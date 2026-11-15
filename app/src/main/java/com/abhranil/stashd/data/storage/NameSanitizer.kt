package com.abhranil.stashd.data.storage

/** Makes Drive names safe for Android filesystems (e.g. "Green Day - 39/Smooth" has a slash). */
object NameSanitizer {
    private val illegal = Regex("[/\\\\:*?\"<>|\\u0000-\\u001F\\u007F]")

    /** Max UTF-8 bytes per name: 255 is the FS limit; leave room for " (12)" and ".stashdpart". */
    const val MAX_NAME_BYTES = 200

    fun sanitize(name: String): String {
        val cleaned = name.replace(illegal, "_").trim().trimEnd('.', ' ')
        return truncateUtf8(cleaned.ifEmpty { "_" }, MAX_NAME_BYTES)
    }

    /** Appends " (2)", " (3)"… before the extension until the name is unused (case-insensitive, like FAT/exFAT SD cards). */
    fun dedupe(name: String, taken: Set<String>): String {
        val lower = taken.mapTo(HashSet()) { it.lowercase() }
        if (name.lowercase() !in lower) return name
        val (base, ext) = split(name)
        var n = 2
        while ("$base ($n)$ext".lowercase() in lower) n++
        return "$base ($n)$ext"
    }

    /** Cuts the base name (keeping a short extension) so the whole name fits in [maxBytes] UTF-8 bytes. */
    fun truncateUtf8(name: String, maxBytes: Int): String {
        if (name.toByteArray().size <= maxBytes) return name
        val (base, ext0) = split(name)
        val ext = if (ext0.toByteArray().size <= 16) ext0 else ""
        val budget = maxBytes - ext.toByteArray().size
        val sb = StringBuilder()
        var used = 0
        var i = 0
        while (i < base.length) {
            val cp = base.codePointAt(i)
            val s = String(Character.toChars(cp))
            val b = s.toByteArray().size
            if (used + b > budget) break
            sb.append(s); used += b; i += Character.charCount(cp)
        }
        return sb.toString().trimEnd('.', ' ').ifEmpty { "_" } + ext
    }

    private fun split(name: String): Pair<String, String> {
        val dot = name.lastIndexOf('.').takeIf { it > 0 } ?: name.length
        return name.substring(0, dot) to name.substring(dot)
    }
}
