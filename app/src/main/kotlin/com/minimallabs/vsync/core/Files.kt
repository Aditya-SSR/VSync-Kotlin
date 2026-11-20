package com.minimallabs.vsync.core

import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Saving and opening files VTOP hands back (passes, course material, assignments). */
object Files {
    /** VTOP often lies about the type, so the format is read from the first bytes. */
    fun detectExtension(bytes: ByteArray): String {
        if (bytes.size < 4) return "bin"
        fun starts(vararg sig: Int) = sig.indices.all { bytes[it] == sig[it].toByte() }
        fun ascii(): String = String(bytes, 0, minOf(bytes.size, 20480), Charsets.ISO_8859_1)
        if (starts(0x25, 0x50, 0x44, 0x46)) return "pdf"
        if (starts(0x50, 0x4B, 0x03, 0x04)) {
            val c = ascii()
            return when {
                c.contains("word/") -> "docx"
                c.contains("ppt/") -> "pptx"
                c.contains("xl/") -> "xlsx"
                else -> "zip"
            }
        }
        if (starts(0xD0, 0xCF, 0x11, 0xE0)) {
            val c = ascii()
            return when {
                c.contains("PowerPoint") || c.contains("P\u0000o\u0000w\u0000e\u0000r\u0000P\u0000o\u0000i\u0000n\u0000t") -> "ppt"
                c.contains("Microsoft Excel") || c.contains("Workbook") -> "xls"
                else -> "doc"
            }
        }
        if (starts(0x89, 0x50, 0x4E, 0x47)) return "png"
        if (starts(0xFF, 0xD8, 0xFF)) return "jpg"
        if (starts(0x47, 0x49, 0x46, 0x38)) return "gif"
        if (bytes.size >= 8 && bytes[4] == 0x66.toByte() && bytes[5] == 0x74.toByte() && bytes[6] == 0x79.toByte() && bytes[7] == 0x70.toByte()) return "mp4"
        val sample = bytes.take(512)
        if (sample.all { b -> val v = b.toInt() and 0xFF; v in 0x20..0x7E || v == 0x0A || v == 0x0D || v == 0x09 }) return "txt"
        return "bin"
    }

    fun mimeFor(extension: String): String = when (extension.lowercase()) {
        "pdf" -> "application/pdf"
        "doc" -> "application/msword"
        "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        "xls" -> "application/vnd.ms-excel"
        "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        "ppt" -> "application/vnd.ms-powerpoint"
        "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
        "zip" -> "application/zip"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "mp4" -> "video/mp4"
        "txt" -> "text/plain"
        else -> "application/octet-stream"
    }

    fun sanitize(name: String) = name.replace(Regex("[\\\\/:*?\"<>|\\s]+"), "_").trim('_').take(80).ifEmpty { "file" }

    class Saved(val uri: Uri, val fileName: String, val mime: String)

    /**
     * Saves into Downloads/VSync and posts the "Ready" notification.
     * [baseName] has no extension unless [extension] is null and it already carries one.
     */
    suspend fun saveToDownloads(bytes: ByteArray, baseName: String, extension: String? = null, label: String = "File"): Saved =
        withContext(Dispatchers.IO) {
            if (bytes.isEmpty()) throw UserMessage("VTOP sent an empty file. Please try again.")
            val ext = extension ?: detectExtension(bytes)
            val fileName = if (baseName.endsWith(".$ext", ignoreCase = true)) baseName else "$baseName.$ext"
            val mime = mimeFor(ext)
            val context = App.context
            val uri: Uri = if (Build.VERSION.SDK_INT >= 29) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(MediaStore.Downloads.MIME_TYPE, mime)
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/VSync")
                }
                val target = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: throw UserMessage("Could not save the file.")
                context.contentResolver.openOutputStream(target)?.use { it.write(bytes) } ?: throw UserMessage("Could not save the file.")
                target
            } else {
                val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir, "VSync").apply { mkdirs() }
                val file = File(dir, fileName)
                file.writeBytes(bytes)
                FileProvider.getUriForFile(context, context.packageName + ".files", file)
            }
            Notifications.downloadComplete(label, fileName, uri, mime)
            Saved(uri, fileName, mime)
        }

    fun open(saved: Saved) {
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(saved.uri, saved.mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { App.context.startActivity(intent) }.onFailure { App.toast("Saved to Downloads/VSync as ${saved.fileName}") }
    }

    /** Download, save, tell the student, open. Errors come back as a toast. */
    suspend fun fetchSaveOpen(baseName: String, label: String, extension: String? = null, fetch: suspend () -> ByteArray) {
        try {
            val saved = saveToDownloads(fetch(), sanitize(baseName), extension, label)
            App.toast("Saved to Downloads/VSync: ${saved.fileName}")
            open(saved)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            App.toast(Failures.messageFor(e, "Download failed. Please try again."))
        }
    }
}
