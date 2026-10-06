package com.fidobridge.client.networking

import android.content.ContentResolver
import android.net.Uri

/**
 * Exports the local diagnostic logs as a zip to a user-chosen [Uri] obtained
 * through the Storage Access Framework (SD card, Google Drive, ...).
 */
class DiagnosticsExporter(
    private val store: DiagnosticLogStore,
    private val resolver: ContentResolver
) {

    /** Returns true when the zip was written; false on a null stream or IO error. */
    fun exportTo(uri: Uri): Boolean =
        try {
            val out = resolver.openOutputStream(uri, "wt") ?: return false
            out.use { store.exportZipTo(it) }
            true
        } catch (e: Exception) {
            false
        }
}
