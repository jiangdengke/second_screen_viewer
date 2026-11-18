package com.example.second_screen_viewer

import java.io.IOException
import java.util.concurrent.FutureTask
import org.junit.Assert.*
import org.junit.Test

class ImageDecodeAllocationTest {
    @Test fun successfulDecodeReturnsItsPreparedValue() {
        val decodedImage = Any()

        assertSame(decodedImage, decodeWithAllocationGuard { decodedImage })
    }

    @Test fun workerAllocationFailureBecomesIOExceptionWithOriginalCause() {
        val allocationFailure = OutOfMemoryError("Injected decoder allocation failure")
        val future = FutureTask {
            decodeWithAllocationGuard<Any> { throw allocationFailure }
        }
        future.run()

        val ordinaryFailure = assertThrows(IOException::class.java) { future.awaitMediaWork() }

        assertSame(allocationFailure, ordinaryFailure.cause)
    }

    @Test fun ordinaryDecodeFailuresAreNotReplaced() {
        listOf(IOException("Unreadable image"), IllegalArgumentException("Invalid image bounds"))
            .forEach { decodeFailure ->
                val propagated = assertThrows(Exception::class.java) {
                    decodeWithAllocationGuard<Any> { throw decodeFailure }
                }
                assertSame(decodeFailure, propagated)
            }
    }

    @Test fun otherFatalErrorsAreNotConverted() {
        listOf(StackOverflowError("Injected VM error"), ThreadDeath()).forEach { fatalError ->
            val propagated = assertThrows(Error::class.java) {
                decodeWithAllocationGuard<Any> { throw fatalError }
            }
            assertSame(fatalError, propagated)
        }
    }
}
