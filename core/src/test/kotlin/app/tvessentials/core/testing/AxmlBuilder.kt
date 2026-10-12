package app.tvessentials.core.testing

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Builds a genuine binary AndroidManifest.xml (same chunk layout aapt2 emits) for tests. */
object AxmlBuilder {
    private const val ANDROID_NS = "http://schemas.android.com/apk/res/android"

    private val attrNames = listOf("versionCode", "versionName", "minSdkVersion", "targetSdkVersion", "name")
    private val attrIds = intArrayOf(0x0101021b, 0x0101021c, 0x0101020c, 0x01010270, 0x01010003)

    fun manifest(
        packageId: String,
        versionCode: Int,
        versionName: String,
        minSdk: Int? = 24,
        targetSdk: Int? = 34,
        leanbackFeature: Boolean = true,
        leanbackLauncher: Boolean = true,
    ): ByteArray {
        val strings = ArrayList<String>()
        fun s(v: String): Int { val i = strings.indexOf(v); if (i >= 0) return i; strings += v; return strings.size - 1 }
        attrNames.forEach { s(it) } // keep ids aligned with the resource map
        val nsUri = s(ANDROID_NS); val nsPrefix = s("android")
        val tManifest = s("manifest"); val tSdk = s("uses-sdk"); val tFeature = s("uses-feature")
        val tApp = s("application"); val tActivity = s("activity"); val tFilter = s("intent-filter"); val tCategory = s("category")
        val aPackage = s("package")
        val vPackage = s(packageId); val vName = s(versionName)
        val vFeature = s("android.software.leanback"); val vCategory = s("android.intent.category.LEANBACK_LAUNCHER")

        class A(val ns: Int, val name: Int, val raw: Int, val type: Int, val data: Int)
        val body = ByteArrayOutputStream()
        fun chunk(type: Int, header: Int, payload: ByteArray, extraHeader: ByteArray = ByteArray(0)): ByteArray {
            val size = header + extraHeader.size + payload.size
            val b = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
            b.putShort(type.toShort()).putShort((header + extraHeader.size).toShort()).putInt(size)
            b.put(extraHeader).put(payload)
            return b.array()
        }
        fun lineHeader() = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putInt(1).putInt(-1).array()
        fun start(name: Int, attrs: List<A>): ByteArray {
            val p = ByteBuffer.allocate(20 + 20 * attrs.size).order(ByteOrder.LITTLE_ENDIAN)
            p.putInt(-1).putInt(name).putShort(0x14).putShort(0x14).putShort(attrs.size.toShort()).putShort(0).putShort(0).putShort(0)
            for (a in attrs) {
                p.putInt(a.ns).putInt(a.name).putInt(a.raw)
                p.putShort(8).put(0).put(a.type.toByte()).putInt(a.data)
            }
            return chunk(0x0102, 8, p.array(), lineHeader())
        }
        fun end(name: Int) = chunk(0x0103, 8, ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putInt(-1).putInt(name).array(), lineHeader())
        fun nsChunk(type: Int) = chunk(type, 8, ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putInt(nsPrefix).putInt(nsUri).array(), lineHeader())
        val STR = 0x03; val INT = 0x10

        body.write(nsChunk(0x0100))
        body.write(start(tManifest, listOf(
            A(nsUri, 0, -1, INT, versionCode),
            A(nsUri, 1, vName, STR, vName),
            A(-1, aPackage, vPackage, STR, vPackage),
        )))
        val sdk = ArrayList<A>()
        if (minSdk != null) sdk += A(nsUri, 2, -1, INT, minSdk)
        if (targetSdk != null) sdk += A(nsUri, 3, -1, INT, targetSdk)
        if (sdk.isNotEmpty()) { body.write(start(tSdk, sdk)); body.write(end(tSdk)) }
        if (leanbackFeature) { body.write(start(tFeature, listOf(A(nsUri, 4, vFeature, STR, vFeature)))); body.write(end(tFeature)) }
        body.write(start(tApp, emptyList()))
        body.write(start(tActivity, emptyList()))
        body.write(start(tFilter, emptyList()))
        if (leanbackLauncher) { body.write(start(tCategory, listOf(A(nsUri, 4, vCategory, STR, vCategory)))); body.write(end(tCategory)) }
        body.write(end(tFilter)); body.write(end(tActivity)); body.write(end(tApp)); body.write(end(tManifest))
        body.write(nsChunk(0x0101))

        // string pool (UTF-16)
        val data = ByteArrayOutputStream()
        val offsets = ByteBuffer.allocate(4 * strings.size).order(ByteOrder.LITTLE_ENDIAN)
        for (str in strings) {
            offsets.putInt(data.size())
            val b = ByteBuffer.allocate(2 + 2 * str.length + 2).order(ByteOrder.LITTLE_ENDIAN)
            b.putShort(str.length.toShort()); str.forEach { b.putShort(it.code.toShort()) }; b.putShort(0)
            data.write(b.array())
        }
        while (data.size() % 4 != 0) data.write(0)
        val poolHeaderExtra = ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(strings.size).putInt(0).putInt(0).putInt(28 + 4 * strings.size).putInt(0).array()
        val pool = chunk(0x0001, 8, offsets.array() + data.toByteArray(), poolHeaderExtra)

        val resMap = ByteBuffer.allocate(4 * attrIds.size).order(ByteOrder.LITTLE_ENDIAN).also { b -> attrIds.forEach { b.putInt(it) } }
        val resChunk = chunk(0x0180, 8, resMap.array())

        val inner = pool + resChunk + body.toByteArray()
        val out = ByteBuffer.allocate(8 + inner.size).order(ByteOrder.LITTLE_ENDIAN)
        out.putShort(0x0003).putShort(8).putInt(8 + inner.size).put(inner)
        return out.array()
    }
}
