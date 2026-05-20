package com.example.second_screen_viewer

import android.app.Activity
import android.app.Presentation
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.hardware.display.DisplayManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.FrameLayout
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import java.io.FileInputStream
import java.io.InputStream
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

class MainActivity : FlutterActivity() {
    private companion object {
        const val CHANNEL_NAME = "second_screen_viewer/display"
        const val REQUEST_PICK_IMAGE = 9101
        const val PREFS_NAME = "second_screen_viewer"
        const val TAG = "SecondScreenViewer"
    }

    private lateinit var channel: MethodChannel
    private var pendingPickResult: MethodChannel.Result? = null
    private var currentPresentation: MediaPresentation? = null

    private val displayManager: DisplayManager by lazy {
        getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
    }

    private val prefs by lazy {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        channel = MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL_NAME)
        channel.setMethodCallHandler { call, result ->
            when (call.method) {
                "getDisplays" -> result.success(getDisplays())
                "getSavedConfig" -> result.success(getSavedConfig())
                "pickImage" -> pickMedia(result)
                "showImage" -> showMedia(call, result)
                "hideImage" -> hideImage(result)
                else -> result.notImplemented()
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode != REQUEST_PICK_IMAGE) {
            return
        }

        val result = pendingPickResult ?: return
        pendingPickResult = null

        if (resultCode != Activity.RESULT_OK) {
            result.success(null)
            return
        }

        val uri = data?.data
        if (uri == null) {
            result.error("NO_MEDIA", "未选择媒体文件", null)
            return
        }

        persistReadPermission(uri, data.flags)
        val name = resolveDisplayName(uri) ?: uri.lastPathSegment ?: "已选择媒体"
        val mimeType = contentResolver.getType(uri) ?: ""
        val mediaType = mimeType.toMediaType()

        prefs.edit()
            .putString("mediaUri", uri.toString())
            .putString("mediaName", name)
            .putString("mediaMimeType", mimeType)
            .putString("mediaType", mediaType)
            .putString("imageUri", uri.toString())
            .putString("imageName", name)
            .apply()

        result.success(
            mapOf(
                "uri" to uri.toString(),
                "name" to name,
                "mimeType" to mimeType,
                "mediaType" to mediaType
            )
        )
    }

    override fun onDestroy() {
        currentPresentation?.dismiss()
        currentPresentation = null
        pendingPickResult?.error("ACTIVITY_DESTROYED", "主界面已关闭", null)
        pendingPickResult = null
        super.onDestroy()
    }

    private fun getDisplays(): List<Map<String, Any?>> {
        return displayManager.displays.map { display ->
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            display.getRealMetrics(metrics)

            mapOf(
                "id" to display.displayId,
                "name" to display.name,
                "width" to metrics.widthPixels,
                "height" to metrics.heightPixels,
                "densityDpi" to metrics.densityDpi,
                "isDefault" to (display.displayId == Display.DEFAULT_DISPLAY),
                "isPresentation" to ((display.flags and Display.FLAG_PRESENTATION) != 0),
                "state" to display.state
            )
        }
    }

    private fun getSavedConfig(): Map<String, Any?> {
        return mapOf(
            "imageUri" to prefs.getString("mediaUri", prefs.getString("imageUri", null)),
            "imageName" to prefs.getString("mediaName", prefs.getString("imageName", null)),
            "mediaUri" to prefs.getString("mediaUri", prefs.getString("imageUri", null)),
            "mediaName" to prefs.getString("mediaName", prefs.getString("imageName", null)),
            "mediaType" to prefs.getString("mediaType", "image"),
            "mediaMimeType" to prefs.getString("mediaMimeType", ""),
            "displayId" to if (prefs.contains("displayId")) prefs.getInt("displayId", -1) else null,
            "scaleMode" to prefs.getString("scaleMode", "centerCrop"),
            "rotationDegrees" to prefs.getInt("rotationDegrees", 0)
        )
    }

