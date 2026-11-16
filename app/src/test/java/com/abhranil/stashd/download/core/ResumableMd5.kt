package com.abhranil.stashd.download.core

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Legacy M3 checkpoint producer retained only as a test fixture. Production uses MessageDigest and
 * rechecks bytes on disk. Tests use this to prove that genuine old checkpoints cannot hide corruption.
 */
class ResumableMd5 private constructor(
    private val h: IntArray,
    private val buffer: ByteArray,
    private var bufferLen: Int,
    /** Total bytes hashed so far. */
    var length: Long,
) {
    constructor() : this(intArrayOf(0x67452301, -0x10325477, -0x67452302, 0x10325476), ByteArray(64), 0, 0)

    private val x = IntArray(16)

    fun update(b: ByteArray, off: Int = 0, len: Int = b.size) {
        var i = off
        var left = len
        length += len
        if (bufferLen > 0) {
            val n = minOf(64 - bufferLen, left)
            System.arraycopy(b, i, buffer, bufferLen, n)
            bufferLen += n; i += n; left -= n
            if (bufferLen == 64) { block(buffer, 0); bufferLen = 0 }
        }
        while (left >= 64) { block(b, i); i += 64; left -= 64 }
        if (left > 0) { System.arraycopy(b, i, buffer, 0, left); bufferLen = left }
    }

    /** Finishes a COPY, so this instance can keep going. */
    fun digestHex(): String {
        val c = ResumableMd5(h.copyOf(), buffer.copyOf(), bufferLen, length)
        val bits = length * 8
        val pad = ByteArray(if (bufferLen < 56) 56 - bufferLen else 120 - bufferLen).also { it[0] = 0x80.toByte() }
        c.update(pad)
        c.update(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(bits).array())
        val out = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
        c.h.forEach { out.putInt(it) }
        return out.array().joinToString("") { "%02x".format(it) }
    }

    /** 4 state words + length + buffered tail: 8 + 16 + 1 + up to 63 bytes, as hex. */
    fun saveState(): String {
        val bb = ByteBuffer.allocate(8 + 16 + 1 + bufferLen).order(ByteOrder.LITTLE_ENDIAN)
        bb.putLong(length); h.forEach { bb.putInt(it) }; bb.put(bufferLen.toByte()); bb.put(buffer, 0, bufferLen)
        return bb.array().joinToString("") { "%02x".format(it) }
    }

    private fun block(b: ByteArray, off: Int) {
        for (k in 0 until 16) {
            val p = off + k * 4
            x[k] = (b[p].toInt() and 0xff) or ((b[p + 1].toInt() and 0xff) shl 8) or
                ((b[p + 2].toInt() and 0xff) shl 16) or ((b[p + 3].toInt() and 0xff) shl 24)
        }
        var a = h[0]; var bb = h[1]; var c = h[2]; var d = h[3]
        for (i in 0 until 64) {
            val f: Int; val g: Int
            when (i / 16) {
                0 -> { f = (bb and c) or (bb.inv() and d); g = i }
                1 -> { f = (d and bb) or (d.inv() and c); g = (5 * i + 1) % 16 }
                2 -> { f = bb xor c xor d; g = (3 * i + 5) % 16 }
                else -> { f = c xor (bb or d.inv()); g = (7 * i) % 16 }
            }
            val tmp = d
            d = c; c = bb
            bb += Integer.rotateLeft(a + f + K[i] + x[g], S[i])
            a = tmp
        }
        h[0] += a; h[1] += bb; h[2] += c; h[3] += d
    }

    companion object {
        private val S = intArrayOf(
            7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22,
            5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20,
            4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23,
            6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21,
        )
        private val K = IntArray(64) { (kotlin.math.abs(kotlin.math.sin((it + 1).toDouble())) * 4294967296.0).toLong().toInt() }

        /** Null if the text isn't a state written by [saveState]. */
        fun restore(state: String): ResumableMd5? = runCatching {
            val bytes = ByteArray(state.length / 2) { state.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
            val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val len = bb.long
            val h = IntArray(4) { bb.int }
            val n = bb.get().toInt()
            require(n in 0..63 && bb.remaining() == n && len >= 0 && len % 64 == n.toLong())
            val buf = ByteArray(64).also { bb.get(it, 0, n) }
            ResumableMd5(h, buf, n, len)
        }.getOrNull()
    }
}
