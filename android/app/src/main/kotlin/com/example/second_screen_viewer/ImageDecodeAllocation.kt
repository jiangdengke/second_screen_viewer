package com.example.second_screen_viewer

import java.io.IOException

/** Only decoder allocation failures become preparation errors; other fatal errors propagate. */
internal inline fun <DecodedImage> decodeWithAllocationGuard(decodeImage: () -> DecodedImage): DecodedImage {
    return try {
        decodeImage()
    } catch (error: OutOfMemoryError) {
        throw IOException("Insufficient memory for image decode", error)
    }
}
