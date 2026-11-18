package com.example.second_screen_viewer

import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class MediaCommandGateTest {
    @Test fun cancellationAfterIoAndBeforeMainRejectsRestore() {
        val gate = MediaCommandGate()
        val lease = gate.attachService()
        val token = gate.restoreToken(lease)!!
        val mainQueue = ArrayDeque<() -> Unit>()
        var shown = false
        var published = false
        mainQueue.add {
            assertThrows(ObsoleteMediaWorkException::class.java) {
                gate.withCurrent(token) { shown = true; published = true }
            }
        }
        gate.admitCommand()
        mainQueue.removeFirst().invoke()
        assertFalse(shown)
        assertFalse(published)
        assertNull(gate.restoreToken(lease))
    }

    @Test fun allRecognizedRoutesCancelBeforeAFailingBodyContinuation() {
        listOf("/robot_task/screen_control_Img_display?trace=1", "/robot_task/screen_control",
            "/api/hide", "/robot_task/screen_stop").forEach { target ->
            val gate = MediaCommandGate()
            val lease = gate.attachService()
            val restore = gate.restoreToken(lease)!!
            val records = MemoryRecordFile<BootFallbackRecord>()
            val ledger = BootFallbackLedger(records)
            if (MediaCommandRoutes.isMediaCommand("POST", MediaCommandRoutes.normalizePath(target))) {
                gate.admitCommand(lease)
                ledger.cancel(8)
            }
            assertThrows(IllegalArgumentException::class.java) { throw IllegalArgumentException("Malformed body") }
            assertFalse(gate.isCurrent(restore))
            assertEquals(BootFallbackPhase.CANCELLED, BootFallbackLedger(records).resume(8, 10, 20, false)?.phase)
        }
    }

    @Test fun pollingOptionsAndUnknownRoutesDoNotCancel() {
        listOf("GET" to "/api/status", "GET" to "/api/displays",
            "OPTIONS" to "/api/hide", "GET" to "/robot_task/screen_control",
            "POST" to "/unrecognized").forEach { (method, path) ->
            assertFalse(MediaCommandRoutes.isMediaCommand(method, path))
        }
    }

    @Test fun cancelledWindowDismissalCannotAdmitAnotherCancellation() {
        val gate = MediaCommandGate()
        val lease = gate.attachService()
        val windowToken = gate.admitCommand(lease)
        val mainQueue = ArrayDeque<() -> Unit>()
        mainQueue.add {
            gate.runIfCurrent(windowToken) { gate.admitCommand() }
        }

        val newerRequest = gate.admitCommand(lease)
        mainQueue.removeFirst().invoke()

        assertTrue(gate.isCurrent(newerRequest))
    }

    @Test fun delayedDismissalNotificationCannotAffectANewerSlideshow() {
        val gate = MediaCommandGate()
        val lease = gate.attachService()
        val dismissalToken = gate.admitCommand()
        val mainQueue = ArrayDeque<() -> Unit>()
        var dismissedEvents = 0
        var slideshowScheduled = false
        mainQueue.add {
            gate.runIfCurrent(dismissalToken) {
                dismissedEvents += 1
                slideshowScheduled = false
            }
        }

        val newerRequest = gate.admitCommand(lease)
        gate.withCurrent(newerRequest) { slideshowScheduled = true }
        mainQueue.removeFirst().invoke()

        assertEquals(0, dismissedEvents)
        assertTrue(slideshowScheduled)
        gate.runIfCurrent(newerRequest) { dismissedEvents += 1 }
        assertEquals(1, dismissedEvents)
    }

    @Test fun staleLeaseAndOlderLocalCommandsCannotDisplayOrRegisterTicks() {
        val gate = MediaCommandGate()
        var cancellations = 0
        val firstLease = gate.attachService { cancellations += 1 }
        val oldRequest = gate.admitCommand(firstLease)
        val localCommand = gate.admitCommand()
        assertFalse(gate.isCurrent(oldRequest))
        assertThrows(ObsoleteMediaWorkException::class.java) { gate.withCurrent(oldRequest) { error("late tick") } }
        assertTrue(gate.isCurrent(localCommand))
        gate.detachService(firstLease)
        val nextLease = gate.attachService()
        assertFalse(gate.isCurrent(oldRequest))
        assertTrue(gate.isServiceActive(nextLease))
        assertNull(gate.restoreToken(nextLease))
        assertEquals(2, cancellations)
    }

    @Test fun admissionAndFinalAttachmentHaveOneOrderingPointWithoutWorkerMainDeadlock() {
        val gate = MediaCommandGate()
        val token = gate.restoreToken(gate.attachService())!!
        val attached = CountDownLatch(1)
        val allowCommit = CountDownLatch(1)
        val admissionStarted = CountDownLatch(1)
        val events = mutableListOf<String>()
        val main = Thread {
            gate.withCurrent(token) {
                attached.countDown()
                check(allowCommit.await(3, TimeUnit.SECONDS))
                events.add("display")
            }
        }
        val ingress = Thread {
            admissionStarted.countDown()
            gate.admitCommand()
            events.add("command")
        }
        main.start()
        assertTrue(attached.await(3, TimeUnit.SECONDS))
        ingress.start()
        assertTrue(admissionStarted.await(3, TimeUnit.SECONDS))
        allowCommit.countDown()
        main.join(3000)
        ingress.join(3000)
        assertFalse(main.isAlive)
        assertFalse(ingress.isAlive)
        assertEquals(listOf("display", "command"), events)
        assertFalse(gate.isCurrent(token))
    }

    @Test fun interruptedWaitDoesNotAbandonAResourceProducingJob() {
        val future = FutureTask { "prepared-resource" }
        Thread.currentThread().interrupt()
        Thread(future).start()
        try {
            assertEquals("prepared-resource", future.awaitMediaWork())
            assertTrue(Thread.currentThread().isInterrupted)
        } finally {
            Thread.interrupted()
        }
    }
}
