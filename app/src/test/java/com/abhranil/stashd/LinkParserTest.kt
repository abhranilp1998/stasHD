package com.abhranil.stashd

import com.abhranil.stashd.data.drive.LinkParser
import com.abhranil.stashd.data.storage.NameSanitizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkParserTest {
    @Test fun folderLink() {
        val l = LinkParser.parse("https://drive.google.com/drive/folders/1wa95llBR886wVZ-cps7XbhhDfuOtGCdU")!!
        assertEquals("1wa95llBR886wVZ-cps7XbhhDfuOtGCdU", l.id)
        assertTrue(l.isFolder)
    }

    @Test fun userIndexedFolderWithResourceKey() {
        val l = LinkParser.parse("drive.google.com/drive/u/1/folders/0B1234567890abcdEFG?resourcekey=0-AbC_d")!!
        assertEquals("0B1234567890abcdEFG", l.id)
        assertEquals("0-AbC_d", l.resourceKey)
    }

    @Test fun fileLink() {
        val l = LinkParser.parse("https://drive.google.com/file/d/1AbCdEfGhIjKlMnOp/view?usp=sharing")!!
        assertEquals("1AbCdEfGhIjKlMnOp", l.id)
        assertEquals(false, l.isFolder)
    }

    @Test fun openId() {
        assertEquals("1AbCdEfGhIjKlMnOp", LinkParser.parse("https://drive.google.com/open?id=1AbCdEfGhIjKlMnOp")!!.id)
    }

    @Test fun rejectsOtherHosts() {
        assertNull(LinkParser.parse("https://example.com/drive/folders/1wa95llBR886wVZ"))
        assertNull(LinkParser.parse("not a link"))
    }

    @Test fun sanitizesSlash() {
        assertEquals("Green Day - 39_Smooth (1990) 24-96", NameSanitizer.sanitize("Green Day - 39/Smooth (1990) 24-96"))
        assertEquals("a (2).flac", NameSanitizer.dedupe("a.flac", setOf("a.flac")))
    }
}

class LinkParserResourceKeyTest {
    @Test fun resourceKeyAfterOtherParams() {
        val l = LinkParser.parse("https://drive.google.com/drive/folders/0B1234567890abcdEFG?usp=sharing&resourcekey=0-XyZ123")!!
        assertEquals("0-XyZ123", l.resourceKey)
    }

    @Test fun resourceKeyIsUrlDecoded() {
        assertEquals("0-a/b", LinkParser.parse("https://drive.google.com/file/d/1AbCdEfGhIjKlMnOp/view?resourcekey=0-a%2Fb")!!.resourceKey)
    }

    @Test fun noResourceKeyIsNull() {
        assertNull(LinkParser.parse("https://drive.google.com/drive/folders/1wa95llBR886wVZ-cps7XbhhDfuOtGCdU?usp=sharing")!!.resourceKey)
        assertNull(LinkParser.parse("https://drive.google.com/drive/folders/1wa95llBR886wVZ-cps7XbhhDfuOtGCdU?resourcekey=")!!.resourceKey)
    }

    @Test fun openIdWithResourceKey() {
        val l = LinkParser.parse("https://drive.google.com/open?id=1AbCdEfGhIjKlMnOp&resourcekey=0-k")!!
        assertEquals("1AbCdEfGhIjKlMnOp" to "0-k", l.id to l.resourceKey)
    }
}

class NameSanitizerTest {
    @Test fun dotsAndSeparators() {
        assertEquals("_", NameSanitizer.sanitize(".."))
        assertEquals("a_b_c", NameSanitizer.sanitize("a/b\\c"))
    }

    @Test fun longMultibyteNamesFitInBytesKeepingExtension() {
        val name = "曲".repeat(150) + ".flac"
        val s = NameSanitizer.sanitize(name)
        assertTrue(s.toByteArray().size <= NameSanitizer.MAX_NAME_BYTES)
        assertTrue(s.endsWith(".flac"))
    }

    @Test fun dedupeIsCaseInsensitive() {
        assertEquals("Song (2).flac", NameSanitizer.dedupe("Song.flac", setOf("song.FLAC")))
        assertEquals("Song (3).flac", NameSanitizer.dedupe("Song.flac", setOf("Song.flac", "Song (2).flac")))
    }
}
