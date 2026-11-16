package com.abhranil.stashd.download.storage

import com.abhranil.stashd.data.storage.NameSanitizer
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream

/** A [Destination] over a plain directory (tests; app-private storage). */
class FileTreeDestination(
    private val root: File,
    private val free: () -> Long? = { root.usableSpace },
) : Destination {
    override fun checkAccess() {
        if (!root.isDirectory || !root.canWrite()) throw StorageError.AccessRevoked(root.path)
    }

    override fun availableBytes(): Long? = free()

    override fun dir(segments: List<String>): DestDir {
        checkAccess()
        val d = segments.fold(root) { acc, s -> File(acc, NameSanitizer.sanitize(s)) }
        if (!d.isDirectory && !d.mkdirs()) throw StorageError.AccessRevoked("can't create ${d.path}")
        return Dir(d)
    }

    private class Dir(val d: File) : DestDir {
        override fun list(): Map<String, Long> = d.listFiles().orEmpty().associate { it.name to it.length() }
        override fun size(name: String): Long? = File(d, name).takeIf { it.exists() }?.length()
        override fun uri(name: String): String? = File(d, name).takeIf { it.isFile }?.path
        override fun openWrite(name: String, append: Boolean): OutputStream = FileOutputStream(File(d, name), append)
        override fun openRead(name: String): InputStream = FileInputStream(File(d, name))
        override fun delete(name: String) { File(d, name).delete() }
        override fun rename(from: String, to: String): String {
            val target = File(d, to)
            check(!target.exists()) { "$to exists" }
            if (!File(d, from).renameTo(target)) throw java.io.IOException("rename $from -> $to failed")
            return target.path
        }
    }
}
