package com.example.second_screen_viewer

import android.util.AtomicFile
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.util.Properties

internal class AtomicRecordFile<Record>(
    private val file: File,
    private val encode: (Record) -> Properties,
    private val decode: (Properties) -> Record,
) : RecordFile<Record> {
    private val atomicFile = AtomicFile(file)

    override fun read(): Record? {
        val input = try {
            atomicFile.openRead()
        } catch (error: FileNotFoundException) {
            if (file.exists() || File(file.path + ".bak").exists()) throw error
            return null
        }
        return input.use { stream ->
            decode(Properties().apply { load(stream) })
        }
    }

    override fun write(record: Record) {
        val directory = file.parentFile ?: throw IOException("Missing record directory")
        if (!directory.isDirectory && !directory.mkdirs()) {
            throw IOException("Cannot create record directory: $directory")
        }
        var output: FileOutputStream? = null
        try {
            output = atomicFile.startWrite()
            encode(record).store(output, null)
            output.fd.sync()
            atomicFile.finishWrite(output)
            output = null
            // AtomicFile.finishWrite has no success result. Confirm before deleting old bytes.
            if (read() != record) throw IOException("Atomic record publication was not confirmed")
        } catch (error: Exception) {
            output?.let { atomicFile.failWrite(it) }
            throw error
        }
    }
}
