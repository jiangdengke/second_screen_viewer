package com.example.second_screen_viewer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URL
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject

class ControlHttpService : Service() {
    companion object {
        const val CONTROL_PORT = 9999
        private const val TAG = "SecondScreenViewer"
        private const val APP_VERSION_LABEL = "版本 1.3.0-beta.3 (20)"
        private const val NOTIFICATION_CHANNEL_ID = "second_screen_control"
        private const val NOTIFICATION_ID = 1001
        private const val BOOT_EVENT_ELAPSED = "bootEventElapsed"

        @Volatile
        private var serverStatus: String = "未启动"

        @Volatile
        private var lastServiceMessage: String? = null

        fun start(context: Context, bootEventElapsed: Long? = null) {
            val intent = Intent(context, ControlHttpService::class.java)
            if (bootEventElapsed != null) intent.putExtra(BOOT_EVENT_ELAPSED, bootEventElapsed)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun serviceStatus(context: Context): Map<String, Any?> {
            return mapOf(
                "controlUrl" to resolveControlAddress(CONTROL_PORT),
                "httpStatus" to serverStatus,
                "port" to CONTROL_PORT,
                "message" to lastServiceMessage,
                "config" to SecondScreenDisplayController.getSavedConfig(context)
            )
        }

        fun resolveControlAddress(port: Int): String {
            try {
                val interfaces = NetworkInterface.getNetworkInterfaces()
                while (interfaces.hasMoreElements()) {
                    val networkInterface = interfaces.nextElement()
                    val addresses = networkInterface.inetAddresses
                    while (addresses.hasMoreElements()) {
                        val address = addresses.nextElement()
                        if (address is Inet4Address && !address.isLoopbackAddress) {
                            return "http://${address.hostAddress}:$port"
                        }
                    }
                }
            } catch (_: Exception) {
                // Fall back to all interfaces below.
            }

            return "http://0.0.0.0:$port"
        }
    }

    private val clientExecutor = Executors.newCachedThreadPool()
    private val slideshowExecutor = Executors.newSingleThreadScheduledExecutor()
    private val remoteMediaCache = ConcurrentHashMap<String, File>()
    private var slideshowFuture: ScheduledFuture<*>? = null
    private var serverThread: Thread? = null
    private var serverSocket: ServerSocket? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var serviceLease = 0L
    private var bootDeadline: Long? = null
    private val bootRestore = object : Runnable {
        override fun run() {
            val deadline = bootDeadline ?: return
            if (MediaFallbackCoordinator.gate.restoreToken(serviceLease) == null) return
            val remaining = deadline - SystemClock.elapsedRealtime()
            if (remaining > 0) {
                mainHandler.postDelayed(this, remaining)
            } else {
                bootDeadline = null
                MediaFallbackCoordinator.restore(applicationContext, serviceLease, ::handlePresentationEvent)
            }
        }
    }

    @Volatile
    private var running = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        serviceLease = MediaFallbackCoordinator.gate.attachService { stopImageSlideshow() }
        startHttpServerIfNeeded()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())
        startHttpServerIfNeeded()
        val bootEvent = intent?.takeIf { it.hasExtra(BOOT_EVENT_ELAPSED) }
            ?.getLongExtra(BOOT_EVENT_ELAPSED, -1L)
        MediaFallbackCoordinator.resume(applicationContext, serviceLease, bootEvent) { record ->
            mainHandler.removeCallbacks(bootRestore)
            bootDeadline = record.deadlineElapsed
            mainHandler.postDelayed(bootRestore, maxOf(0L, record.deadlineElapsed - SystemClock.elapsedRealtime()))
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        MediaFallbackCoordinator.gate.detachService(serviceLease)
        mainHandler.removeCallbacks(bootRestore)
        serverStatus = "已停止"
        stopImageSlideshow()
        clientExecutor.execute { pruneRemoteMediaCache() }
        try {
            serverSocket?.close()
        } catch (_: IOException) {
            // Best effort shutdown.
        }
        clientExecutor.shutdown()
        slideshowExecutor.shutdown()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startHttpServerIfNeeded() {
        if (running) {
            return
        }

        running = true
        serverStatus = "启动中"
        serverThread = Thread({ runHttpServer() }, "second-screen-http-server").apply {
            isDaemon = true
            start()
        }
    }

    private fun runHttpServer() {
        try {
            ServerSocket().use { socket ->
                socket.reuseAddress = true
                socket.bind(InetSocketAddress(InetAddress.getByName("0.0.0.0"), CONTROL_PORT))
                serverSocket = socket
                serverStatus = "运行中"
                lastServiceMessage = "HTTP服务启动成功：${resolveControlAddress(CONTROL_PORT)}"
                Log.i(TAG, lastServiceMessage ?: "HTTP service started")

                while (running) {
                    val client = socket.accept()
                    clientExecutor.execute { handleClient(client) }
                }
            }
        } catch (error: SocketException) {
            if (running) {
                serverStatus = "启动失败：${error.message ?: error::class.java.simpleName}"
                lastServiceMessage = serverStatus
                Log.e(TAG, "HTTP server socket error", error)
            }
        } catch (error: Exception) {
            serverStatus = "启动失败：${error.message ?: error::class.java.simpleName}"
            lastServiceMessage = serverStatus
            Log.e(TAG, "HTTP server failed", error)
        } finally {
            running = false
        }
    }

    private fun handleClient(socket: Socket) {
        try {
            socket.use { client ->
                try {
                    val request = readHttpRequest(client)
                    if (request == null) {
                        writeJsonResponse(
                            client,
                            HttpStatus.badRequest,
                            errorResponse("BAD_REQUEST", "请求格式错误")
                        )
                        return
                    }

                    Log.i(TAG, "HTTP调用：${request.method} ${request.path} from=${request.remoteAddress}")
                    val response = routeRequest(request)
                    if (response.statusCode == HttpStatus.noContent) {
                        writeEmptyResponse(client, response.statusCode)
                    } else {
                        writeJsonResponse(client, response.statusCode, response.payload)
                    }
                } catch (error: Exception) {
                    Log.e(TAG, "HTTP request failed", error)
                    writeJsonResponse(
                        client,
                        HttpStatus.badRequest,
                        errorResponse("REQUEST_FAILED", error.message ?: error.toString())
                    )
                }
            }
        } catch (error: IOException) {
            // Includes a failed error response and Socket.close(), outside the inner catch.
            Log.w(TAG, "HTTP client I/O ended", error)
        } finally {
            pruneRemoteMediaCache()
        }
    }

    private fun routeRequest(request: HttpRequestData): RouteResponse {
        if (request.method == "OPTIONS") {
            return RouteResponse(HttpStatus.noContent, emptyMap())
        }

        if (request.method == "GET" && request.path == "/api/status") {
            return RouteResponse(HttpStatus.ok, successResponse(statusPayload()))
        }

        if (request.method == "GET" && request.path == "/api/displays") {
            return RouteResponse(
                HttpStatus.ok,
                successResponse(
                    mapOf("displays" to SecondScreenDisplayController.getDisplays(this))
                )
            )
        }

        if (request.method == "POST" &&
            (request.path == "/api/hide" || request.path == "/robot_task/screen_stop")
        ) {
            Log.i(TAG, "HTTP停止调用：${request.path}")
            val token = requireNotNull(request.commandToken)
            SecondScreenDisplayController.hideMedia(this, token)
            MediaFallbackCoordinator.gate.runIfCurrent(token) {
                lastServiceMessage = "已停止副屏显示"
            }
            return RouteResponse(HttpStatus.ok, successResponse(statusPayload()))
        }

        if (request.method != "POST") {
            return RouteResponse(
                HttpStatus.methodNotAllowed,
                errorResponse("METHOD_NOT_ALLOWED", "只支持 POST 请求")
            )
        }

        val payload = readJsonBody(request.body)
        return when (request.path) {
            "/robot_task/screen_control" -> handleVideoControlRequest(payload, requireNotNull(request.commandToken))
            "/robot_task/screen_control_img_display" -> handleImageControlRequest(payload, requireNotNull(request.commandToken))
            else -> RouteResponse(
                HttpStatus.notFound,
                errorResponse("NOT_FOUND", "未知接口：${request.path}")
            )
        }
    }

    private fun handleVideoControlRequest(payload: JSONObject, token: MediaWorkToken): RouteResponse {
        val url = stringValue(payload.opt("url"))
        if (url.isNullOrEmpty()) {
            throw IllegalArgumentException("缺少 url")
        }

        val mediaType = stringValue(payload.opt("mediaType")) ?: inferMediaType(url)
        val displayId = intValue(firstPresentValue(payload, "displayId", "display_id"))
        val scaleMode = scaleModeFromPayload(payload)
        val rotationDegrees = rotationDegreesFromPayload(payload)
        Log.i(
            TAG,
            "HTTP视频调用：url=$url display=${displayId ?: "默认副屏"} " +
                "scale=$scaleMode rotation=$rotationDegrees"
        )

        val resolvedUri = resolveRemoteMediaUriForDisplay(url, mediaType, token)
        val request = DisplayMediaRequest(
            displayId = displayId,
            mediaUri = resolvedUri,
            mediaName = fileNameFromUrl(url),
            sourceUri = url,
            mediaType = mediaType,
            scaleMode = scaleMode,
            rotationDegrees = rotationDegrees,
        )
        val showResult = if (mediaType == "image" || mediaType.startsWith("image/")) {
            showHttpImage(request, token)
        } else {
            SecondScreenDisplayController.showMedia(this, request, token, ::handlePresentationEvent)
        }
        MediaFallbackCoordinator.gate.runIfCurrent(token) {
            lastServiceMessage = "视频显示成功：display=${showResult["displayId"]}"
        }

        return RouteResponse(
            HttpStatus.ok,
            successResponse(
                statusPayload() + mapOf(
                    "displayId" to showResult["displayId"],
                    "url" to url
                )
            )
        )
    }

    private fun handleImageControlRequest(payload: JSONObject, token: MediaWorkToken): RouteResponse {
        val urls = urlListValue(payload.opt("url"))
        if (urls.isEmpty()) {
            throw IllegalArgumentException("缺少 url 图片列表")
        }

        val intervalMs = intValue(payload.opt("time_sleep")) ?: 2000
        val displayId = intValue(firstPresentValue(payload, "displayId", "display_id"))
        val scaleMode = scaleModeFromPayload(payload)
        val rotationDegrees = rotationDegreesFromPayload(payload)
        Log.i(
            TAG,
            "HTTP图片轮播调用：count=${urls.size} interval=${intervalMs}ms " +
                "display=${displayId ?: "默认副屏"} scale=$scaleMode rotation=$rotationDegrees"
        )

        val shownDisplayId = startImageSlideshow(
            urls = urls,
            intervalMs = intervalMs,
            displayId = displayId,
            scaleMode = scaleMode,
            rotationDegrees = rotationDegrees,
            token = token,
        )
        MediaFallbackCoordinator.gate.runIfCurrent(token) {
            lastServiceMessage = "图片轮播启动：display=$shownDisplayId count=${urls.size}"
        }

        return RouteResponse(
            HttpStatus.ok,
            successResponse(
                statusPayload() + mapOf(
                    "displayId" to shownDisplayId,
                    "time_sleep" to intervalMs,
                    "url" to urls
                )
            )
        )
    }

    private fun startImageSlideshow(
        urls: List<String>,
        intervalMs: Int,
        displayId: Int?,
        scaleMode: String,
        rotationDegrees: Int,
        token: MediaWorkToken,
    ): Int {
        val normalizedIntervalMs = if (intervalMs < 500) 500 else intervalMs
        var index = 0

        fun showNextImage(): Int {
            if (!MediaFallbackCoordinator.gate.isCurrent(token)) throw ObsoleteMediaWorkException()
            val currentIndex = index % urls.size
            val sourceUrl = urls[currentIndex]
            index += 1
            val displayUri = resolveRemoteMediaUriForDisplay(sourceUrl, "image", token)
            val showResult = showHttpImage(
                DisplayMediaRequest(
                    displayId = displayId,
                    mediaUri = displayUri,
                    mediaName = "${fileNameFromUrl(sourceUrl)} (${currentIndex + 1}/${urls.size})",
                    sourceUri = sourceUrl,
                    mediaType = "image",
                    scaleMode = scaleMode,
                    rotationDegrees = rotationDegrees,
                ),
                token,
            )
            return (showResult["displayId"] as? Number)?.toInt()
                ?: SecondScreenDisplayController.resolveDisplayId(this, displayId)
                ?: throw SecondScreenDisplayException("NO_DISPLAY", "请选择副屏")
        }

        val shownDisplayId = showNextImage()
        if (urls.size > 1) {
            MediaFallbackCoordinator.gate.withCurrent(token) {
                slideshowFuture = slideshowExecutor.scheduleWithFixedDelay(
                    {
                        if (!MediaFallbackCoordinator.gate.isCurrent(token)) {
                            return@scheduleWithFixedDelay
                        }
                        try {
                            showNextImage()
                        } catch (error: Exception) {
                            MediaFallbackCoordinator.gate.runIfCurrent(token) {
                                lastServiceMessage = "图片轮播失败：${error.message ?: error.toString()}"
                                Log.e(TAG, "Image slideshow failed", error)
                            }
                        } finally {
                            pruneRemoteMediaCache()
                        }
                    },
                    normalizedIntervalMs.toLong(),
                    normalizedIntervalMs.toLong(),
                    TimeUnit.MILLISECONDS
                )
            }
            Log.i(TAG, "图片轮播：${urls.size}张 interval=${normalizedIntervalMs}ms")
        }

        return shownDisplayId
    }

    private fun showHttpImage(request: DisplayMediaRequest, token: MediaWorkToken): Map<String, Any?> {
        val prepared = MediaFallbackCoordinator.prepareImage(applicationContext, request, token)
        try {
            val displayRequest = prepared.imageFile?.let {
                request.copy(mediaUri = Uri.fromFile(it).toString())
            } ?: request
            return SecondScreenDisplayController.showPreparedImage(
                this, displayRequest, prepared, token, onEvent = ::handlePresentationEvent,
            )
        } finally {
            prepared.releaseIfUnadopted()
        }
    }

    private fun stopImageSlideshow() {
        synchronized(MediaFallbackCoordinator.gate) {
            slideshowFuture?.cancel(false)
            slideshowFuture = null
        }
    }

    private fun pruneRemoteMediaCache() {
        val currentUri = SecondScreenDisplayController.getSavedConfig(this)["mediaUri"]
        remoteMediaCache.entries.forEach { (key, file) ->
            val generation = key.substringBefore(':').toLongOrNull()
            val activeCommand = generation != null && MediaFallbackCoordinator.gate.isCurrent(
                MediaWorkToken(generation, serviceLease),
            )
            if (activeCommand || Uri.fromFile(file).toString() == currentUri) return@forEach
            try {
                if (remoteMediaCache.remove(key, file)) {
                    file.delete()
                }
            } catch (_: SecurityException) {
                // Cache cleanup is best effort.
            }
        }
    }

    private fun resolveRemoteMediaUriForDisplay(
        sourceUrl: String, mediaType: String, token: MediaWorkToken,
    ): String {
        if (!MediaFallbackCoordinator.gate.isCurrent(token)) throw ObsoleteMediaWorkException()
        val uri = Uri.parse(sourceUrl)
        val scheme = uri.scheme?.lowercase(Locale.US)
        if (scheme != "http" && scheme != "https") {
            return sourceUrl
        }

        // Generation is part of the key: a late old download never becomes a new command's source.
        val cacheKey = "${token.generation}:$sourceUrl"
        val cachedFile = remoteMediaCache[cacheKey]
        if (cachedFile != null && cachedFile.exists()) {
            return Uri.fromFile(cachedFile).toString()
        }

        val cacheDirectory = File(cacheDir, "second_screen_viewer/media_cache")
        if (!cacheDirectory.exists()) {
            cacheDirectory.mkdirs()
        }

        val file = File.createTempFile("media-", "_${safeMediaFileName(sourceUrl, mediaType)}", cacheDirectory)
        val connection = URL(sourceUrl).openConnection() as HttpURLConnection
        try {
            connection.instanceFollowRedirects = true
            connection.connectTimeout = 15000
            connection.readTimeout = 60000
            connection.connect()
            if (connection.responseCode < 200 || connection.responseCode >= 300) {
                throw IOException("下载媒体失败：HTTP ${connection.responseCode}")
            }
            connection.inputStream.use { input ->
                FileOutputStream(file).use { output ->
                    input.copyTo(output)
                }
            }
            MediaFallbackCoordinator.gate.withCurrent(token) { remoteMediaCache[cacheKey] = file }
            Log.i(TAG, "媒体缓存完成：$sourceUrl -> ${file.absolutePath}")
            return Uri.fromFile(file).toString()
        } catch (error: Exception) {
            try {
                if (file.exists()) {
                    file.delete()
                }
            } catch (_: SecurityException) {
                // Partial cache cleanup is best effort.
            }
            throw error
        } finally {
            connection.disconnect()
        }
    }

    private fun handlePresentationEvent(event: Map<String, Any?>) {
        val type = event["type"]?.toString() ?: "unknown"
        val message = event["message"]?.toString()
        lastServiceMessage = if (message.isNullOrEmpty()) type else "$type：$message"
        if (type == "videoError") {
            Log.e(TAG, "Presentation event: $event")
        } else {
            Log.i(TAG, "Presentation event: $event")
        }
    }

    private fun statusPayload(): Map<String, Any?> {
        val config = SecondScreenDisplayController.getSavedConfig(this)
        return mapOf(
            "version" to APP_VERSION_LABEL,
            "controlUrl" to resolveControlAddress(CONTROL_PORT),
            "httpStatus" to serverStatus,
            "message" to lastServiceMessage,
            "isShowing" to config["isShowing"],
            "mediaUri" to config["mediaUri"],
            "mediaName" to config["mediaName"],
            "mediaType" to config["mediaType"],
            "displayId" to config["displayId"],
            "scaleMode" to config["scaleMode"],
            "rotationDegrees" to config["rotationDegrees"]
        )
    }

    private fun readHttpRequest(socket: Socket): HttpRequestData? {
        val input = socket.getInputStream()
        val requestLine = readHttpLine(input) ?: return null
        val requestParts = requestLine.split(" ")
        if (requestParts.size < 2) {
            return null
        }
        val method = requestParts[0].uppercase(Locale.US)
        val path = MediaCommandRoutes.normalizePath(requestParts[1])
        val commandToken = if (MediaCommandRoutes.isMediaCommand(method, path)) {
            val (token, persisted) = MediaFallbackCoordinator.admit(applicationContext, serviceLease)
            // Admission precedes headers/body, including malformed or stalled recognized requests.
            persisted.awaitMediaWork()
            token
        } else null

        var contentLength = 0
        while (true) {
            val headerLine = readHttpLine(input) ?: return null
            if (headerLine.isEmpty()) {
                break
            }
            val separatorIndex = headerLine.indexOf(':')
            if (separatorIndex > 0) {
                val name = headerLine.substring(0, separatorIndex).trim().lowercase(Locale.US)
                val value = headerLine.substring(separatorIndex + 1).trim()
                if (name == "content-length") {
                    contentLength = value.toIntOrNull() ?: 0
                }
            }
        }

        val body = if (contentLength > 0) {
            val bodyBytes = ByteArray(contentLength)
            var offset = 0
            while (offset < contentLength) {
                val read = input.read(bodyBytes, offset, contentLength - offset)
                if (read < 0) {
                    break
                }
                offset += read
            }
            bodyBytes.copyOf(offset).toString(Charsets.UTF_8)
        } else {
            ""
        }

        return HttpRequestData(
            method = method,
            path = path,
            body = body,
            remoteAddress = socket.inetAddress?.hostAddress ?: "unknown",
            commandToken = commandToken,
        )
    }

    private fun readHttpLine(input: java.io.InputStream): String? {
        val buffer = ByteArrayOutputStream()
        while (true) {
            val value = input.read()
            if (value == -1) {
                return if (buffer.size() == 0) null else buffer.toString(Charsets.UTF_8.name())
            }
            if (value == '\n'.code) {
                break
            }
            if (value != '\r'.code) {
                buffer.write(value)
            }
        }
        return buffer.toString(Charsets.UTF_8.name())
    }

    private fun writeEmptyResponse(socket: Socket, statusCode: Int) {
        val output = BufferedOutputStream(socket.getOutputStream())
        val headers = buildString {
            append("HTTP/1.1 $statusCode ${reasonPhrase(statusCode)}\r\n")
            appendCorsHeaders()
            append("Content-Length: 0\r\n")
            append("Connection: close\r\n")
            append("\r\n")
        }
        output.write(headers.toByteArray(Charsets.UTF_8))
        output.flush()
    }

    private fun writeJsonResponse(socket: Socket, statusCode: Int, payload: Map<String, Any?>) {
        val output = BufferedOutputStream(socket.getOutputStream())
        val body = JSONObject(payload).toString().toByteArray(Charsets.UTF_8)
        val headers = buildString {
            append("HTTP/1.1 $statusCode ${reasonPhrase(statusCode)}\r\n")
            appendCorsHeaders()
            append("Content-Type: application/json; charset=utf-8\r\n")
            append("Content-Length: ${body.size}\r\n")
            append("Connection: close\r\n")
            append("\r\n")
        }
        output.write(headers.toByteArray(Charsets.UTF_8))
        output.write(body)
        output.flush()
    }

    private fun StringBuilder.appendCorsHeaders() {
        append("Access-Control-Allow-Origin: *\r\n")
        append("Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n")
        append("Access-Control-Allow-Headers: Content-Type, Authorization\r\n")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            "副屏控制服务",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "保持 9999 端口后台监听"
        }
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        return builder
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("副屏控制服务运行中")
            .setContentText("正在后台监听 ${resolveControlAddress(CONTROL_PORT)}")
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun readJsonBody(body: String): JSONObject {
        if (body.trim().isEmpty()) {
            return JSONObject()
        }
        return JSONObject(body)
    }

    private fun successResponse(data: Map<String, Any?>): Map<String, Any?> {
        return mapOf("code" to 200, "msg" to "success", "data" to data)
    }

    private fun errorResponse(error: String, message: String): Map<String, Any?> {
        return mapOf("code" to 400, "msg" to message, "error" to error)
    }

    private fun firstPresentValue(payload: JSONObject, vararg names: String): Any? {
        for (name in names) {
            if (payload.has(name)) {
                return payload.opt(name)
            }
        }
        return null
    }

    private fun stringValue(value: Any?): String? {
        return when (value) {
            null, JSONObject.NULL -> null
            is String -> value
            else -> value.toString()
        }
    }

    private fun intValue(value: Any?): Int? {
        return when (value) {
            null, JSONObject.NULL -> null
            is Int -> value
            is Number -> value.toInt()
            is String -> value.toIntOrNull()
            else -> null
        }
    }

    private fun urlListValue(value: Any?): List<String> {
        return when (value) {
            null, JSONObject.NULL -> emptyList()
            is JSONArray -> (0 until value.length())
                .mapNotNull { index -> stringValue(value.opt(index)) }
                .filter { it.isNotBlank() }
            is String -> listOf(value).filter { it.isNotBlank() }
            else -> emptyList()
        }
    }

    private fun scaleModeFromPayload(payload: JSONObject): String {
        return stringValue(firstPresentValue(payload, "scaleMode", "scale_mode")).toImageScaleMode()
    }

    private fun rotationDegreesFromPayload(payload: JSONObject): Int {
        return intValue(firstPresentValue(payload, "rotationDegrees", "rotation_degrees", "rotation")) ?: 0
    }

    private fun inferMediaType(url: String): String {
        val lower = url.lowercase(Locale.US).substringBefore('?')
        return if (
            lower.endsWith(".jpg") ||
            lower.endsWith(".jpeg") ||
            lower.endsWith(".png") ||
            lower.endsWith(".webp") ||
            lower.endsWith(".bmp") ||
            lower.endsWith(".gif")
        ) {
            "image"
        } else {
            "video"
        }
    }

    private fun fileNameFromUrl(url: String): String {
        return Uri.parse(url).lastPathSegment?.takeIf { it.isNotBlank() } ?: url
    }

    private fun safeMediaFileName(sourceUrl: String, mediaType: String): String {
        val original = Uri.parse(sourceUrl).lastPathSegment?.takeIf { it.isNotBlank() }
            ?: if (mediaType == "video") "media.mp4" else "image.png"
        val sanitized = original.replace(Regex("[^A-Za-z0-9._-]"), "_").take(80)
        return sanitized.ifBlank { if (mediaType == "video") "media.mp4" else "image.png" }
    }

    private fun String?.toImageScaleMode(): String {
        return when (this) {
            "fitCenter", "fitXY", "centerInside" -> this
            else -> "centerCrop"
        }
    }

    private fun reasonPhrase(statusCode: Int): String {
        return when (statusCode) {
            HttpStatus.ok -> "OK"
            HttpStatus.noContent -> "No Content"
            HttpStatus.badRequest -> "Bad Request"
            HttpStatus.notFound -> "Not Found"
            HttpStatus.methodNotAllowed -> "Method Not Allowed"
            else -> "OK"
        }
    }
}

private data class HttpRequestData(
    val method: String,
    val path: String,
    val body: String,
    val remoteAddress: String,
    val commandToken: MediaWorkToken?,
)

private data class RouteResponse(
    val statusCode: Int,
    val payload: Map<String, Any?>,
)

private object HttpStatus {
    const val ok = 200
    const val noContent = 204
    const val badRequest = 400
    const val notFound = 404
    const val methodNotAllowed = 405
}
