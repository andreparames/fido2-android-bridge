package com.fidobridge.client.networking

import java.io.File
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Local, on-device persistence for diagnostics (TODO: store Android logs
 * locally besides the relay). Append-only JSON Lines with size-based rotation.
 */
interface DiagnosticLogStore {
    fun append(entry: DiagnosticLogEntry)
    fun readAll(): String
    fun exportZipTo(output: OutputStream)
    fun clear()
    fun sizeBytes(): Long
}

/**
 * [DiagnosticLogStore] backed by `diagnostics.jsonl` in [dir]. At [maxBytes] the
 * current file rotates to `diagnostics.1.jsonl` (the previous generation is
 * overwritten), so the store is bounded at roughly `2 * maxBytes`.
 *
 * All operations are serialized, so appends from the main thread and the relay
 * coroutine can interleave safely.
 */
class FileDiagnosticLogStore(
    private val dir: File,
    private val maxBytes: Long = DEFAULT_MAX_BYTES
) : DiagnosticLogStore {

    private val currentFile: File get() = File(dir, CURRENT_NAME)
    private val rotatedFile: File get() = File(dir, ROTATED_NAME)

    @Synchronized
    override fun append(entry: DiagnosticLogEntry) {
        dir.mkdirs()
        val line = DiagnosticLogEntry.encode(entry).toByteArray(Charsets.UTF_8) + NEWLINE
        if (currentFile.length() > 0 && currentFile.length() + line.size > maxBytes) {
            rotate()
        }
        currentFile.appendBytes(line)
    }

    @Synchronized
    override fun readAll(): String {
        val retained = buildList {
            if (rotatedFile.isFile) addAll(retainedLines(rotatedFile))
            if (currentFile.isFile) addAll(retainedLines(currentFile))
        }
        return retained.joinToString(separator = "\n", postfix = if (retained.isEmpty()) "" else "\n")
    }

    @Synchronized
    override fun exportZipTo(output: OutputStream) {
        val entries = listOf(ROTATED_NAME to rotatedFile, CURRENT_NAME to currentFile)
            .filter { (_, file) -> file.isFile && file.length() > 0 }
        val zip = ZipOutputStream(output)
        entries.forEach { (name, file) ->
            zip.putNextEntry(ZipEntry(name))
            file.inputStream().use { it.copyTo(zip) }
            zip.closeEntry()
        }
        zip.finish()
        zip.flush()
    }

    @Synchronized
    override fun clear() {
        currentFile.delete()
        rotatedFile.delete()
    }

    @Synchronized
    override fun sizeBytes(): Long =
        (if (currentFile.isFile) currentFile.length() else 0L) +
            (if (rotatedFile.isFile) rotatedFile.length() else 0L)

    private fun rotate() {
        rotatedFile.delete()
        currentFile.renameTo(rotatedFile)
    }

    /** Reads only the lines that still decode, so a truncated tail never breaks readAll. */
    private fun retainedLines(file: File): List<String> =
        file.readLines(Charsets.UTF_8).filter { DiagnosticLogEntry.decode(it) != null }

    companion object {
        const val CURRENT_NAME = "diagnostics.jsonl"
        const val ROTATED_NAME = "diagnostics.1.jsonl"
        const val DEFAULT_MAX_BYTES = 1L shl 20 // 1 MiB

        private val NEWLINE = byteArrayOf('\n'.code.toByte())
    }
}
