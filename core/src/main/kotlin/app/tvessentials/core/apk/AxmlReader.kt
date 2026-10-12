package app.tvessentials.core.apk

import java.nio.ByteBuffer
import java.nio.ByteOrder

class AxmlException(message: String) : Exception(message)

/**
 * Minimal reader for Android's binary XML (`AndroidManifest.xml` inside an APK).
 *
 * Only extracts the handful of manifest facts the installer needs. It is deliberately strict: any
 * structural surprise throws [AxmlException], and the caller treats the APK as invalid.
 */
object AxmlReader {
    private const val RES_XML_TYPE = 0x0003
    private const val RES_STRING_POOL_TYPE = 0x0001
    private const val RES_XML_RESOURCE_MAP_TYPE = 0x0180
    private const val RES_XML_START_ELEMENT_TYPE = 0x0102
    private const val RES_XML_END_ELEMENT_TYPE = 0x0103

    private const val TYPE_REFERENCE = 0x01
    private const val TYPE_STRING = 0x03
    private const val TYPE_INT_DEC = 0x10
    private const val TYPE_INT_HEX = 0x11
    private const val TYPE_INT_BOOLEAN = 0x12

    // android: attribute resource ids
    private const val ID_NAME = 0x01010003
    private const val ID_VERSION_CODE = 0x0101021b
    private const val ID_VERSION_NAME = 0x0101021c
    private const val ID_MIN_SDK = 0x0101020c
    private const val ID_TARGET_SDK = 0x01010270
    private const val ID_VERSION_CODE_MAJOR = 0x01010576

    private const val LEANBACK_LAUNCHER = "android.intent.category.LEANBACK_LAUNCHER"
    private const val LEANBACK_FEATURE = "android.software.leanback"

    private data class Attr(val ns: Int, val name: Int, val raw: Int, val type: Int, val data: Int)

    fun parse(bytes: ByteArray): ManifestInfo {
        if (bytes.size < 16) throw AxmlException("Manifest too small")
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        if (u16(buf, 0) != RES_XML_TYPE) throw AxmlException("Not binary XML")
        val declared = buf.getInt(4)
        val limit = if (declared in 16..bytes.size) declared else bytes.size

        var strings: List<String> = emptyList()
        var resIds = IntArray(0)

        var packageId: String? = null
        var versionName: String? = null
        var versionCode: Long? = null
        var versionCodeMajor = 0L
        var minSdk: Int? = null
        var targetSdk: Int? = null
        var leanbackFeature = false
        var leanbackLauncher = false
        var depth = 0

        var pos = u16(buf, 2).let { if (it < 8) 8 else it }
        while (pos + 8 <= limit) {
            val type = u16(buf, pos)
            val headerSize = u16(buf, pos + 2)
            val size = buf.getInt(pos + 4)
            if (size < 8 || pos + size > limit || headerSize > size) throw AxmlException("Corrupt chunk at $pos")
            when (type) {
                RES_STRING_POOL_TYPE -> strings = readStringPool(buf, pos, size)
                RES_XML_RESOURCE_MAP_TYPE -> {
                    val n = (size - headerSize) / 4
                    resIds = IntArray(n) { buf.getInt(pos + headerSize + it * 4) }
                }
                RES_XML_START_ELEMENT_TYPE -> {
                    val base = pos + 16
                    if (base + 20 > pos + size) throw AxmlException("Corrupt element at $pos")
                    val name = str(strings, buf.getInt(base + 4))
                    val attrStart = u16(buf, base + 8)
                    val attrSize = u16(buf, base + 10)
                    val attrCount = u16(buf, base + 12)
                    if (attrSize < 20 || base + attrStart + attrCount * attrSize > pos + size) throw AxmlException("Corrupt attributes at $pos")
                    val attrs = ArrayList<Attr>(attrCount)
                    for (i in 0 until attrCount) {
                        val a = base + attrStart + i * attrSize
                        attrs += Attr(
                            ns = buf.getInt(a), name = buf.getInt(a + 4), raw = buf.getInt(a + 8),
                            type = buf.get(a + 15).toInt() and 0xFF, data = buf.getInt(a + 16),
                        )
                    }
                    fun id(a: Attr) = resIds.getOrElse(a.name) { 0 }
                    fun attrName(a: Attr) = str(strings, a.name)
                    fun text(a: Attr): String? = when {
                        a.type == TYPE_STRING -> strings.getOrNull(a.data)
                        a.raw >= 0 -> strings.getOrNull(a.raw)
                        a.type == TYPE_INT_DEC -> a.data.toString()
                        a.type == TYPE_INT_HEX -> "0x" + Integer.toHexString(a.data)
                        a.type == TYPE_INT_BOOLEAN -> (a.data != 0).toString()
                        a.type == TYPE_REFERENCE -> "@0x" + Integer.toHexString(a.data)
                        else -> null
                    }
                    fun number(a: Attr): Long? = when (a.type) {
                        TYPE_INT_DEC, TYPE_INT_HEX -> a.data.toLong() and 0xFFFFFFFFL
                        else -> text(a)?.toLongOrNull()
                    }
                    when {
                        depth == 0 && name == "manifest" -> {
                            for (a in attrs) {
                                when {
                                    attrName(a) == "package" && a.ns < 0 -> packageId = text(a)
                                    id(a) == ID_VERSION_CODE || attrName(a) == "versionCode" -> versionCode = number(a)
                                    id(a) == ID_VERSION_CODE_MAJOR || attrName(a) == "versionCodeMajor" -> versionCodeMajor = number(a) ?: 0L
                                    id(a) == ID_VERSION_NAME || attrName(a) == "versionName" -> versionName = text(a)
                                }
                            }
                        }
                        name == "uses-sdk" -> for (a in attrs) {
                            when {
                                id(a) == ID_MIN_SDK || attrName(a) == "minSdkVersion" -> minSdk = number(a)?.toInt()
                                id(a) == ID_TARGET_SDK || attrName(a) == "targetSdkVersion" -> targetSdk = number(a)?.toInt()
                            }
                        }
                        name == "uses-feature" -> for (a in attrs) {
                            if ((id(a) == ID_NAME || attrName(a) == "name") && text(a) == LEANBACK_FEATURE) leanbackFeature = true
                        }
                        name == "category" -> for (a in attrs) {
                            if ((id(a) == ID_NAME || attrName(a) == "name") && text(a) == LEANBACK_LAUNCHER) leanbackLauncher = true
                        }
                    }
                    depth++
                }
                RES_XML_END_ELEMENT_TYPE -> depth--
            }
            pos += size
        }

        val code = versionCode?.let { (versionCodeMajor shl 32) or it }
        return ManifestInfo(packageId, versionName, code, minSdk, targetSdk, leanbackFeature, leanbackLauncher)
    }

