package app.tvessentials.core.apk

import java.io.File
import java.io.IOException
import java.util.zip.ZipException
import java.util.zip.ZipFile

/** What a structural inspection of a downloaded file found. */
data class ApkStructure(
    val manifest: ManifestInfo?,
    val nativeAbis: Set<String>,
    val dexCount: Int,
    val entryCount: Int,
    val hasV1Signature: Boolean,
    /** Non-empty means the file must not be handed to the installer. */
    val problems: List<Problem>,
) {
    val isAcceptable: Boolean get() = problems.isEmpty()
}

enum class Problem {
    NOT_A_ZIP,
    BUNDLE_NOT_SUPPORTED,
    NO_MANIFEST,
    MANIFEST_UNREADABLE,
    NO_CODE,
    SUSPICIOUS_ENTRY_NAME,
    TOO_MANY_ENTRIES,
}

/**
 * Inspects a file's actual content rather than trusting its name or content type: it must be a real
 * ZIP with a parsable binary manifest and executable code, and must not be a split/bundle container.
 * (Signature verification is done by the platform's package parser, not here.)
 */
object ApkInspector {
    private const val MAX_ENTRIES = 100_000
    private const val MAX_MANIFEST_BYTES = 8 * 1024 * 1024
    private val ZIP_MAGIC = byteArrayOf(0x50, 0x4B, 0x03, 0x04)

    fun looksLikeZip(first4: ByteArray): Boolean = first4.size >= 4 && (0..3).all { first4[it] == ZIP_MAGIC[it] }

    fun inspect(file: File): ApkStructure {
        val head = try {
            file.inputStream().use { s -> ByteArray(4).also { s.read(it) } }
        } catch (e: IOException) {
            return failed(Problem.NOT_A_ZIP)
        }
        if (!looksLikeZip(head)) return failed(Problem.NOT_A_ZIP)

        val problems = LinkedHashSet<Problem>()
        try {
            ZipFile(file).use { zip ->
                var manifestBytes: ByteArray? = null
                var dex = 0
                var count = 0
                var nestedApks = 0
                var hasBundleMarker = false
                var v1 = false
                val abis = LinkedHashSet<String>()
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val e = entries.nextElement()
                    count++
                    if (count > MAX_ENTRIES) { problems += Problem.TOO_MANY_ENTRIES; break }
                    val name = e.name
                    if (name.startsWith("/") || name.contains("../") || name.contains("\\") || name.contains('\u0000')) {
                        problems += Problem.SUSPICIOUS_ENTRY_NAME
                    }
                    when {
                        name == "AndroidManifest.xml" && !e.isDirectory -> {
                            if (e.size > MAX_MANIFEST_BYTES) problems += Problem.MANIFEST_UNREADABLE
                            else manifestBytes = zip.getInputStream(e).use { it.readBytes() }
                        }
                        !name.contains('/') && name.matches(Regex("classes\\d*\\.dex")) -> dex++
                        name.startsWith("lib/") && name.endsWith(".so") ->
                            name.split('/').getOrNull(1)?.let { abis += it }
                        name.startsWith("META-INF/") && (name.endsWith(".RSA") || name.endsWith(".DSA") || name.endsWith(".EC")) -> v1 = true
                        !name.contains('/') && name.endsWith(".apk") -> nestedApks++
                        name == "manifest.json" || name == "info.json" || name == "toc.pb" -> hasBundleMarker = true
                    }
                }
                val manifest = manifestBytes?.let {
                    try { AxmlReader.parse(it) } catch (_: AxmlException) { problems += Problem.MANIFEST_UNREADABLE; null }
                }
                if (manifestBytes == null && Problem.MANIFEST_UNREADABLE !in problems) {
                    problems += if (nestedApks > 0 || hasBundleMarker) Problem.BUNDLE_NOT_SUPPORTED else Problem.NO_MANIFEST
                }
                if (manifestBytes != null && dex == 0) problems += Problem.NO_CODE
                return ApkStructure(manifest, abis, dex, count, v1, problems.toList())
            }
        } catch (e: ZipException) {
            return failed(Problem.NOT_A_ZIP)
        } catch (e: IOException) {
            return failed(Problem.NOT_A_ZIP)
        }
    }

    private fun failed(p: Problem) = ApkStructure(null, emptySet(), 0, 0, false, listOf(p))
}
