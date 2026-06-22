package com.example.second_screen_viewer

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {
    private companion object {
        const val CHANNEL_NAME = "second_screen_viewer/display"
        const val REQUEST_PICK_IMAGE = 9101
        const val PREFS_NAME = "second_screen_viewer"
    }

    private lateinit var channel: MethodChannel
    private var pendingPickResult: MethodChannel.Result? = null
    private var pendingPickMediaType: String? = null

    private val prefs by lazy {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        ControlHttpService.start(this)

        channel = MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL_NAME)
        channel.setMethodCallHandler { call, result ->
            when (call.method) {
                "getDisplays" -> result.success(SecondScreenDisplayController.getDisplays(applicationContext))
                "getSavedConfig" -> result.success(SecondScreenDisplayController.getSavedConfig(applicationContext))
                "getControlServiceStatus" -> result.success(ControlHttpService.serviceStatus(applicationContext))
                "startControlService" -> {
                    ControlHttpService.start(this)
                    result.success(ControlHttpService.serviceStatus(applicationContext))
                }
                "pickImage" -> pickMedia(call, result)
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
        val requestedMediaType = pendingPickMediaType
        pendingPickResult = null
        pendingPickMediaType = null

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
        val mediaType = mimeType.toMediaType(requestedMediaType)

        prefs.edit()
            .putString("mediaUri", uri.toString())
            .putString("mediaName", name)
            .putString("mediaMimeType", mimeType)
            .putString("mediaType", mediaType)
            .putString("sourceUri", uri.toString())
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
        pendingPickResult?.error("ACTIVITY_DESTROYED", "主界面已关闭", null)
        pendingPickResult = null
        pendingPickMediaType = null
        super.onDestroy()
    }

    private fun pickMedia(call: MethodCall, result: MethodChannel.Result) {
        if (pendingPickResult != null) {
            result.error("PICK_IN_PROGRESS", "已经有一个媒体选择任务在进行", null)
            return
        }

        val requestedMediaType = (call.arguments as? Map<*, *>)?.get("mediaType") as? String
        val normalizedMediaType = when (requestedMediaType) {
            "video" -> "video"
            "image" -> "image"
            else -> null
        }
        val mimeTypeFilter = when (normalizedMediaType) {
            "video" -> "video/*"
            "image" -> "image/*"
            else -> "*/*"
        }
        val chooserTitle = when (normalizedMediaType) {
            "video" -> "选择副屏视频"
            "image" -> "选择副屏图片"
            else -> "选择副屏媒体"
        }

        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = mimeTypeFilter
            if (mimeTypeFilter == "*/*") {
                putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/*", "video/*"))
            }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }

        pendingPickResult = result
        pendingPickMediaType = normalizedMediaType
        try {
            startActivityForResult(Intent.createChooser(intent, chooserTitle), REQUEST_PICK_IMAGE)
        } catch (error: ActivityNotFoundException) {
            pendingPickResult = null
            pendingPickMediaType = null
            result.error("NO_FILE_PICKER", "系统没有可用的文件选择器", error.message)
        }
    }

    private fun showMedia(call: MethodCall, result: MethodChannel.Result) {
        val args = call.arguments as? Map<*, *>
        val displayId = args?.get("displayId").asInt()
        val mediaUriString = args?.get("imageUri") as? String
            ?: args?.get("mediaUri") as? String
            ?: prefs.getString("mediaUri", prefs.getString("imageUri", null))
        val mediaName = args?.get("mediaName") as? String
            ?: prefs.getString("mediaName", prefs.getString("imageName", null))
        val sourceUriString = args?.get("sourceUri") as? String ?: mediaUriString
        val scaleMode = args?.get("scaleMode") as? String
            ?: prefs.getString("scaleMode", "centerCrop")
            ?: "centerCrop"
        val rotationDegrees = args?.get("rotationDegrees").asInt()
            ?: prefs.getInt("rotationDegrees", 0)
        val mediaType = args?.get("mediaType") as? String
            ?: prefs.getString("mediaType", null)

        try {
            val response = SecondScreenDisplayController.showMedia(
                this,
                DisplayMediaRequest(
                    displayId = displayId,
                    mediaUri = mediaUriString,
                    mediaName = mediaName,
                    sourceUri = sourceUriString,
                    mediaType = mediaType,
                    scaleMode = scaleMode,
                    rotationDegrees = rotationDegrees,
                ),
                ::sendPresentationEvent
            )
            result.success(response)
        } catch (error: SecondScreenDisplayException) {
            result.error(error.code, error.message, error.details)
        } catch (error: Exception) {
            result.error("SHOW_FAILED", "副屏显示失败", error.message)
        }
    }

    private fun hideImage(result: MethodChannel.Result) {
        SecondScreenDisplayController.hideMedia(applicationContext)
        result.success(true)
    }

    private fun sendPresentationEvent(event: Map<String, Any?>) {
        if (::channel.isInitialized) {
            runOnUiThread {
                if (event["type"] == "presentationDismissed") {
                    channel.invokeMethod("presentationDismissed", null)
                } else {
                    channel.invokeMethod("presentationEvent", event)
                }
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
}

private fun String?.toMediaType(fallback: String?): String {
    return when {
        this?.startsWith("video/") == true -> "video"
        this?.startsWith("image/") == true -> "image"
        fallback == "video" -> "video"
        this == "video" -> "video"
        else -> "image"
    }
}
