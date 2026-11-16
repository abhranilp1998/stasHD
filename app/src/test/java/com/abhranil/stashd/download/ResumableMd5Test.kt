package com.abhranil.stashd.download

import com.abhranil.stashd.download.core.FileDownloader.Companion.toHex
import com.abhranil.stashd.download.core.ResumableMd5
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.security.MessageDigest
import kotlin.random.Random

class ResumableMd5Test {
    private fun ref(b: ByteArray) = MessageDigest.getInstance("MD5").digest(b).toHex()

    @Test fun matchesMessageDigestForAllPaddingEdges() {
        assertEquals("d41d8cd98f00b204e9800998ecf8427e", ResumableMd5().digestHex())
        for (n in listOf(1, 3, 55, 56, 57, 63, 64, 65, 119, 120, 128, 1000, 300_001)) {
            val b = Random(n).nextBytes(n)
            assertEquals("size $n", ref(b), ResumableMd5().apply { update(b) }.digestHex())
        }
    }

    @Test fun saveAndRestoreAtAnyOffsetGivesTheSameDigest() {
        val b = Random(1).nextBytes(200_003)
        for (cut in listOf(0, 1, 63, 64, 100, 8_192, 150_017, 200_003)) {
            val first = ResumableMd5().apply { update(b, 0, cut) }
            val restored = ResumableMd5.restore(first.saveState())!!
            assertEquals(cut.toLong(), restored.length)
            restored.update(b, cut, b.size - cut)
            assertEquals("cut $cut", ref(b), restored.digestHex())
        }
    }

    @Test fun oddChunkingAndDigestDoesNotDisturbRunningState() {
        val b = Random(2).nextBytes(10_000)
        val m = ResumableMd5()
        var i = 0
        while (i < b.size) { val n = minOf(1 + i % 97, b.size - i); m.update(b, i, n); i += n; if (i == 5_000) m.digestHex() }
        assertEquals(ref(b), m.digestHex())
    }

    @Test fun garbageStateIsRejected() {
        assertNull(ResumableMd5.restore("zz"))
        assertNull(ResumableMd5.restore(""))
        assertNull(ResumableMd5.restore("00".repeat(30)))
    }
}
