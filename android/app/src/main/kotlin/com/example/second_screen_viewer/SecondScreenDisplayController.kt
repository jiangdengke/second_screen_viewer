package com.example.second_screen_viewer

import android.app.Presentation
import android.content.Context
import android.content.Intent
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
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.FrameLayout
import java.io.FileInputStream
import java.io.InputStream
import java.util.Locale
import java.util.concurrent.CountDownLatch
import kotlin.math.max
import kotlin.math.min

data class DisplayMediaRequest(
    val displayId: Int?,
    val mediaUri: String?,
    val mediaName: String?,
    val sourceUri: String?,
    val mediaType: String?,
    val scaleMode: String?,
    val rotationDegrees: Int,
)

class SecondScreenDisplayException(
    val code: String,
    override val message: String,
    val details: String? = null,
) : Exception(message)

object SecondScreenDisplayController {
    private const val PREFS_NAME = "second_screen_viewer"
    private const val TAG = "SecondScreenViewer"
    private const val PREF_IS_SHOWING = "isShowing"
    private const val PREF_LAST_SHOWN_MEDIA_URI = "lastShownMediaUri"
    private const val PREF_LAST_SHOWN_MEDIA_NAME = "lastShownMediaName"
    private const val PREF_LAST_SHOWN_MEDIA_TYPE = "lastShownMediaType"
    private const val PREF_LAST_SHOWN_SOURCE_URI = "lastShownSourceUri"
    private const val PREF_LAST_SHOWN_DISPLAY_ID = "lastShownDisplayId"
    private const val PREF_LAST_SHOWN_SCALE_MODE = "lastShownScaleMode"
    private const val PREF_LAST_SHOWN_ROTATION_DEGREES = "lastShownRotationDegrees"

    private val mainHandler = Handler(Looper.getMainLooper())
    private var currentPresentation: SecondScreenMediaPresentation? = null

    fun getDisplays(context: Context): List<Map<String, Any?>> {
        val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
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

    fun getSavedConfig(context: Context): Map<String, Any?> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val isShowing = prefs.getBoolean(PREF_IS_SHOWING, false)

        val selectedMediaUri = prefs.getString("mediaUri", prefs.getString("imageUri", null))
        val selectedMediaName = prefs.getString("mediaName", prefs.getString("imageName", null))
        val selectedMediaType = prefs.getString("mediaType", "image")
        val selectedSourceUri = prefs.getString("sourceUri", selectedMediaUri)
        val selectedDisplayId =
            if (prefs.contains("displayId")) prefs.getInt("displayId", -1) else null
        val selectedScaleMode = prefs.getString("scaleMode", "centerCrop")
        val selectedRotationDegrees = prefs.getInt("rotationDegrees", 0)

        val shownMediaUri = prefs.getString(PREF_LAST_SHOWN_MEDIA_URI, null)
        val shownMediaName = prefs.getString(PREF_LAST_SHOWN_MEDIA_NAME, null)
        val shownMediaType = prefs.getString(PREF_LAST_SHOWN_MEDIA_TYPE, null)
        val shownSourceUri = prefs.getString(PREF_LAST_SHOWN_SOURCE_URI, shownMediaUri)
        val shownDisplayId =
            if (prefs.contains(PREF_LAST_SHOWN_DISPLAY_ID)) {
                prefs.getInt(PREF_LAST_SHOWN_DISPLAY_ID, -1)
            } else {
                null
            }
        val shownScaleMode = prefs.getString(PREF_LAST_SHOWN_SCALE_MODE, "centerCrop")
        val shownRotationDegrees = prefs.getInt(PREF_LAST_SHOWN_ROTATION_DEGREES, 0)

        val effectiveMediaUri =
            if (isShowing) shownSourceUri ?: shownMediaUri ?: selectedSourceUri ?: selectedMediaUri
            else selectedMediaUri
        val effectiveMediaName =
            if (isShowing) shownMediaName ?: selectedMediaName else selectedMediaName
        val effectiveMediaType =
            if (isShowing) shownMediaType ?: selectedMediaType else selectedMediaType
        val effectiveSourceUri =
            if (isShowing) shownSourceUri ?: selectedSourceUri else selectedSourceUri
        val effectiveDisplayId =
            if (isShowing) shownDisplayId ?: selectedDisplayId else selectedDisplayId
        val effectiveScaleMode =
            if (isShowing) shownScaleMode ?: selectedScaleMode else selectedScaleMode
        val effectiveRotationDegrees =
            if (isShowing) shownRotationDegrees else selectedRotationDegrees

        return mapOf(
            "imageUri" to effectiveMediaUri,
            "imageName" to effectiveMediaName,
            "mediaUri" to effectiveMediaUri,
            "mediaName" to effectiveMediaName,
            "mediaType" to effectiveMediaType,
            "mediaMimeType" to prefs.getString("mediaMimeType", ""),
            "sourceUri" to effectiveSourceUri,
            "displayId" to effectiveDisplayId,
            "scaleMode" to effectiveScaleMode,
            "rotationDegrees" to effectiveRotationDegrees,
            "isShowing" to isShowing
        )
    }