    private fun u16(b: ByteBuffer, at: Int): Int = b.getShort(at).toInt() and 0xFFFF

    private fun str(pool: List<String>, index: Int): String = if (index in pool.indices) pool[index] else ""

    private fun readStringPool(buf: ByteBuffer, pos: Int, size: Int): List<String> {
        val headerSize = u16(buf, pos + 2)
        val count = buf.getInt(pos + 8)
        val flags = buf.getInt(pos + 16)
        val stringsStart = buf.getInt(pos + 20)
        if (count < 0 || count > 200_000 || headerSize + count * 4 > size) throw AxmlException("Corrupt string pool")
        val utf8 = flags and 0x100 != 0
        val out = ArrayList<String>(count)
        for (i in 0 until count) {
            val off = buf.getInt(pos + headerSize + i * 4)
            val start = pos + stringsStart + off
            if (stringsStart <= 0 || off < 0 || start >= pos + size) throw AxmlException("String offset out of range")
            out += if (utf8) readUtf8(buf, start, pos + size) else readUtf16(buf, start, pos + size)
        }
        return out
    }

    private fun readUtf16(buf: ByteBuffer, start: Int, end: Int): String {
        var p = start
        var len = u16(buf, p); p += 2
        if (len and 0x8000 != 0) { len = ((len and 0x7FFF) shl 16) or u16(buf, p); p += 2 }
        if (len < 0 || p + len * 2 > end) throw AxmlException("String out of range")
        val chars = CharArray(len) { buf.getShort(p + it * 2).toInt().toChar() }
        return String(chars)
    }

    private fun readUtf8(buf: ByteBuffer, start: Int, end: Int): String {
        var p = start
        var charLen = buf.get(p).toInt() and 0xFF; p += 1
        if (charLen and 0x80 != 0) { charLen = ((charLen and 0x7F) shl 8) or (buf.get(p).toInt() and 0xFF); p += 1 }
        var byteLen = buf.get(p).toInt() and 0xFF; p += 1
        if (byteLen and 0x80 != 0) { byteLen = ((byteLen and 0x7F) shl 8) or (buf.get(p).toInt() and 0xFF); p += 1 }
        if (byteLen < 0 || p + byteLen > end) throw AxmlException("String out of range")
        val bytes = ByteArray(byteLen)
        for (i in 0 until byteLen) bytes[i] = buf.get(p + i)
        return String(bytes, Charsets.UTF_8)
    }
}
