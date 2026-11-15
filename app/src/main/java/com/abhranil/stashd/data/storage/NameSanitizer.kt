package com.abhranil.stashd.data.storage

/** Makes Drive names safe for Android filesystems (e.g. "Green Day - 39/Smooth" has a slash). */
object NameSanitizer {
    private val illegal = Regex("[/\\\\:*?\"<>|\\u0000-\\u001F]")

    fun sanitize(name: String): String {
        val cleaned = name.replace(illegal, "_").trim().trimEnd('.', ' ')
        return cleaned.ifEmpty { "_" }.take(200)
    }

    /** Appends " (2)", " (3)"… before the extension until the name is unused. */
    fun dedupe(name: String, taken: Set<String>): String {
        if (name !in taken) return name
        val dot = name.lastIndexOf('.').takeIf { it > 0 } ?: name.length
        val base = name.substring(0, dot)
        val ext = name.substring(dot)
        var n = 2
        while ("$base ($n)$ext" in taken) n++
        return "$base ($n)$ext"
    }
}