    fun resolveDisplayId(context: Context, preferredId: Int?): Int? {
        val secondaryDisplays = getSecondaryDisplays(context)
        if (secondaryDisplays.isEmpty()) {
            return null
        }

        if (preferredId != null && secondaryDisplays.any { it.displayId == preferredId }) {
            return preferredId
        }

        return secondaryDisplays.first().displayId
    }

    fun showMedia(
        context: Context,
        request: DisplayMediaRequest,
        onEvent: (Map<String, Any?>) -> Unit = {},
    ): Map<String, Any?> {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return showMediaOnMain(context, request, onEvent)
        }

        val latch = CountDownLatch(1)
        var response: Map<String, Any?>? = null
        var thrown: Throwable? = null
        mainHandler.post {
            try {
                response = showMediaOnMain(context, request, onEvent)
            } catch (error: Throwable) {
                thrown = error
            } finally {
                latch.countDown()
            }
        }
        latch.await()
        thrown?.let { throw it }
        return response ?: throw SecondScreenDisplayException("SHOW_FAILED", "副屏显示失败")
    }

    fun hideMedia(context: Context): Boolean {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return hideMediaOnMain(context)
        }

        val latch = CountDownLatch(1)
        var response = false
        mainHandler.post {
            try {
                response = hideMediaOnMain(context)
            } finally {
                latch.countDown()
            }
        }
        latch.await()
        return response
    }

    private fun showMediaOnMain(
        context: Context,
        request: DisplayMediaRequest,
        onEvent: (Map<String, Any?>) -> Unit,
    ): Map<String, Any?> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val mediaUriString = request.mediaUri
            ?: prefs.getString("mediaUri", prefs.getString("imageUri", null))
        val mediaName = request.mediaName
            ?: prefs.getString("mediaName", prefs.getString("imageName", null))
        val sourceUriString = request.sourceUri ?: mediaUriString
        val scaleMode = (request.scaleMode ?: prefs.getString("scaleMode", "centerCrop")).toImageScaleMode()
        val rotationDegrees = request.rotationDegrees
        val mediaType = (
            request.mediaType
                ?: prefs.getString("mediaType", null)
                ?: contentTypeForUri(context, mediaUriString).toMediaType()
            ).toMediaType()
        val displayId = resolveDisplayId(context, request.displayId)

        if (displayId == null) {
            throw SecondScreenDisplayException("NO_DISPLAY", "请选择副屏")
        }

        if (mediaUriString.isNullOrBlank()) {
            throw SecondScreenDisplayException("NO_MEDIA", "请选择要显示的图片或视频")
        }

        val display = displayManager.displays.firstOrNull { it.displayId == displayId }
        if (display == null) {
            throw SecondScreenDisplayException("DISPLAY_NOT_FOUND", "找不到 Display $displayId")
        }

        val presentation = SecondScreenMediaPresentation(
            context,
            display,
            Uri.parse(mediaUriString),
            mediaType,
            scaleMode,
            rotationDegrees,
            displayId,
            onEvent
        )
        presentation.setOnDismissListener {
            if (currentPresentation === presentation) {
                currentPresentation = null
                prefs.edit()
                    .putBoolean(PREF_IS_SHOWING, false)
                    .apply()
                onEvent(mapOf("type" to "presentationDismissed", "displayId" to displayId))
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
                .putString("mediaName", mediaName)
                .putString("mediaType", mediaType)
                .putString("sourceUri", sourceUriString)
                .putString("imageUri", mediaUriString)
                .putString("imageName", mediaName)
                .putInt("displayId", displayId)
                .putString("scaleMode", scaleMode)
                .putInt("rotationDegrees", rotationDegrees)
                .putBoolean(PREF_IS_SHOWING, true)
                .putString(PREF_LAST_SHOWN_MEDIA_URI, mediaUriString)
                .putString(PREF_LAST_SHOWN_MEDIA_NAME, mediaName)
                .putString(PREF_LAST_SHOWN_MEDIA_TYPE, mediaType)
                .putString(PREF_LAST_SHOWN_SOURCE_URI, sourceUriString)
                .putInt(PREF_LAST_SHOWN_DISPLAY_ID, displayId)
                .putString(PREF_LAST_SHOWN_SCALE_MODE, scaleMode)
                .putInt(PREF_LAST_SHOWN_ROTATION_DEGREES, rotationDegrees)
                .apply()

            return mapOf(
                "displayId" to displayId,
                "imageUri" to mediaUriString,
                "mediaUri" to mediaUriString,
                "mediaType" to mediaType,
                "mediaName" to mediaName,
                "sourceUri" to sourceUriString,
                "scaleMode" to scaleMode,
                "rotationDegrees" to rotationDegrees
            )
        } catch (error: WindowManager.InvalidDisplayException) {
            Log.e(TAG, "Invalid display for presentation", error)
            throw SecondScreenDisplayException(
                "INVALID_DISPLAY",
                "目标屏幕不可用",
                buildErrorDetails(error, display, mediaUriString, mediaType, scaleMode, rotationDegrees)
            )
        } catch (error: SecurityException) {
            Log.e(TAG, "Media permission error", error)
            throw SecondScreenDisplayException(
                "IMAGE_PERMISSION",
                "没有媒体读取权限，请重新选择文件",
                buildErrorDetails(error, display, mediaUriString, mediaType, scaleMode, rotationDegrees)
            )
        } catch (error: Exception) {
            Log.e(TAG, "Failed to show presentation", error)
            throw SecondScreenDisplayException(
                "SHOW_FAILED",
                "副屏显示失败",
                buildErrorDetails(error, display, mediaUriString, mediaType, scaleMode, rotationDegrees)
            )
        }
    }

    private fun hideMediaOnMain(context: Context): Boolean {
        val presentation = currentPresentation
        currentPresentation = null
        presentation?.dismiss()
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(PREF_IS_SHOWING, false)
            .apply()
        return true
    }

    private fun getSecondaryDisplays(context: Context): List<Display> {
        val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        return displayManager.displays
            .filter { display -> display.displayId != Display.DEFAULT_DISPLAY }
    }

    private fun contentTypeForUri(context: Context, mediaUriString: String?): String? {
        if (mediaUriString.isNullOrBlank()) {
            return null
        }
        return try {
            context.contentResolver.getType(Uri.parse(mediaUriString))
        } catch (_: RuntimeException) {
            null
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

private class SecondScreenMediaPresentation(
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
                    SecondScreenVideoView(
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
                    SecondScreenImageView(context, mediaUri, scaleMode, rotationDegrees),
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

private class SecondScreenVideoView(
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
                this@SecondScreenVideoView.videoWidth = videoW
                this@SecondScreenVideoView.videoHeight = videoH
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
                this@SecondScreenVideoView.videoWidth = it.videoWidth
                this@SecondScreenVideoView.videoHeight = it.videoHeight
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

private class SecondScreenImageView(
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
        val halfWidth = options.outWidth / 2
        val halfHeight = options.outHeight / 2

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
