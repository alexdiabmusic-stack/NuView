package app.tvessentials.core.testing

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Generates small but structurally real APK-shaped zip files. */
object ApkFixture {
    fun apk(
        file: File,
        packageId: String = "com.example.app",
        versionCode: Int = 10,
        versionName: String = "1.0.0",
        minSdk: Int? = 24,
        abis: List<String> = emptyList(),
        withDex: Boolean = true,
        padding: Int = 0,
        manifestOverride: ByteArray? = null,
    ): File {
        ZipOutputStream(file.outputStream()).use { z ->
            fun put(name: String, bytes: ByteArray, stored: Boolean = false) {
                val e = ZipEntry(name)
                if (stored) {
                    e.method = ZipEntry.STORED; e.size = bytes.size.toLong(); e.compressedSize = bytes.size.toLong()
                    val crc = java.util.zip.CRC32().also { it.update(bytes) }; e.crc = crc.value
                }
                z.putNextEntry(e); z.write(bytes); z.closeEntry()
            }
            put("AndroidManifest.xml", manifestOverride ?: AxmlBuilder.manifest(packageId, versionCode, versionName, minSdk))
            if (withDex) put("classes.dex", ByteArray(64) { 7 })
            put("resources.arsc", ByteArray(32) { 1 }, stored = true)
            for (abi in abis) put("lib/$abi/libnative.so", ByteArray(48) { 3 })
            // Random + STORED so the file really is `padding` bytes bigger; a repeating pattern would deflate to almost nothing.
            if (padding > 0) put("assets/blob.bin", ByteArray(padding).also { java.util.Random(42).nextBytes(it) }, stored = true)
        }
        return file
    }

    fun bundle(file: File): File {
        ZipOutputStream(file.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("manifest.json")); z.write("{}".toByteArray()); z.closeEntry()
            z.putNextEntry(ZipEntry("com.example.app.apk")); z.write(ByteArray(10)); z.closeEntry()
        }
        return file
    }

    fun zipWithEntries(file: File, vararg names: String): File {
        ZipOutputStream(file.outputStream()).use { z ->
            for (n in names) { z.putNextEntry(ZipEntry(n)); z.write(byteArrayOf(1)); z.closeEntry() }
        }
        return file
    }
}
