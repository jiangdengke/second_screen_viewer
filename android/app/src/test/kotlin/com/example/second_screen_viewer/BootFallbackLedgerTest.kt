package com.example.second_screen_viewer

import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

internal class MemoryRecordFile<Record> : RecordFile<Record> {
    var record: Record? = null
    var failWrite = false
    var failRead = false
    override fun read(): Record? {
        if (failRead) throw IOException("Injected record read failure")
        return record
    }
    override fun write(record: Record) {
        if (failWrite) throw IOException("Injected publication failure")
        this.record = record
    }
}

class BootFallbackLedgerTest {
    private val records = MemoryRecordFile<BootFallbackRecord>()
    private val ledger = BootFallbackLedger(records)

    @Test fun ordinaryOrPackageStartDoesNotArm() {
        assertNull(ledger.resume(7, null, 300_000, false))
        assertNull(records.record)
    }

    @Test fun genuineBootDeadlineIsNotResetByDuplicatesOrProcessRecreation() {
        val armed = ledger.resume(7, 40_000, 41_000, false)!!
        assertEquals(160_000, armed.deadlineElapsed)
        assertEquals(armed, ledger.resume(7, 90_000, 90_000, false))
        assertEquals(armed, BootFallbackLedger(records).resume(7, null, 150_000, false))
        assertNull(ledger.consume(7, 159_999))
        assertEquals(BootFallbackPhase.CONSUMED, ledger.consume(7, 160_000)?.phase)
    }

    @Test fun consumedRecordNeverRetriesEvenWhenTheImageLoadFailedOrProcessDied() {
        ledger.resume(7, 0, 0, false)
        ledger.consume(7, 120_000)
        val recreated = BootFallbackLedger(records)
        assertEquals(BootFallbackPhase.CONSUMED, recreated.resume(7, null, 200_000, false)?.phase)
        assertNull(recreated.consume(7, 200_000))
        assertEquals(BootFallbackPhase.CONSUMED, recreated.resume(7, 200_000, 200_000, false)?.phase)
    }

    @Test fun earlyCancellationIsDurableWithoutAnExistingArmedRecord() {
        ledger.cancel(7)
        assertEquals(BootFallbackPhase.CANCELLED, BootFallbackLedger(records).resume(7, 10, 20, false)?.phase)
        assertNull(ledger.consume(7, 500_000))
    }

    @Test fun initializationMergesCancellationInsteadOfArming() {
        assertEquals(BootFallbackPhase.CANCELLED, ledger.resume(7, 10, 20, true)?.phase)
        assertNull(ledger.consume(7, 500_000))
    }

    @Test fun newBootOnlyArmsWithItsOwnGenuineEvent() {
        ledger.cancel(7)
        assertNull(ledger.resume(8, null, 600_000, false))
        val nextBoot = ledger.resume(8, 600_000, 600_000, false)!!
        assertEquals(720_000, nextBoot.deadlineElapsed)
        assertEquals(BootFallbackPhase.ARMED, nextBoot.phase)
        assertNull(ledger.consume(7, 900_000))
    }

    @Test fun missingBootIdentityNeverUsesASharedSentinel() {
        assertNull(ledger.resume(null, 0, 0, false))
        assertNull(ledger.cancel(null))
        assertNull(ledger.consume(null, 120_000))
        assertNull(records.record)
    }

    @Test fun failedConsumptionWriteDoesNotAuthorizeReadingAnImage() {
        ledger.resume(7, 0, 0, false)
        records.failWrite = true
        assertThrows(IOException::class.java) { ledger.consume(7, 120_000) }
        assertEquals(BootFallbackPhase.ARMED, records.record?.phase)
    }

    @Test fun cancellationCanReplaceACorruptLedgerWithoutReadingIt() {
        records.failRead = true
        assertEquals(BootFallbackPhase.CANCELLED, ledger.cancel(7)?.phase)
        assertEquals(BootFallbackPhase.CANCELLED, ledger.resume(7, 0, 0, true)?.phase)
    }

    @Test fun codecRejectsCorruptOrUnknownState() {
        val record = BootFallbackRecord(7, 123_000, BootFallbackPhase.ARMED)
        assertEquals(record, BootFallbackLedger.decode(BootFallbackLedger.encode(record)))
        val properties = BootFallbackLedger.encode(record)
        properties.setProperty("phase", "INVALID")
        assertThrows(IllegalArgumentException::class.java) { BootFallbackLedger.decode(properties) }
    }
}
