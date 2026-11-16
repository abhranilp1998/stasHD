package com.abhranil.stashd.download.storage

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.system.Os
import androidx.documentfile.provider.DocumentFile
import com.abhranil.stashd.data.storage.NameSanitizer
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Storage Access Framework destination (the folder picked with OpenDocumentTree, persisted permission).
 * Directory listings are cached per directory so collision checks don't re-query the provider per file.
 */
class SafDestination(private val context: Context, private val treeUri: Uri) : Destination {
    private val resolver get() = context.contentResolver
    private val dirs = HashMap<List<String>, Dir>()

    override fun checkAccess() {
        val granted = resolver.persistedUriPermissions.any { it.uri == treeUri && it.isWritePermission }
        val root = runCatching { DocumentFile.fromTreeUri(context, treeUri) }.getOrNull()
        if (!granted || root == null || !root.exists() || !root.canWrite()) {
            throw StorageError.AccessRevoked(if (!granted) "permission revoked" else "folder missing")
        }
    }

    override fun availableBytes(): Long? = runCatching {
        val doc = DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))
        resolver.openFileDescriptor(doc, "r")?.use { pfd ->
            val st = Os.fstatvfs(pfd.fileDescriptor)
            st.f_bavail * st.f_frsize
        }
    }.getOrNull()

    override fun dir(segments: List<String>): DestDir = guard {
        dirs.getOrPut(segments) {
            var cur = DocumentFile.fromTreeUri(context, treeUri) ?: throw StorageError.AccessRevoked("folder missing")
            for (raw in segments) {
                val name = NameSanitizer.sanitize(raw)
                cur = cur.findFile(name)?.takeIf { it.isDirectory }
                    ?: cur.createDirectory(name)
                    ?: throw StorageError.AccessRevoked("can't create folder $name")
            }
            Dir(cur)
        }
    }

    private inner class Dir(private val doc: DocumentFile) : DestDir {
        private var cache: MutableMap<String, DocumentFile>? = null
        private fun entries(): MutableMap<String, DocumentFile> =
            cache ?: doc.listFiles().filter { it.name != null }.associateByTo(HashMap()) { it.name!! }.also { cache = it }

        override fun list(): Map<String, Long> = guard { entries().mapValues { it.value.length() } }
        override fun size(name: String): Long? = guard { entries()[name]?.length() }
        override fun uri(name: String): String? = guard { entries()[name]?.uri?.toString() }

        override fun openWrite(name: String, append: Boolean): OutputStream = guard {
            val f = entries()[name] ?: (doc.createFile("application/octet-stream", name)
                ?: throw StorageError.AccessRevoked("can't create $name")).also { created ->
                // Some providers adjust the display name; track what we actually got.
                entries()[created.name ?: name] = created
                if (created.name != name) throw IOException("provider renamed $name to ${created.name}")
            }
            resolver.openOutputStream(f.uri, if (append) "wa" else "wt") ?: throw StorageError.AccessRevoked("can't open $name")
        }

        override fun openRead(name: String): InputStream = guard {
            val f = entries()[name] ?: throw FileNotFoundException(name)
            resolver.openInputStream(f.uri) ?: throw FileNotFoundException(name)
        }

        override fun delete(name: String) {
            guard { entries().remove(name)?.delete() }
        }

        override fun rename(from: String, to: String): String = guard {
            val f = entries()[from] ?: throw FileNotFoundException(from)
            check(to !in entries()) { "$to exists" }
            val newUri = DocumentsContract.renameDocument(resolver, f.uri, to) ?: throw IOException("rename failed")
            val renamed = DocumentFile.fromSingleUri(context, newUri)
            if (renamed?.name != to) throw IOException("provider renamed to ${renamed?.name}")
            entries().remove(from)
            cache = null // re-list lazily; the renamed doc's tree-uri form differs per provider
            newUri.toString()
        }
    }

    /** SecurityException = grant revoked; ENOSPC = storage full. Everything else stays an IOException. */
    private inline fun <T> guard(block: () -> T): T = try {
        block()
    } catch (e: SecurityException) {
        throw StorageError.AccessRevoked("permission revoked", e)
    } catch (e: IllegalArgumentException) {
        throw StorageError.AccessRevoked("folder no longer exists", e)
    } catch (e: IOException) {
        if (e !is StorageError && e.isNoSpace()) throw StorageError.InsufficientSpace(0, availableBytes(), e) else throw e
    }
}
