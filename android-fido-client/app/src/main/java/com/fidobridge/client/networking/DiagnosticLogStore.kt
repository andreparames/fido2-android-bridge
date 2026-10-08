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

    /** Deletes all stored diagnostics. Returns true when nothing remains on disk. */
    fun clear(): Boolean
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
        val line = encodeWithinLimit(entry)
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
    override fun clear(): Boolean {
        val currentGone = !currentFile.exists() || currentFile.delete()
        val rotatedGone = !rotatedFile.exists() || rotatedFile.delete()
        return currentGone && rotatedGone
    }

    @Synchronized
    override fun sizeBytes(): Long =
        (if (currentFile.isFile) currentFile.length() else 0L) +
            (if (rotatedFile.isFile) rotatedFile.length() else 0L)

    private fun rotate() {
        rotatedFile.delete()
        currentFile.renameTo(rotatedFile)
    }

    /**
     * Encodes one entry so it can never exceed [maxBytes] on its own (the
     * rotation bound would otherwise be violated by a single oversized line).
     * Oversized messages are truncated to the longest prefix that fits, found by
     * binary search; the result is always valid JSON Lines.
     */
    private fun encodeWithinLimit(entry: DiagnosticLogEntry): ByteArray {
        if (lineBytes(entry).size <= maxBytes) return lineBytes(entry)
        var low = 0
        var high = entry.message.length
        while (low < high) {
            val mid = (low + high + 1) / 2
            if (lineBytes(entry.copy(message = entry.message.substring(0, mid))).size <= maxBytes) {
                low = mid
            } else {
                high = mid - 1
            }
        }
        return lineBytes(entry.copy(message = entry.message.substring(0, low)))
    }

    private fun lineBytes(entry: DiagnosticLogEntry): ByteArray =
        (DiagnosticLogEntry.encode(entry) + "\n").toByteArray(Charsets.UTF_8)

    /** Reads only the lines that still decode, so a truncated tail never breaks readAll. */
    private fun retainedLines(file: File): List<String> =
        file.readLines(Charsets.UTF_8).filter { DiagnosticLogEntry.decode(it) != null }

    companion object {
        const val CURRENT_NAME = "diagnostics.jsonl"
        const val ROTATED_NAME = "diagnostics.1.jsonl"
        const val DEFAULT_MAX_BYTES = 1L shl 20 // 1 MiB
    }
}
