package app.tvessentials.core.apk

import app.tvessentials.core.net.HostPolicy
import app.tvessentials.core.net.HttpRequest
import app.tvessentials.core.net.NetFailure
import app.tvessentials.core.net.SafeHttp
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.Inflater

/**
 * Reads an APK's `AndroidManifest.xml` straight off a server using HTTP range requests
 * (end-of-central-directory, central directory, then just the manifest entry) so a version can be
 * learned without downloading the whole file.
 *
 * The result only drives *display* ("is an update likely?"). It is unauthenticated: nothing about
 * installation trust ever depends on it. The full download is verified independently.
 */
class RemoteApkProbe(
    private val http: SafeHttp,
    private val policy: HostPolicy,
) {
    suspend fun readManifest(url: String, totalSize: Long): ManifestInfo? {
        if (totalSize < 22 || totalSize > 4L * 1024 * 1024 * 1024) return null
        // 1) tail: end-of-central-directory record.
        val tailLen = minOf(totalSize, 66_000L)
        val tail = range(url, suffix = tailLen) ?: return null
        val eocdAt = findEocd(tail) ?: return null
        val bb = ByteBuffer.wrap(tail).order(ByteOrder.LITTLE_ENDIAN)
        val cdSize = bb.getInt(eocdAt + 12).toLong() and 0xFFFFFFFFL
        val cdOffset = bb.getInt(eocdAt + 16).toLong() and 0xFFFFFFFFL
        if (cdSize == 0xFFFFFFFFL || cdOffset == 0xFFFFFFFFL) return null // ZIP64: not worth supporting here
        if (cdSize > 8L * 1024 * 1024 || cdOffset + cdSize > totalSize) return null

        // 2) central directory (often already inside the tail).
        val tailStart = totalSize - tail.size
        val cd: ByteArray = if (cdOffset >= tailStart) {
            tail.copyOfRange((cdOffset - tailStart).toInt(), (cdOffset - tailStart + cdSize).toInt())
        } else {
            range(url, start = cdOffset, end = cdOffset + cdSize - 1) ?: return null
        }

        // 3) find the AndroidManifest.xml entry.
        val entry = findEntry(cd, "AndroidManifest.xml") ?: return null
        if (entry.compressedSize > 4 * 1024 * 1024) return null

        // 4) local header -> data offset, then the compressed data itself.
        val lh = range(url, start = entry.localHeaderOffset, end = entry.localHeaderOffset + 29) ?: return null
        val lhb = ByteBuffer.wrap(lh).order(ByteOrder.LITTLE_ENDIAN)
        if (lh.size < 30 || lhb.getInt(0) != 0x04034b50) return null
        val dataStart = entry.localHeaderOffset + 30 + (lhb.getShort(26).toInt() and 0xFFFF) + (lhb.getShort(28).toInt() and 0xFFFF)
        val data = range(url, start = dataStart, end = dataStart + entry.compressedSize - 1) ?: return null

        val raw = when (entry.method) {
            0 -> data
            8 -> inflate(data, entry.uncompressedSize.toInt()) ?: return null
            else -> return null
        }
        return try { AxmlReader.parse(raw) } catch (_: AxmlException) { null }
    }

    private suspend fun range(url: String, start: Long? = null, end: Long? = null, suffix: Long? = null): ByteArray? = try {
        http.fetch(HttpRequest(url, rangeStart = start, rangeEnd = end, suffixRange = suffix), policy) { r ->
            // Only a 206 proves the server honoured the range; a 200 would be the whole file.
            if (r.code != 206) null else r.readBytes(10 * 1024 * 1024)
        }
    } catch (e: NetFailure) {
        null
    }

    private data class Entry(val method: Int, val compressedSize: Long, val uncompressedSize: Long, val localHeaderOffset: Long)

    private fun findEocd(tail: ByteArray): Int? {
        for (i in tail.size - 22 downTo maxOf(0, tail.size - 22 - 65_535)) {
            if (tail[i] == 0x50.toByte() && tail[i + 1] == 0x4B.toByte() && tail[i + 2] == 0x05.toByte() && tail[i + 3] == 0x06.toByte()) return i
        }
        return null
    }

    private fun findEntry(cd: ByteArray, wanted: String): Entry? {
        val b = ByteBuffer.wrap(cd).order(ByteOrder.LITTLE_ENDIAN)
        var p = 0
        while (p + 46 <= cd.size && b.getInt(p) == 0x02014b50) {
            val method = b.getShort(p + 10).toInt() and 0xFFFF
            val csize = b.getInt(p + 20).toLong() and 0xFFFFFFFFL
            val usize = b.getInt(p + 24).toLong() and 0xFFFFFFFFL
            val nameLen = b.getShort(p + 28).toInt() and 0xFFFF
            val extraLen = b.getShort(p + 30).toInt() and 0xFFFF
            val commentLen = b.getShort(p + 32).toInt() and 0xFFFF
            val offset = b.getInt(p + 42).toLong() and 0xFFFFFFFFL
            if (p + 46 + nameLen > cd.size) return null
            val name = String(cd, p + 46, nameLen, Charsets.UTF_8)
            if (name == wanted) return Entry(method, csize, usize, offset)
            p += 46 + nameLen + extraLen + commentLen
        }
        return null
    }

    private fun inflate(data: ByteArray, expected: Int): ByteArray? {
        if (expected <= 0 || expected > 8 * 1024 * 1024) return null
        val inflater = Inflater(true)
        return try {
            inflater.setInput(data)
            val out = ByteArrayOutputStream(expected)
            val buf = ByteArray(16 * 1024)
            while (!inflater.finished()) {
                val n = inflater.inflate(buf)
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
                out.write(buf, 0, n)
                if (out.size() > 8 * 1024 * 1024) return null
            }
            out.toByteArray().takeIf { it.isNotEmpty() }
        } catch (e: java.util.zip.DataFormatException) {
            null
        } finally {
            inflater.end()
        }
    }
}
