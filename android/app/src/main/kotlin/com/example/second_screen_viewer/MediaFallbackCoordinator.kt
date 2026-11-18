package com.example.second_screen_viewer

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import java.io.File
import java.io.IOException
import java.util.concurrent.Future
import java.util.concurrent.Executors

/** Process lifetime worker: service recreation must not strand durable writes or active image pins. */
internal object MediaFallbackCoordinator {
    private const val TAG = "SecondScreenViewer"
    val gate = MediaCommandGate()
    private val diskWorker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "second-screen-image-storage")
    }
    private val mainHandler = Handler(Looper.getMainLooper())
    private var ledger: BootFallbackLedger? = null
    private var imageStore: LastGoodImageStore? = null
    private var displaySequence = 0L

    private fun ledger(context: Context): BootFallbackLedger {
        return ledger ?: BootFallbackLedger(
            AtomicRecordFile(
                File(context.filesDir, "boot_image_fallback.properties"),
                BootFallbackLedger::encode, BootFallbackLedger::decode,
            )
        ).also { ledger = it }
    }

    private fun store(context: Context): LastGoodImageStore {
        return imageStore ?: run {
            val directory = File(context.filesDir, "last_good_image")
            LastGoodImageStore(
                directory,
                AtomicRecordFile(
                    File(directory, "manifest.properties"),
                    LastGoodImageStore::encode, LastGoodImageStore::decode,
                ),
                ::logFailure,
            ).also { imageStore = it }
        }
    }

    private fun bootCount(context: Context): Int? {
        return try {
            Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT).also {
                require(it >= 0) { "Invalid BOOT_COUNT" }
            }
        } catch (error: Exception) {
            logFailure("BOOT_COUNT unavailable; skip automatic image restore", error)
            null
        }
    }

    fun resume(
        context: Context, lease: Long, bootEventElapsed: Long?,
        onArmed: (BootFallbackRecord) -> Unit,
    ) {
        val application = context.applicationContext
        diskWorker.execute {
            try {
                if (!gate.isServiceActive(lease)) return@execute
                val record = ledger(application).resume(
                    bootCount(application), bootEventElapsed, SystemClock.elapsedRealtime(),
                    gate.isRestoreRevoked(),
                )
                if (record?.phase == BootFallbackPhase.ARMED) {
                    mainHandler.post {
                        if (gate.restoreToken(lease) != null) onArmed(record)
                    }
                }
            } catch (error: Exception) {
                logFailure("Boot fallback initialization skipped", error)
            }
        }
    }

    fun admit(context: Context, lease: Long? = null): Pair<MediaWorkToken, Future<*>> {
        val token = gate.admitCommand(lease)
        val application = context.applicationContext
        val durableWrite = diskWorker.submit {
            try {
                ledger(application).cancel(bootCount(application))
            } catch (error: Exception) {
                // Memory cancellation stays authoritative even when storage cannot promise restart safety.
                logFailure("Cannot persist media cancellation; restart safety unavailable", error)
            }
        }
        return token to durableWrite
    }

    fun admitLocal(context: Context, continuation: (MediaWorkToken) -> Unit) {
        val (token, _) = admit(context)
        // FIFO barrier follows the terminal write. Main never waits for storage.
        diskWorker.execute { mainHandler.post { continuation(token) } }
    }

    fun prepareImage(context: Context, request: DisplayMediaRequest, token: MediaWorkToken): PreparedDisplayImage {
        val application = context.applicationContext
        return diskWorker.submit<PreparedDisplayImage> {
            if (!gate.isCurrent(token)) throw ObsoleteMediaWorkException()
            val candidate = try {
                store(application).createCandidate {
                    DisplayImageDecoder.open(application, Uri.parse(request.mediaUri))
                }
            } catch (error: Exception) {
                // A cache failure must not turn an otherwise readable image command into a failed display.
                logFailure("Cannot copy durable image; displaying without publishing fallback", error)
                null
            }
            try {
                if (!gate.isCurrent(token)) throw ObsoleteMediaWorkException()
                val decodeRequest = if (candidate == null) request else
                    request.copy(mediaUri = Uri.fromFile(candidate).toString())
                val bitmap = DisplayImageDecoder.decode(application, decodeRequest, strictDisplay = false)
                PreparedDisplayImage(bitmap, candidate) {
                    if (candidate != null) releaseFile(application, candidate)
                }
            } catch (error: Throwable) {
                candidate?.let { store(application).release(it) }
                throw error
            }
        }.awaitMediaWork()
    }

    /** Called inside the final main gate, so publication is queued in successful attachment order. */
    fun imageAttached(context: Context, prepared: PreparedDisplayImage, config: Map<String, Any?>) {
        val candidate = prepared.imageFile ?: return
        displaySequence += 1
        val sequence = displaySequence
        val record = CachedImageRecord(
            candidate.name, (config["displayId"] as Number).toInt(),
            config["scaleMode"] as String, (config["rotationDegrees"] as Number).toInt(),
            config["mediaName"]?.toString() ?: "", config["sourceUri"]?.toString() ?: "",
        )
        val application = context.applicationContext
        diskWorker.execute {
            try {
                store(application).publish(candidate, record, sequence)
            } catch (error: Exception) {
                logFailure("Image displayed, but durable last-good publication failed", error)
            }
        }
    }

    fun restore(context: Context, lease: Long, onEvent: (Map<String, Any?>) -> Unit) {
        val token = gate.restoreToken(lease) ?: return
        val application = context.applicationContext
        diskWorker.execute {
            var imageFile: File? = null
            var pendingMainImage: PreparedDisplayImage? = null
            try {
                if (!gate.isCurrent(token)) return@execute
                if (ledger(application).consume(bootCount(application), SystemClock.elapsedRealtime()) == null) {
                    return@execute
                }
                val (record, image) = store(application).acquireCurrent() ?: run {
                    Log.i(TAG, "Boot image restore skipped: no last-good image")
                    return@execute
                }
                imageFile = image
                val request = DisplayMediaRequest(
                    record.displayId, Uri.fromFile(image).toString(), record.mediaName,
                    record.sourceUri, "image", record.scaleMode, record.rotationDegrees,
                )
                val bitmap = DisplayImageDecoder.decode(application, request, strictDisplay = true)
                val prepared = PreparedDisplayImage(bitmap, image) { releaseFile(application, image) }
                pendingMainImage = prepared
                imageFile = null
                val posted = mainHandler.post {
                    try {
                        SecondScreenDisplayController.showPreparedImage(
                            application, request, prepared, token, strictDisplay = true,
                            publishLastGood = false, onEvent = onEvent,
                        )
                        Log.i(TAG, "Boot image fallback displayed from local storage")
                    } catch (error: Exception) {
                        logFailure("Boot image restore skipped", error)
                    } finally {
                        prepared.releaseIfUnadopted()
                    }
                }
                if (!posted) throw IOException("Display thread is unavailable")
                pendingMainImage = null
            } catch (error: Throwable) {
                Log.w(TAG, "Boot image restore skipped", error)
            } finally {
                imageFile?.let { store(application).release(it) }
                pendingMainImage?.releaseIfUnadopted()
            }
        }
    }

    private fun releaseFile(context: Context, image: File) {
        diskWorker.execute { store(context).release(image) }
    }

    private fun logFailure(message: String, error: Exception) {
        Log.w(TAG, message, error)
    }
}
