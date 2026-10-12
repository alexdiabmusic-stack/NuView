package app.tvessentials.core.util

import java.io.File
import java.io.InputStream
import java.security.MessageDigest

object Hex {
    private val DIGITS = "0123456789abcdef".toCharArray()

    fun encode(bytes: ByteArray): String {
        val out = CharArray(bytes.size * 2)
        for (i in bytes.indices) {
            val v = bytes[i].toInt() and 0xFF
            out[i * 2] = DIGITS[v ushr 4]
            out[i * 2 + 1] = DIGITS[v and 0x0F]
        }
        return String(out)
    }

    /** Lower-cases and strips ':' / whitespace so "AB:CD" and "abcd" compare equal. Returns null if not hex of [bytes] length. */
    fun normalizeDigest(input: String?, bytes: Int = 32): String? {
        if (input == null) return null
        val s = input.filter { it != ':' && !it.isWhitespace() }.lowercase()
        if (s.length != bytes * 2 || !s.all { it in '0'..'9' || it in 'a'..'f' }) return null
        return s
    }
}

object Digests {
    fun sha256(bytes: ByteArray): String = Hex.encode(MessageDigest.getInstance("SHA-256").digest(bytes))

    fun sha256(stream: InputStream, bufferSize: Int = 64 * 1024): String {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(bufferSize)
        while (true) {
            val n = stream.read(buf)
            if (n < 0) break
            md.update(buf, 0, n)
        }
        return Hex.encode(md.digest())
    }

    fun sha256(file: File): String = file.inputStream().use { sha256(it) }
}
