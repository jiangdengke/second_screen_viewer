package com.example.second_screen_viewer

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.Properties
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LastGoodImageStoreTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun record(image: File) = CachedImageRecord(
        image.name, 3, "fitCenter", 90, "example slide", "https://example.invalid/image.png",
    )

    private fun store(directory: File, manifest: RecordFile<CachedImageRecord>) =
        LastGoodImageStore(directory, manifest) { _, _ -> }

    private fun candidate(store: LastGoodImageStore, bytes: String): File =
        store.createCandidate { bytes.byteInputStream() }

    @Test fun failedCopyAndUnshownCandidateKeepPreviousBytesAndManifest() {
        val directory = temporary.newFolder()
        val manifest = MemoryRecordFile<CachedImageRecord>()
        val store = store(directory, manifest)
        val previous = candidate(store, "previous")
        store.publish(previous, record(previous), 1)
        assertThrows(IOException::class.java) {
            store.createCandidate { object : InputStream() { override fun read(): Int = throw IOException("copy failed") } }
        }
        val rejected = candidate(store, "invalid pixels or failed window")
        store.release(rejected)
        assertEquals(record(previous), manifest.read())
        assertEquals("previous", previous.readText())
        assertFalse(rejected.exists())
        assertEquals(1, directory.listFiles()!!.size)
    }

    @Test fun publicationFailureKeepsOldDurableImageAndNewLiveWindowUntilRelease() {
        val directory = temporary.newFolder()
        val manifest = MemoryRecordFile<CachedImageRecord>()
        val store = store(directory, manifest)
        val previous = candidate(store, "previous")
        store.publish(previous, record(previous), 1)
        val replacement = candidate(store, "replacement")
        manifest.failWrite = true
        assertThrows(IOException::class.java) { store.publish(replacement, record(replacement), 2) }
        assertTrue(replacement.exists())
        assertEquals(record(previous), manifest.read())
        assertEquals("previous", previous.readText())
        store.release(replacement)
        assertFalse(replacement.exists())
        assertTrue(previous.exists())
    }

    @Test fun successfulReplacementPublishesMatchingSettingsAndRetainsPinnedOldWindow() {
        val directory = temporary.newFolder()
        val manifest = MemoryRecordFile<CachedImageRecord>()
        val store = store(directory, manifest)
        val previous = candidate(store, "previous")
        store.publish(previous, record(previous), 1)
        val replacement = candidate(store, "replacement")
        val settings = record(replacement).copy(displayId = 4, rotationDegrees = 270, scaleMode = "fitXY")
        store.publish(replacement, settings, 2)
        assertTrue(previous.exists())
        store.release(previous)
        assertFalse(previous.exists())
        val (restored, image) = store.acquireCurrent()!!
        assertEquals(settings, restored)
        assertEquals("replacement", image.readText())
        store.release(image)
    }

    @Test fun interruptedCandidateIsCleanedOnNextInitializationWithoutRemovingReferencedImage() {
        val directory = temporary.newFolder()
        val manifestFile = File(directory, "manifest.properties")
        val manifest = diskManifest(manifestFile)
        val initial = store(directory, manifest)
        val previous = candidate(initial, "previous")
        initial.publish(previous, record(previous), 1)
        val orphan = candidate(initial, "crash before manifest")
        val recreated = store(directory, diskManifest(manifestFile))
        assertEquals("previous", recreated.acquireCurrent()!!.second.readText())
        assertFalse(orphan.exists())
        assertTrue(previous.exists())
    }

    @Test fun cleanupPreservesOtherInflightCandidatesAndRejectsOlderPublication() {
        val directory = temporary.newFolder()
        val manifest = MemoryRecordFile<CachedImageRecord>()
        val store = store(directory, manifest)
        val slowEarlier = candidate(store, "older display")
        val latest = candidate(store, "latest display")
        store.publish(latest, record(latest), 2)
        assertTrue(slowEarlier.exists())
        assertFalse(store.publish(slowEarlier, record(slowEarlier), 1))
        store.release(slowEarlier)
        assertFalse(slowEarlier.exists())
        assertEquals(record(latest), manifest.read())
    }

    @Test fun unconfirmedManifestWriteDoesNotDeletePreviousBytes() {
        val directory = temporary.newFolder()
        val backing = MemoryRecordFile<CachedImageRecord>()
        val store = store(directory, backing)
        val previous = candidate(store, "previous")
        store.publish(previous, record(previous), 1)
        val ignoredWriter = object : RecordFile<CachedImageRecord> {
            override fun read() = backing.read()
            override fun write(record: CachedImageRecord) = Unit
        }
        val unconfirmed = store(directory, ignoredWriter)
        val replacement = candidate(unconfirmed, "replacement")
        assertThrows(IOException::class.java) { unconfirmed.publish(replacement, record(replacement), 2) }
        assertTrue(previous.exists())
        assertEquals(record(previous), backing.read())
    }

    @Test fun corruptManifestSkipsCleanupAndNeverFollowsAnExternalPath() {
        val directory = temporary.newFolder()
        val properties = LastGoodImageStore.encode(CachedImageRecord("image-safe.img", 3, "centerCrop", 0, "", ""))
        properties.setProperty("imageName", "../outside.img")
        assertThrows(IllegalArgumentException::class.java) { LastGoodImageStore.decode(properties) }
        val unreadable = MemoryRecordFile<CachedImageRecord>().apply { failRead = true }
        val owned = File(directory, "image-safe.img").apply { writeText("keep") }
        assertThrows(IOException::class.java) { store(directory, unreadable).acquireCurrent() }
        assertTrue(owned.exists())
    }

    @Test fun missingImageDoesNotEraseItsManifestAndCodecRoundTripsSettings() {
        val directory = temporary.newFolder()
        val manifest = MemoryRecordFile<CachedImageRecord>()
        val metadata = record(File(directory, "image-missing.img"))
        manifest.record = metadata
        assertEquals(metadata, LastGoodImageStore.decode(LastGoodImageStore.encode(metadata)))
        assertThrows(IllegalArgumentException::class.java) { store(directory, manifest).acquireCurrent() }
        assertEquals(metadata, manifest.record)
    }

    private fun diskManifest(file: File) = object : RecordFile<CachedImageRecord> {
        override fun read(): CachedImageRecord? = if (!file.exists()) null else file.inputStream().use { input ->
            LastGoodImageStore.decode(Properties().apply { load(input) })
        }
        override fun write(record: CachedImageRecord) {
            val pending = File(file.path + ".pending")
            pending.outputStream().use { LastGoodImageStore.encode(record).store(it, null) }
            check(pending.renameTo(file))
        }
    }
}
