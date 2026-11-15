package com.abhranil.stashd.download.core

import com.abhranil.stashd.data.drive.DriveItem
import com.abhranil.stashd.data.drive.DriveRepository
import com.abhranil.stashd.data.storage.NameSanitizer

/** What the user picked: an item plus the folder names from the linked root down to its parent (root excluded). */
data class PickedItem(val item: DriveItem, val parentNames: List<String>)

/** One file to download, with its destination path relative to the chosen folder. */
data class PlannedFile(
    val fileId: String,
    val resourceKey: String?,
    val dirSegments: List<String>,
    val name: String,
    val expected: RemoteFingerprint,
    /** The selection this file came from (DriveItem.id of the pick). */
    val sourceKey: String,
    val viaShortcut: Boolean,
)

enum class SkipReason { GOOGLE_DOC, CANNOT_DOWNLOAD }
data class SkippedFile(val name: String, val dirSegments: List<String>, val reason: SkipReason, val sourceKey: String, val fileId: String)

data class ExpansionResult(
    val files: List<PlannedFile>,
    val skipped: List<SkippedFile>,
    /** Files dropped because the same Drive file was already covered (selected folder, shortcut, multi-parent). */
    val duplicatesRemoved: Int,
    val foldersScanned: Int,
) {
    val totalBytes: Long get() = files.sumOf { it.expected.size ?: 0L }
}

class ExpansionIncomplete(folder: String) : Exception("Drive returned an incomplete listing for \u201c$folder\u201d. Try again.")

/**
 * Expands a mixed selection (files, folders, shortcuts, the linked root) into individual file tasks:
 *  - recursive BFS with relative paths preserved (`Album/CD1/01.flac`), or flattened when [keepStructure] is off;
 *  - dedup by Drive TARGET file id: a file covered by a selected folder, reachable via a shortcut, or listed
 *    under two parents is downloaded once — the non-shortcut, shallowest occurrence wins;
 *  - shortcut subtrees are expanded only after all real folders, and each folder at most once (cycle-safe);
 *  - Google Docs/Sheets/Slides are reported as skipped (export comes later); canDownload=false likewise;
 *  - names are sanitized and de-duplicated per directory (Drive allows identical names side by side).
 * Never caches or trusts an incomplete listing: incompleteSearch aborts with [ExpansionIncomplete].
 */
class SelectionExpander(private val drive: DriveRepository) {

    private data class FolderWork(val id: String, val resourceKey: String?, val name: String, val dirs: List<String>, val sourceKey: String, val via: Boolean)
    private data class Candidate(val file: PlannedFile, val depth: Int, val order: Int)

    suspend fun expand(
        picks: List<PickedItem>,
        keepStructure: Boolean,
        onProgress: (foldersScanned: Int, filesFound: Int) -> Unit = { _, _ -> },
    ): ExpansionResult {
        val candidates = ArrayList<Candidate>()
        val skipped = ArrayList<SkippedFile>()
        val main = ArrayDeque<FolderWork>()
        val viaShortcut = ArrayDeque<FolderWork>()
        val visited = HashSet<String>()
        var scanned = 0

        suspend fun addFile(item: DriveItem, dirs: List<String>, sourceKey: String, via: Boolean) {
            val viaHere = via || item.isShortcut
            when {
                item.isGoogleDoc -> skipped += SkippedFile(item.name, dirs, SkipReason.GOOGLE_DOC, sourceKey, item.targetId)
                !item.canDownload -> skipped += SkippedFile(item.name, dirs, SkipReason.CANNOT_DOWNLOAD, sourceKey, item.targetId)
                else -> {
                    // A shortcut doesn't carry its target's size/md5: ask once.
                    val target = if (item.isShortcut) drive.fileMetadata(item.targetId, item.targetResourceKey) else item
                    if (target.isGoogleDoc) { skipped += SkippedFile(item.name, dirs, SkipReason.GOOGLE_DOC, sourceKey, item.targetId); return }
                    if (!target.canDownload) { skipped += SkippedFile(item.name, dirs, SkipReason.CANNOT_DOWNLOAD, sourceKey, item.targetId); return }
                    candidates += Candidate(
                        PlannedFile(item.targetId, item.targetResourceKey, dirs, item.name, RemoteFingerprint.of(target), sourceKey, viaHere),
                        depth = dirs.size, order = candidates.size,
                    )
                }
            }
        }

        fun enqueue(w: FolderWork) = if (w.via) viaShortcut.addLast(w) else main.addLast(w)
        fun dirsFor(parent: List<String>, child: String) = if (keepStructure) parent + child else emptyList()

        // Real items first, shortcuts after, so a real occurrence wins the dedup.
        for (p in picks.sortedBy { it.item.isShortcut }) {
            val parentDirs = if (keepStructure) p.parentNames else emptyList()
            if (p.item.isFolder) enqueue(FolderWork(p.item.targetId, p.item.targetResourceKey, p.item.name, dirsFor(parentDirs, p.item.name), p.item.id, p.item.isShortcut))
            else addFile(p.item, parentDirs, p.item.id, false)
        }

        while (main.isNotEmpty() || viaShortcut.isNotEmpty()) {
            val w = main.removeFirstOrNull() ?: viaShortcut.removeFirst()
            if (!visited.add(w.id)) continue
            var token: String? = null
            do {
                val listing = drive.listChildren(w.id, w.resourceKey, token)
                if (listing.incompleteSearch) throw ExpansionIncomplete(w.name)
                for (child in listing.items) {
                    if (child.isFolder) enqueue(FolderWork(child.targetId, child.targetResourceKey, child.name, dirsFor(w.dirs, child.name), w.sourceKey, w.via || child.isShortcut))
                    else addFile(child, w.dirs, w.sourceKey, w.via)
                }
                token = listing.nextPageToken
            } while (token != null)
            scanned++
            onProgress(scanned, candidates.size)
        }

        // Dedup by target id: prefer non-shortcut, then shallowest, then first seen.
        val winners = candidates.groupBy { it.file.fileId }.values
            .map { group -> group.minWith(compareBy<Candidate>({ it.file.viaShortcut }, { it.depth }, { it.order })) }
            .sortedBy { it.order }
        val duplicates = candidates.size - winners.size

        // Sanitize + per-directory unique names (case-insensitive), stable in listing order.
        val taken = HashMap<List<String>, MutableSet<String>>()
        val files = winners.map { c ->
            val dirs = c.file.dirSegments.map(NameSanitizer::sanitize)
            val used = taken.getOrPut(dirs) { HashSet() }
            val name = NameSanitizer.dedupe(NameSanitizer.sanitize(c.file.name), used)
            used += name
            c.file.copy(dirSegments = dirs, name = name)
        }
        return ExpansionResult(files, skipped, duplicates, scanned)
    }
}
