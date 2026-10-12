package app.tvessentials.core.download

import app.tvessentials.core.source.ReleaseAsset
import java.io.File

/** The app's private store of downloaded APKs. Nothing here is ever shared with other apps. */
class DownloadCache(val dir: File) {

    fun fileFor(appId: String, versionName: String?, asset: ReleaseAsset): File {
        val name = "${safe(appId)}__${safe(versionName ?: "unverified")}__${safe(asset.fileName)}"
        val withExt = if (name.endsWith(".apk", ignoreCase = true)) name else "$name.apk"
        return File(dir, withExt)
    }

    fun sizeBytes(): Long = dir.listFiles()?.sumOf { it.length() } ?: 0L

    /** Deletes everything and returns the number of bytes freed. */
    fun clear(): Long {
        val freed = sizeBytes()
        dir.listFiles()?.forEach { it.delete() }
        return freed
    }

    /** Removes partial/abandoned files older than [maxAgeMs] except those in [keep]. */
    fun purgeAbandoned(maxAgeMs: Long, nowMs: Long = System.currentTimeMillis(), keep: Set<File> = emptySet()): Int {
        var removed = 0
        dir.listFiles()?.forEach { f ->
            val partial = f.name.endsWith(".part") || f.name.endsWith(".part.meta")
            val stale = nowMs - f.lastModified() > maxAgeMs
            if (stale && f !in keep && (partial || f.name.endsWith(".apk"))) { if (f.delete()) removed++ }
        }
        return removed
    }

    /** After a successful install the cached copy has done its job; other files for the same app are obsolete. */
    fun removeForApp(appId: String, except: File? = null) {
        val prefix = safe(appId) + "__"
        dir.listFiles()?.filter { it.name.startsWith(prefix) && it != except && !(except != null && it.name.startsWith(except.name)) }
            ?.forEach { it.delete() }
    }

    private fun safe(s: String): String = s.map { if (it.isLetterOrDigit() || it == '.' || it == '-') it else '_' }.joinToString("").take(80)
}