    private fun pickMedia(result: MethodChannel.Result) {
        if (pendingPickResult != null) {
            result.error("PICK_IN_PROGRESS", "已经有一个媒体选择任务在进行", null)
            return
        }

        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/*", "video/*"))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }

        pendingPickResult = result
        try {
            startActivityForResult(Intent.createChooser(intent, "选择副屏媒体"), REQUEST_PICK_IMAGE)
        } catch (error: ActivityNotFoundException) {
            pendingPickResult = null
            result.error("NO_FILE_PICKER", "系统没有可用的文件选择器", error.message)
        }
    }

    private fun showMedia(call: MethodCall, result: MethodChannel.Result) {
        val args = call.arguments as? Map<*, *>
        val displayId = args?.get("displayId").asInt()
        val mediaUriString = args?.get("imageUri") as? String
            ?: args?.get("mediaUri") as? String
            ?: prefs.getString("mediaUri", prefs.getString("imageUri", null))
        val scaleMode = args?.get("scaleMode") as? String
            ?: prefs.getString("scaleMode", "centerCrop")
            ?: "centerCrop"
        val rotationDegrees = args?.get("rotationDegrees").asInt()
            ?: prefs.getInt("rotationDegrees", 0)
        val mediaType = (
            args?.get("mediaType") as? String
                ?: prefs.getString("mediaType", null)
                ?: contentResolver.getType(Uri.parse(mediaUriString ?: ""))?.toMediaType()
                ?: "image"
            ).toMediaType()

        if (displayId == null) {
            result.error("NO_DISPLAY", "请选择副屏", null)
            return
        }

        if (mediaUriString.isNullOrBlank()) {
            result.error("NO_MEDIA", "请选择要显示的图片或视频", null)
            return
        }

        val display = displayManager.displays.firstOrNull { it.displayId == displayId }
        if (display == null) {
            result.error("DISPLAY_NOT_FOUND", "找不到 Display $displayId", null)
            return
        }

        val presentation = MediaPresentation(
            this,
            display,
            Uri.parse(mediaUriString),
            mediaType,
            scaleMode.toImageScaleMode(),
            rotationDegrees,
            displayId
        ) { event ->
            sendPresentationEvent(event)
        }
        presentation.setOnDismissListener {
            if (currentPresentation === presentation) {
                currentPresentation = null
                if (::channel.isInitialized) {
                    channel.invokeMethod("presentationDismissed", null)
                }
            }
        }

        try {
            val previousPresentation = currentPresentation
            currentPresentation = null
            previousPresentation?.dismiss()
            presentation.show()
            currentPresentation = presentation

            prefs.edit()
                .putString("mediaUri", mediaUriString)
                .putString("mediaType", mediaType)
                .putString("imageUri", mediaUriString)
                .putInt("displayId", displayId)
                .putString("scaleMode", scaleMode.toImageScaleMode())
                .putInt("rotationDegrees", rotationDegrees)
                .apply()

            result.success(
                mapOf(
                    "displayId" to displayId,
                    "imageUri" to mediaUriString,
                    "mediaUri" to mediaUriString,
                    "mediaType" to mediaType,
                    "scaleMode" to scaleMode.toImageScaleMode(),
                    "rotationDegrees" to rotationDegrees
                )
            )
        } catch (error: WindowManager.InvalidDisplayException) {
            Log.e(TAG, "Invalid display for presentation", error)
            result.error(
                "INVALID_DISPLAY",
                "目标屏幕不可用",
                buildErrorDetails(error, display, mediaUriString, mediaType, scaleMode, rotationDegrees)
            )
        } catch (error: SecurityException) {
            Log.e(TAG, "Media permission error", error)
            result.error(
                "IMAGE_PERMISSION",
                "没有媒体读取权限，请重新选择文件",
                buildErrorDetails(error, display, mediaUriString, mediaType, scaleMode, rotationDegrees)
            )
        } catch (error: Exception) {
            Log.e(TAG, "Failed to show presentation", error)
            result.error(
                "SHOW_FAILED",
                "副屏显示失败",
                buildErrorDetails(error, display, mediaUriString, mediaType, scaleMode, rotationDegrees)
            )
        }
    }

    private fun hideImage(result: MethodChannel.Result) {
        currentPresentation?.dismiss()
        currentPresentation = null
        result.success(true)
    }

    private fun sendPresentationEvent(event: Map<String, Any?>) {
        if (::channel.isInitialized) {
            runOnUiThread {
                channel.invokeMethod("presentationEvent", event)
            }
        }
    }

    private fun persistReadPermission(uri: Uri, flags: Int) {
        val takeFlags = flags and Intent.FLAG_GRANT_READ_URI_PERMISSION
        if (takeFlags == 0) {
            return
        }

        try {
            contentResolver.takePersistableUriPermission(uri, takeFlags)
        } catch (_: SecurityException) {
            // Some providers grant temporary access only. The current session can still use it.
        }
    }

    private fun resolveDisplayName(uri: Uri): String? {
        var cursor: Cursor? = null
        return try {
            cursor = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            if (cursor != null && cursor.moveToFirst()) {
                cursor.getString(0)
            } else {
                null
            }
        } catch (_: RuntimeException) {
            null
        } finally {
            cursor?.close()
        }
    }

    private fun Any?.asInt(): Int? {
        return when (this) {
            is Int -> this
            is Number -> toInt()
            is String -> toIntOrNull()
            else -> null
        }
    }

    private fun buildErrorDetails(
        error: Throwable,
        display: Display?,
        mediaUriString: String?,
        mediaType: String,
        scaleMode: String,
        rotationDegrees: Int,
    ): String {
        val metrics = DisplayMetrics()
        val displayText = if (display == null) {
            "null"
        } else {
            @Suppress("DEPRECATION")
            display.getRealMetrics(metrics)
            "id=${display.displayId}, name=${display.name}, " +
                "size=${metrics.widthPixels}x${metrics.heightPixels}, " +
                "flags=${display.flags}, state=${display.state}"
        }
        val stack = error.stackTrace
            .take(8)
            .joinToString("\n") { "  at $it" }

        return """
            type=${error::class.java.name}
            message=${error.message ?: ""}
            display=$displayText
            mediaUri=$mediaUriString
            mediaType=$mediaType
            scaleMode=${scaleMode.toImageScaleMode()}
            rotationDegrees=$rotationDegrees
            stack:
            $stack
        """.trimIndent()
    }
}

private class MediaPresentation(
    context: Context,
    display: Display,
    private val mediaUri: Uri,
    private val mediaType: String,
    private val scaleMode: String,
    private val rotationDegrees: Int,
    private val displayId: Int,
    private val onEvent: (Map<String, Any?>) -> Unit,
) : Presentation(context, display) {
    override fun onCreate(savedInstanceState: Bundle?) {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        super.onCreate(savedInstanceState)

        window?.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )
        window?.setLayout(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT
        )
        window?.setBackgroundDrawableResource(android.R.color.black)

        @Suppress("DEPRECATION")
        window?.decorView?.systemUiVisibility =
            View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE

        val root = FrameLayout(context).apply {
            setBackgroundColor(Color.BLACK)
            if (mediaType == "video") {
                addView(
                    PresentationVideoView(
                        context,
                        mediaUri,
                        scaleMode,
                        rotationDegrees,
                        displayId,
                        onEvent
                    ),
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                    )
                )
            } else {
                onEvent(
                    mapOf(
                        "type" to "imageShown",
                        "mediaType" to mediaType,
                        "displayId" to displayId,
                        "uri" to mediaUri.toString(),
                        "message" to "图片窗口已显示"
                    )
                )
                addView(
                    PresentationImageView(context, mediaUri, scaleMode, rotationDegrees),
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                    )
                )
            }
        }

        setContentView(root)
    }
}

private class PresentationVideoView(
    context: Context,
    private val videoUri: Uri,
    private val scaleMode: String,
    rotationDegrees: Int,
    private val displayId: Int,
    private val onEvent: (Map<String, Any?>) -> Unit,
) : TextureView(context), TextureView.SurfaceTextureListener {
    private val rotationDegrees = rotationDegrees.normalizedRightAngle()
    private var mediaPlayer: MediaPlayer? = null
    private var surface: Surface? = null
    private var videoWidth = 0
    private var videoHeight = 0

    init {
        surfaceTextureListener = this
    }

    override fun onSurfaceTextureAvailable(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
        surface = Surface(surfaceTexture)
        onEvent(
            mapOf(
                "type" to "videoSurfaceReady",
                "mediaType" to "video",
                "displayId" to displayId,
                "width" to width,
                "height" to height,
                "uri" to videoUri.toString(),
                "message" to "视频窗口已创建"
            )
        )
        preparePlayer()
    }

    override fun onSurfaceTextureSizeChanged(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
        updateTransform()
    }

    override fun onSurfaceTextureDestroyed(surfaceTexture: SurfaceTexture): Boolean {
        onEvent(
            mapOf(
                "type" to "videoSurfaceDestroyed",
                "mediaType" to "video",
                "displayId" to displayId,
                "uri" to videoUri.toString(),
                "message" to "视频窗口已销毁"
            )
        )
        releasePlayer()
        return true
    }

    override fun onSurfaceTextureUpdated(surfaceTexture: SurfaceTexture) = Unit

    override fun onDetachedFromWindow() {
        onEvent(
            mapOf(
                "type" to "videoDetached",
                "mediaType" to "video",
                "displayId" to displayId,
                "uri" to videoUri.toString(),
                "message" to "视频视图已释放"
            )
        )
        releasePlayer()
        super.onDetachedFromWindow()
    }

    private fun preparePlayer() {
        val targetSurface = surface ?: return
        releasePlayer(keepSurface = true)
        onEvent(
            mapOf(
                "type" to "videoPreparing",
                "mediaType" to "video",
                "displayId" to displayId,
                "uri" to videoUri.toString(),
                "message" to "播放器开始准备"
            )
        )

        mediaPlayer = MediaPlayer().apply {
            setDataSourceForUri(videoUri)
            setSurface(targetSurface)
            isLooping = true
            setOnVideoSizeChangedListener { _, videoW, videoH ->
                this@PresentationVideoView.videoWidth = videoW
                this@PresentationVideoView.videoHeight = videoH
                Log.i(
                    "SecondScreenViewer",
                    "Video size changed ${videoW}x$videoH uri=$videoUri"
                )
                onEvent(
                    mapOf(
                        "type" to "videoSizeChanged",
                        "mediaType" to "video",
                        "displayId" to displayId,
                        "width" to videoW,
                        "height" to videoH,
                        "uri" to videoUri.toString()
                    )
                )
                updateTransform()
            }
            setOnPreparedListener {
                this@PresentationVideoView.videoWidth = it.videoWidth
                this@PresentationVideoView.videoHeight = it.videoHeight
                Log.i(
                    "SecondScreenViewer",
                    "Video prepared ${it.videoWidth}x${it.videoHeight} uri=$videoUri"
                )
                updateTransform()
                it.start()
                onEvent(
                    mapOf(
                        "type" to "videoStarted",
                        "mediaType" to "video",
                        "displayId" to displayId,
                        "width" to it.videoWidth,
                        "height" to it.videoHeight,
                        "uri" to videoUri.toString(),
                        "message" to "视频已开始播放"
                    )
                )
            }
            setOnErrorListener { _, what, extra ->
                Log.e(
                    "SecondScreenViewer",
                    "Video playback error what=$what extra=$extra uri=$videoUri"
                )
                onEvent(
                    mapOf(
                        "type" to "videoError",
                        "mediaType" to "video",
                        "displayId" to displayId,
                        "uri" to videoUri.toString(),
                        "message" to "视频播放失败 what=$what extra=$extra"
                    )
                )
                true
            }
            setOnInfoListener { _, what, extra ->
                Log.i("SecondScreenViewer", "Video playback info what=$what extra=$extra uri=$videoUri")
                onEvent(
                    mapOf(
                        "type" to "videoInfo",
                        "mediaType" to "video",
                        "displayId" to displayId,
                        "uri" to videoUri.toString(),
                        "message" to "播放器信息 what=$what extra=$extra"
                    )
                )
                false
            }
            prepareAsync()
        }
    }

    private fun MediaPlayer.setDataSourceForUri(uri: Uri) {
        when (uri.scheme?.lowercase(Locale.US)) {
            "http", "https" -> setDataSource(uri.toString())
            "file" -> setDataSource(uri.path ?: uri.toString())
            else -> setDataSource(context, uri)
        }
    }

    private fun updateTransform() {
        if (width <= 0 || height <= 0 || videoWidth <= 0 || videoHeight <= 0) {
            return
        }

        setTransform(
            buildTextureViewMatrix(
                mediaWidth = videoWidth.toFloat(),
                mediaHeight = videoHeight.toFloat(),
                viewWidth = width.toFloat(),
                viewHeight = height.toFloat(),
                rotationDegrees = rotationDegrees,
                scaleMode = scaleMode
            )
        )
    }

    private fun releasePlayer(keepSurface: Boolean = false) {
        mediaPlayer?.run {
            try {
                stop()
            } catch (_: IllegalStateException) {
                // Player may not have reached the started state.
            }
            reset()
            release()
        }
        mediaPlayer = null

        if (!keepSurface) {
            surface?.release()
            surface = null
        }
    }
}

private class PresentationImageView(
    context: Context,
    private val imageUri: Uri,
    private val scaleMode: String,
    rotationDegrees: Int,
) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    private val rotationDegrees = rotationDegrees.normalizedRightAngle()
    private var bitmap: Bitmap? = null

    init {
        setBackgroundColor(Color.BLACK)
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        if (width > 0 && height > 0 && bitmap == null) {
            bitmap = decodeBitmap(width, height)
        }
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.BLACK)

        val currentBitmap = bitmap ?: decodeBitmap(width, height)?.also {
            bitmap = it
        } ?: return

        if (width <= 0 || height <= 0) {
            return
        }

        canvas.drawBitmap(
            currentBitmap,
            buildMediaMatrix(
                mediaWidth = currentBitmap.width.toFloat(),
                mediaHeight = currentBitmap.height.toFloat(),
                viewWidth = width.toFloat(),
                viewHeight = height.toFloat(),
                rotationDegrees = rotationDegrees,
                scaleMode = scaleMode
            ),
            paint
        )
    }

    override fun onDetachedFromWindow() {
        bitmap?.recycle()
        bitmap = null
        super.onDetachedFromWindow()
    }

    private fun decodeBitmap(targetWidth: Int, targetHeight: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }

        try {
            openImageStream()?.use {
                BitmapFactory.decodeStream(it, null, bounds)
            }

            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                return null
            }

            val requestedWidth = if (rotationDegrees % 180 == 0) targetWidth else targetHeight
            val requestedHeight = if (rotationDegrees % 180 == 0) targetHeight else targetWidth
            val options = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
                inSampleSize = calculateInSampleSize(bounds, requestedWidth, requestedHeight)
            }

            return openImageStream()?.use {
                BitmapFactory.decodeStream(it, null, options)
            }
        } catch (_: Exception) {
            return null
        }
    }

    private fun openImageStream(): InputStream? {
        return if (imageUri.scheme == "file") {
            imageUri.path?.let { FileInputStream(it) }
        } else {
            context.contentResolver.openInputStream(imageUri)
        }
    }

    private fun calculateInSampleSize(
        options: BitmapFactory.Options,
        requestedWidth: Int,
        requestedHeight: Int
    ): Int {
        var sampleSize = 1
        var halfWidth = options.outWidth / 2
        var halfHeight = options.outHeight / 2

        while (
            halfWidth / sampleSize >= requestedWidth &&
            halfHeight / sampleSize >= requestedHeight
        ) {
            sampleSize *= 2
        }

        return max(sampleSize, 1)
    }

}

private fun String?.toImageScaleMode(): String {
    return when (this) {
        "fitCenter", "fitXY", "centerInside" -> this
        else -> "centerCrop"
    }
}

private fun String?.toMediaType(): String {
    return when {
        this?.startsWith("video/") == true -> "video"
        this == "video" -> "video"
        else -> "image"
    }
}

private fun buildMediaMatrix(
    mediaWidth: Float,
    mediaHeight: Float,
    viewWidth: Float,
    viewHeight: Float,
    rotationDegrees: Int,
    scaleMode: String,
): Matrix {
    val matrix = Matrix().apply {
        postTranslate(-mediaWidth / 2f, -mediaHeight / 2f)
        postRotate(rotationDegrees.toFloat())
    }
    val bounds = RectF(0f, 0f, mediaWidth, mediaHeight)
    matrix.mapRect(bounds)

    val scaleX = viewWidth / bounds.width()
    val scaleY = viewHeight / bounds.height()
    val scale = when (scaleMode) {
        "fitCenter" -> min(scaleX, scaleY)
        "fitXY" -> null
        "centerInside" -> min(1f, min(scaleX, scaleY))
        else -> max(scaleX, scaleY)
    }

    if (scale == null) {
        matrix.postScale(scaleX, scaleY)
    } else {
        matrix.postScale(scale, scale)
    }
    matrix.postTranslate(viewWidth / 2f, viewHeight / 2f)

    return matrix
}

private fun buildTextureViewMatrix(
    mediaWidth: Float,
    mediaHeight: Float,
    viewWidth: Float,
    viewHeight: Float,
    rotationDegrees: Int,
    scaleMode: String,
): Matrix {
    return Matrix().apply {
        setScale(mediaWidth / viewWidth, mediaHeight / viewHeight)
        postConcat(
            buildMediaMatrix(
                mediaWidth = mediaWidth,
                mediaHeight = mediaHeight,
                viewWidth = viewWidth,
                viewHeight = viewHeight,
                rotationDegrees = rotationDegrees,
                scaleMode = scaleMode
            )
        )
    }
}

private fun Int.normalizedRightAngle(): Int {
    val normalized = ((this % 360) + 360) % 360
    return when (normalized) {
        in 45 until 135 -> 90
        in 135 until 225 -> 180
        in 225 until 315 -> 270
        else -> 0
    }
}
