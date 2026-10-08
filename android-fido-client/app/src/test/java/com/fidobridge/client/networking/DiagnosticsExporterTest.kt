package com.fidobridge.client.networking

import android.content.ContentResolver
import android.net.Uri
import io.mockk.every
import io.mockk.mockk
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsExporterTest {

    private class WritingStore(private val bytes: ByteArray = "zip-bytes".toByteArray()) : DiagnosticLogStore {
        override fun append(entry: DiagnosticLogEntry) = Unit
        override fun readAll(): String = ""
        override fun exportZipTo(output: OutputStream) {
            output.write(bytes)
        }
        override fun clear(): Boolean = true
        override fun sizeBytes(): Long = bytes.size.toLong()
    }

    private val uri = mockk<Uri>()

    @Test
    fun `exportTo writes the store to the resolver stream`() {
        val sink = ByteArrayOutputStream()
        val resolver = mockk<ContentResolver>()
        every { resolver.openOutputStream(uri, "wt") } returns sink
        val exporter = DiagnosticsExporter(WritingStore(), resolver)

        assertTrue(exporter.exportTo(uri))

        assertArrayEquals("zip-bytes".toByteArray(), sink.toByteArray())
    }

    @Test
    fun `exportTo returns false when the resolver yields no stream`() {
        val resolver = mockk<ContentResolver>()
        every { resolver.openOutputStream(uri, "wt") } returns null
        val exporter = DiagnosticsExporter(WritingStore(), resolver)

        assertFalse(exporter.exportTo(uri))
    }

    @Test
    fun `exportTo returns false when writing throws`() {
        val resolver = mockk<ContentResolver>()
        every { resolver.openOutputStream(uri, "wt") } throws IOException("disk full")
        val exporter = DiagnosticsExporter(WritingStore(), resolver)

        assertFalse(exporter.exportTo(uri))
    }
}
