package com.example.second_screen_viewer

import java.util.Properties

internal interface RecordFile<Record> {
    fun read(): Record?
    fun write(record: Record)
}

internal enum class BootFallbackPhase { ARMED, CANCELLED, CONSUMED }

internal data class BootFallbackRecord(
    val bootCount: Int,
    val deadlineElapsed: Long,
    val phase: BootFallbackPhase,
)

/** Called only by the serial storage worker; elapsed times never cross boot identities. */
internal class BootFallbackLedger(private val records: RecordFile<BootFallbackRecord>) {
    companion object {
        const val GRACE_PERIOD_MS = 120_000L

        fun encode(record: BootFallbackRecord): Properties = Properties().apply {
            setProperty("version", "1")
            setProperty("bootCount", record.bootCount.toString())
            setProperty("deadlineElapsed", record.deadlineElapsed.toString())
            setProperty("phase", record.phase.name)
        }

        fun decode(properties: Properties): BootFallbackRecord {
            require(properties.getProperty("version") == "1") { "Unknown boot ledger version" }
            val bootCount = properties.getProperty("bootCount").toInt()
            val deadline = properties.getProperty("deadlineElapsed").toLong()
            require(bootCount >= 0 && deadline >= 0) { "Invalid boot ledger" }
            return BootFallbackRecord(
                bootCount,
                deadline,
                BootFallbackPhase.valueOf(properties.getProperty("phase")),
            )
        }
    }

    fun resume(
        bootCount: Int?,
        bootEventElapsed: Long?,
        nowElapsed: Long,
        cancelledBeforeInitialization: Boolean,
    ): BootFallbackRecord? {
        if (bootCount == null) return null
        if (cancelledBeforeInitialization) {
            return cancel(bootCount)
        }
        val previous = records.read()?.takeIf { it.bootCount == bootCount }
        if (previous != null) return previous
        if (bootEventElapsed == null || bootEventElapsed !in 0..nowElapsed) return null

        val armed = BootFallbackRecord(
            bootCount,
            bootEventElapsed + GRACE_PERIOD_MS,
            BootFallbackPhase.ARMED,
        )
        records.write(armed)
        return armed
    }

    fun cancel(bootCount: Int?): BootFallbackRecord? {
        if (bootCount == null) return null
        val cancelled = BootFallbackRecord(
            bootCount,
            0L,
            BootFallbackPhase.CANCELLED,
        )
        records.write(cancelled)
        return cancelled
    }

    fun consume(bootCount: Int?, nowElapsed: Long): BootFallbackRecord? {
        if (bootCount == null) return null
        val armed = records.read()?.takeIf {
            it.bootCount == bootCount && it.phase == BootFallbackPhase.ARMED &&
                nowElapsed >= it.deadlineElapsed
        } ?: return null
        val consumed = armed.copy(phase = BootFallbackPhase.CONSUMED)
        records.write(consumed)
        return consumed
    }
}
