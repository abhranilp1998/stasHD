package com.abhranil.stashd.data.drive

import java.net.URI
import java.net.URLDecoder

/**
 * Parses the Drive link shapes users paste:
 *  drive.google.com/drive/folders/{id}, /drive/u/N/folders/{id}, /open?id={id}, /file/d/{id}/view,
 *  each optionally with ?resourcekey=... (needed for older link-shared items).
 */
object LinkParser {
    private val idChars = Regex("^[A-Za-z0-9_-]{10,}$")

    fun parse(raw: String): DriveLink? {
        val text = raw.trim().let { if (it.startsWith("http")) it else "https://$it" }
        val uri = runCatching { URI(text) }.getOrNull() ?: return null
        val host = uri.host?.lowercase() ?: return null
        if (host != "drive.google.com" && host != "docs.google.com") return null

        val query = uri.rawQuery.orEmpty().split('&').mapNotNull {
            val i = it.indexOf('=')
            if (i <= 0) null else it.substring(0, i) to it.substring(i + 1)
        }.toMap()
        val resourceKey = query["resourcekey"]?.let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrDefault(it) }
            ?.trim()?.takeIf { it.isNotBlank() }
        val segments = uri.path.orEmpty().split('/').filter { it.isNotEmpty() }

        val folderIdx = segments.indexOf("folders")
        if (folderIdx >= 0 && folderIdx + 1 < segments.size) {
            return segments[folderIdx + 1].takeIf { idChars.matches(it) }?.let { DriveLink(it, resourceKey, isFolder = true) }
        }
        val fileIdx = segments.indexOf("d")
        if (segments.firstOrNull() == "file" && fileIdx >= 0 && fileIdx + 1 < segments.size) {
            return segments[fileIdx + 1].takeIf { idChars.matches(it) }?.let { DriveLink(it, resourceKey, isFolder = false) }
        }
        query["id"]?.takeIf { idChars.matches(it) }?.let {
            // open?id= can be either; the validation call (files.get) decides.
            return DriveLink(it, resourceKey, isFolder = true)
        }
        return null
    }
}
