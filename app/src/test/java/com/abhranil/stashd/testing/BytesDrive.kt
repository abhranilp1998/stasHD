package com.abhranil.stashd.testing

import com.abhranil.stashd.data.drive.DriveDownload
import com.abhranil.stashd.data.drive.DriveError
import com.abhranil.stashd.data.drive.DriveItem
import com.abhranil.stashd.data.drive.DriveLink
import com.abhranil.stashd.data.drive.DrivePage
import com.abhranil.stashd.data.drive.DriveRepository
import com.abhranil.stashd.data.drive.ShortcutTarget
import com.abhranil.stashd.download.core.FileDownloader.Companion.toHex
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer
import java.io.IOException
import java.security.MessageDigest

/** In-memory Drive with real bytes: folders, files, Docs, shortcuts, scripted download failures. */
class BytesDrive : DriveRepository {
    val children = HashMap<String, MutableList<DriveItem>>()
    val contents = HashMap<String, ByteArray>()
    val items = HashMap<String, DriveItem>()
    /** Failures thrown by the next openDownload calls for a file, in order. */
    val openFailures = HashMap<String, ArrayDeque<Exception>>()
    /** Throws an IOException ("connection reset") once the stream has delivered this many bytes (one time). */
    val cutAfter = HashMap<String, Long>()
    val opens = mutableListOf<Pair<String, Long>>()
    var listFailures = ArrayDeque<Exception>()

    fun folder(parent: String?, id: String, name: String = id): DriveItem {
        val f = DriveItem(id, name, DriveItem.MIME_FOLDER, null, null, null, 1_000, true)
        items[id] = f; children.getOrPut(id) { mutableListOf() }
        parent?.let { children.getOrPut(it) { mutableListOf() } += f }
        return f
    }

    fun file(parent: String, id: String, name: String, bytes: ByteArray, md5: String? = md5(bytes)): DriveItem {
        val f = DriveItem(id, name, "audio/flac", bytes.size.toLong(), md5, null, 1_000, true)
        items[id] = f; contents[id] = bytes
        children.getOrPut(parent) { mutableListOf() } += f
        return f
    }

    fun doc(parent: String, id: String, name: String) {
        val d = DriveItem(id, name, "application/vnd.google-apps.document", null, null, null, 1_000, true)
        items[id] = d; children.getOrPut(parent) { mutableListOf() } += d
    }

    fun shortcut(parent: String, id: String, name: String, target: DriveItem) {
        val s = DriveItem(id, name, DriveItem.MIME_SHORTCUT, null, null, null, 1_000, true, shortcut = ShortcutTarget(target.id, target.mimeType, null))
        items[id] = s; children.getOrPut(parent) { mutableListOf() } += s
    }

    override suspend fun resolve(link: DriveLink) = items[link.id] ?: throw DriveError.NotFound(link.id)

    override suspend fun listPage(folderId: String, resourceKey: String?, pageToken: String?): DrivePage {
        listFailures.removeFirstOrNull()?.let { throw it }
        return DrivePage(children[folderId] ?: throw DriveError.NotFound(folderId), null)
    }

    override suspend fun fileMetadata(fileId: String, resourceKey: String?) = items[fileId] ?: throw DriveError.NotFound(fileId)

    override suspend fun openDownload(fileId: String, resourceKey: String?, fromByte: Long): DriveDownload {
        opens += fileId to fromByte
        openFailures[fileId]?.removeFirstOrNull()?.let { throw it }
        val bytes = contents[fileId] ?: throw DriveError.NotFound(fileId)
        if (fromByte > bytes.size) throw DriveError.RangeNotSatisfiable(bytes.size.toLong())
        val body = Buffer().write(bytes, fromByte.toInt(), bytes.size - fromByte.toInt())
        val cut = cutAfter.remove(fileId)
        val source: BufferedSource = if (cut == null) body else object : ForwardingSource(body) {
            var sent = fromByte
            override fun read(sink: Buffer, byteCount: Long): Long {
                if (sent >= cut) throw IOException("connection reset")
                val n = super.read(sink, minOf(byteCount, cut - sent))
                if (n > 0) sent += n
                return n
            }
        }.buffer()
        return object : DriveDownload {
            override val offset = fromByte
            override val totalSize = bytes.size.toLong()
            override val source = source
            override fun close() = source.close()
        }
    }

    companion object {
        fun md5(b: ByteArray) = MessageDigest.getInstance("MD5").digest(b).toHex()
        fun bytes(n: Int, seed: Int = 1) = ByteArray(n) { ((it * 31 + seed) and 0xFF).toByte() }
    }
}
