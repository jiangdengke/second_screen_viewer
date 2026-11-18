package com.example.second_screen_viewer

import java.net.URI
import java.util.Locale
import java.util.concurrent.ExecutionException
import java.util.concurrent.Future

internal data class MediaWorkToken(
    val generation: Long,
    val serviceLease: Long?,
    val isRestore: Boolean = false,
)

internal class ObsoleteMediaWorkException : Exception("Media command was superseded")

/** Only short admission/final-main-thread operations belong inside this gate. */
internal class MediaCommandGate {
    private var generation = 0L
    private var nextServiceLease = 0L
    private var serviceLease: Long? = null
    private var restoreRevoked = false
    private var cancelServiceWork: (() -> Unit)? = null

    @Synchronized
    fun attachService(cancelWork: () -> Unit = {}): Long {
        cancelServiceWork?.invoke()
        nextServiceLease += 1
        serviceLease = nextServiceLease
        cancelServiceWork = cancelWork
        return nextServiceLease
    }

    @Synchronized
    fun detachService(lease: Long) {
        if (serviceLease == lease) {
            serviceLease = null
            cancelServiceWork = null
        }
    }

    @Synchronized
    fun isServiceActive(lease: Long): Boolean = serviceLease == lease

    @Synchronized
    fun admitCommand(lease: Long? = null): MediaWorkToken {
        if (lease != null && serviceLease != lease) {
            throw ObsoleteMediaWorkException()
        }
        generation += 1
        restoreRevoked = true
        // Only cancels a scheduled future; never dispatches to main or accesses storage.
        cancelServiceWork?.invoke()
        return MediaWorkToken(generation, lease)
    }

    @Synchronized
    fun restoreToken(lease: Long): MediaWorkToken? {
        return if (serviceLease == lease && !restoreRevoked) {
            MediaWorkToken(generation, lease, isRestore = true)
        } else {
            null
        }
    }

    @Synchronized
    fun isRestoreRevoked(): Boolean = restoreRevoked

    @Synchronized
    fun isCurrent(token: MediaWorkToken): Boolean {
        return token.generation == generation &&
            (token.serviceLease == null || token.serviceLease == serviceLease) &&
            (!token.isRestore || !restoreRevoked)
    }

    @Synchronized
    fun <Result> withCurrent(token: MediaWorkToken, operation: () -> Result): Result {
        if (!isCurrent(token)) {
            throw ObsoleteMediaWorkException()
        }
        return operation()
    }

    @Synchronized
    fun runIfCurrent(token: MediaWorkToken, operation: () -> Unit) {
        if (isCurrent(token)) operation()
    }
}

/** Do not abandon a prepared bitmap or acknowledge a command before its durable barrier completes. */
internal fun <Result> Future<Result>.awaitMediaWork(): Result {
    var interrupted = false
    try {
        while (true) {
            try {
                return get()
            } catch (_: InterruptedException) {
                interrupted = true
            } catch (error: ExecutionException) {
                throw error.cause ?: error
            }
        }
    } finally {
        if (interrupted) Thread.currentThread().interrupt()
    }
}

internal object MediaCommandRoutes {
    fun normalizePath(target: String): String {
        return try {
            (URI(target).path ?: "").lowercase(Locale.US)
        } catch (_: Exception) {
            target.substringBefore('?').lowercase(Locale.US)
        }
    }

    fun isMediaCommand(method: String, path: String): Boolean {
        return method == "POST" && path in setOf(
            "/robot_task/screen_control_img_display",
            "/robot_task/screen_control",
            "/api/hide",
            "/robot_task/screen_stop",
        )
    }
}
