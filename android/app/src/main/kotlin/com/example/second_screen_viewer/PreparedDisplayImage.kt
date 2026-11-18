package com.example.second_screen_viewer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.hardware.display.DisplayManager
import android.net.Uri
import android.util.DisplayMetrics
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean

/** Worker owns this resource until the candidate window adopts it; release is idempotent. */
internal class PreparedDisplayImage(
    val bitmap: Bitmap,
    val imageFile: File?,
    private val releaseFile: () -> Unit,
) {
    private val released = AtomicBoolean(false)
    @Volatile
    private var adopted: Boolean = false

    fun adopt() {
        check(!released.get())
        adopted = true
    }

    fun releaseIfUnadopted() {
        if (!adopted) release()
    }

    fun release() {
        if (released.compareAndSet(false, true)) {
            bitmap.recycle()
            releaseFile()
        }
    }
}

internal object DisplayImageDecoder {
    fun decode(context: Context, request: DisplayMediaRequest, strictDisplay: Boolean): Bitmap = decodeWithAllocationGuard {
        val displayId = if (strictDisplay) request.displayId else
            SecondScreenDisplayController.resolveDisplayId(context, request.displayId)
        val manager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val display = manager.displays.firstOrNull { it.displayId == displayId }
            ?: throw SecondScreenDisplayException("DISPLAY_NOT_FOUND", "Target display is unavailable")
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        display.getRealMetrics(metrics)
        require(metrics.widthPixels > 0 && metrics.heightPixels > 0) { "Invalid target dimensions" }
        val uri = Uri.parse(request.mediaUri ?: throw IOException("Missing image URI"))
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open(context, uri).use { BitmapFactory.decodeStream(it, null, bounds) }
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Invalid image bounds" }
        val normalizedRotation = ((request.rotationDegrees % 360) + 360) % 360
        val rotated = normalizedRotation in 45 until 135 || normalizedRotation in 225 until 315
        val targetWidth = if (rotated) metrics.heightPixels else metrics.widthPixels
        val targetHeight = if (rotated) metrics.widthPixels else metrics.heightPixels
        // Bound both dimensions, including extreme aspect ratios. Never fully decode just to validate.
        var sampleSize = 1
        while (bounds.outWidth / sampleSize > targetWidth * 2L ||
            bounds.outHeight / sampleSize > targetHeight * 2L) {
            sampleSize *= 2
        }
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inSampleSize = sampleSize
        }
        open(context, uri).use { BitmapFactory.decodeStream(it, null, options) }
            ?: throw IOException("Image pixel decode failed")
    }

    fun open(context: Context, uri: Uri): InputStream {
        return if (uri.scheme == "file") {
            FileInputStream(uri.path ?: throw IOException("Missing local image path"))
        } else {
            context.contentResolver.openInputStream(uri) ?: throw IOException("Cannot open image")
        }
    }
}
