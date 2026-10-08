package com.fidobridge.client.networking

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FileDiagnosticLogStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun store(maxBytes: Long = FileDiagnosticLogStore.DEFAULT_MAX_BYTES) =
        FileDiagnosticLogStore(tmp.root, maxBytes)

    private fun entry(message: String) = DiagnosticLogEntry(1_700_000_000_000, message)

    @Test
    fun `append persists an entry readable from readAll`() {
        val store = store()
        store.append(entry("first"))

        val lines = store.readAll().trim().lines()
        assertEquals(1, lines.size)
        assertEquals("first", DiagnosticLogEntry.decode(lines[0])?.message)
        assertEquals(entry("first").ts, DiagnosticLogEntry.decode(lines[0])?.ts)
    }

    @Test
    fun `rotation keeps the previous generation and readAll stays ordered oldest first`() {
        val store = store(maxBytes = 80)
        store.append(entry("one"))
        store.append(entry("two"))
        store.append(entry("three"))

        assertTrue(File(tmp.root, FileDiagnosticLogStore.ROTATED_NAME).isFile)
        val messages = store.readAll().trim().lines().mapNotNull { DiagnosticLogEntry.decode(it)?.message }
        assertEquals(listOf("one", "two", "three"), messages)
    }

    @Test
    fun `clear removes every file and reports success`() {
        val store = store(maxBytes = 80)
        repeat(3) { store.append(entry("m$it")) }

        assertTrue(store.clear())

        assertFalse(File(tmp.root, FileDiagnosticLogStore.CURRENT_NAME).exists())
        assertFalse(File(tmp.root, FileDiagnosticLogStore.ROTATED_NAME).exists())
        assertEquals(0L, store.sizeBytes())
        assertEquals("", store.readAll())
    }

    @Test
    fun `clear on an empty store is still a success`() {
        assertTrue(store().clear())
    }

    @Test
    fun `append truncates a single entry that cannot fit one generation`() {
        val store = store(maxBytes = 80)
        val message = "x".repeat(10_000)

        store.append(entry(message))

        val line = store.readAll().trim()
        val decoded = DiagnosticLogEntry.decode(line)!!
        assertTrue(decoded.message.length < message.length)
        assertTrue(store.sizeBytes() <= 80)
    }

    @Test
    fun `readAll skips malformed lines`() {
        val current = File(tmp.root, FileDiagnosticLogStore.CURRENT_NAME)
        current.writeText("{not json}\n" + DiagnosticLogEntry.encode(entry("good")) + "\n")

        val messages = store().readAll().trim().lines().mapNotNull { DiagnosticLogEntry.decode(it)?.message }

        assertEquals(listOf("good"), messages)
    }

    @Test
    fun `sizeBytes sums both files`() {
        val store = store(maxBytes = 80)
        repeat(3) { store.append(entry("m$it")) }

        val expected = File(tmp.root, FileDiagnosticLogStore.CURRENT_NAME).length() +
            File(tmp.root, FileDiagnosticLogStore.ROTATED_NAME).length()
        assertEquals(expected, store.sizeBytes())
    }

    @Test
    fun `exportZipTo writes both generations as jsonl entries`() {
        val store = store(maxBytes = 80)
        store.append(entry("one"))
        store.append(entry("two"))
        store.append(entry("three"))

        val bos = ByteArrayOutputStream()
        store.exportZipTo(bos)

        val contents = mutableMapOf<String, String>()
        ZipInputStream(ByteArrayInputStream(bos.toByteArray())).use { zip ->
            var e = zip.nextEntry
            while (e != null) {
                contents[e.name] = zip.readBytes().toString(Charsets.UTF_8)
                zip.closeEntry()
                e = zip.nextEntry
            }
        }

        assertEquals(
            setOf(FileDiagnosticLogStore.ROTATED_NAME, FileDiagnosticLogStore.CURRENT_NAME),
            contents.keys
        )
        assertTrue(contents.getValue(FileDiagnosticLogStore.ROTATED_NAME).contains("one"))
        assertTrue(contents.getValue(FileDiagnosticLogStore.CURRENT_NAME).contains("three"))
    }

    @Test
    fun `exportZipTo omits an empty store`() {
        val bos = ByteArrayOutputStream()
        store().exportZipTo(bos)

        assertTrue(bos.size() > 0) // a valid (empty) zip, not zero bytes
        ZipInputStream(ByteArrayInputStream(bos.toByteArray())).use {
            assertEquals(null, it.nextEntry)
        }
    }
}
