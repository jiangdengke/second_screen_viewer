package com.example.second_screen_viewer

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.Properties

internal data class CachedImageRecord(
    val imageName: String,
    val displayId: Int,
    val scaleMode: String,
    val rotationDegrees: Int,
    val mediaName: String,
    val sourceUri: String,
)

/** Serial-worker confined. Live files are pinned until their prepared/window owner releases them. */
internal class LastGoodImageStore(
    private val directory: File,
    private val manifest: RecordFile<CachedImageRecord>,
    private val logFailure: (String, Exception) -> Unit,
) {
    companion object {
        fun encode(record: CachedImageRecord): Properties = Properties().apply {
            setProperty("version", "1")
            setProperty("imageName", record.imageName)
            setProperty("displayId", record.displayId.toString())
            setProperty("scaleMode", record.scaleMode)
            setProperty("rotationDegrees", record.rotationDegrees.toString())
            setProperty("mediaName", record.mediaName)
            setProperty("sourceUri", record.sourceUri)
        }

        fun decode(properties: Properties): CachedImageRecord {
            require(properties.getProperty("version") == "1") { "Unknown image manifest version" }
            val imageName = properties.getProperty("imageName") ?: error("Missing image name")
            require(isOwnedName(imageName)) { "Unsafe image filename" }
            val displayId = properties.getProperty("displayId").toInt()
            val scaleMode = properties.getProperty("scaleMode")
            val rotationDegrees = properties.getProperty("rotationDegrees").toInt()
            require(displayId >= 0 && scaleMode in setOf("centerCrop", "fitCenter", "fitXY", "centerInside"))
            require(rotationDegrees in setOf(0, 90, 180, 270))
            return CachedImageRecord(
                imageName, displayId, scaleMode, rotationDegrees,
                properties.getProperty("mediaName") ?: error("Missing media name"),
                properties.getProperty("sourceUri") ?: error("Missing source URI"),
            )
        }

        private fun isOwnedName(name: String): Boolean =
            name.matches(Regex("image-[A-Za-z0-9-]+[.]img"))
    }

    private val liveFiles = mutableMapOf<String, Int>()
    private var initialized = false
    private var lastPublishedSequence = 0L

    fun createCandidate(openSource: () -> InputStream): File {
        initialize()
        if (!directory.isDirectory && !directory.mkdirs()) {
            throw IOException("Cannot create image directory")
        }
        val candidate = File.createTempFile("image-", ".img", directory)
        pin(candidate)
        try {
            openSource().use { input ->
                FileOutputStream(candidate).use { output ->
                    input.copyTo(output)
                    output.fd.sync()
                }
            }
            require(candidate.length() > 0) { "Empty image candidate" }
            return candidate
        } catch (error: Exception) {
            release(candidate)
            throw error
        }
    }

    fun acquireCurrent(): Pair<CachedImageRecord, File>? {
        initialize()
        val record = manifest.read() ?: return null
        require(isOwnedName(record.imageName)) { "Unsafe image filename" }
        val image = File(directory, record.imageName)
        require(image.isFile && image.length() > 0) { "Cached image is missing" }
        pin(image)
        return record to image
    }

    fun publish(candidate: File, record: CachedImageRecord, displaySequence: Long): Boolean {
        if (displaySequence <= lastPublishedSequence) return false
        require(candidate.parentFile == directory && candidate.name == record.imageName)
        require(candidate.isFile && candidate.length() > 0)
        manifest.write(record)
        // A fake or platform publisher must prove the complete old-or-new pointer before cleanup.
        if (manifest.read() != record) throw IOException("Image manifest publication was not confirmed")
        lastPublishedSequence = displaySequence
        cleanup()
        return true
    }

    fun release(image: File) {
        val count = liveFiles[image.name] ?: return
        if (count == 1) liveFiles.remove(image.name) else liveFiles[image.name] = count - 1
        cleanup()
    }

    private fun pin(image: File) {
        liveFiles[image.name] = (liveFiles[image.name] ?: 0) + 1
    }

    private fun initialize() {
        if (initialized) return
        // Recover AtomicFile before looking for crash orphans, never delete from an unreadable pointer.
        cleanup()
        initialized = true
    }

    private fun cleanup() {
        try {
            val referencedName = manifest.read()?.imageName
            directory.listFiles()?.forEach { image ->
                if (isOwnedName(image.name) && image.name != referencedName && image.name !in liveFiles) {
                    if (!image.delete()) logFailure("Cannot remove orphan image", IOException(image.name))
                }
            }
        } catch (error: Exception) {
            logFailure("Image cleanup skipped; manifest must remain recoverable", error)
        }
    }
}
