package app.tvessentials.core.source

import app.tvessentials.core.util.Digests
import java.io.File

data class CachedBody(val etag: String?, val body: String, val savedAtMs: Long)

/** Stores API responses with their ETag so unchanged data costs a conditional request (free against GitHub's limits). */
interface HttpCacheStore {
    fun get(key: String): CachedBody?
    fun put(key: String, value: CachedBody)
    fun clear()
}

class InMemoryHttpCache : HttpCacheStore {
    private val map = java.util.concurrent.ConcurrentHashMap<String, CachedBody>()
    override fun get(key: String) = map[key]
    override fun put(key: String, value: CachedBody) { map[key] = value }
    override fun clear() = map.clear()
}

class FileHttpCache(private val dir: File) : HttpCacheStore {
    private fun file(key: String) = File(dir, Digests.sha256(key.toByteArray()).take(32) + ".cache")

    override fun get(key: String): CachedBody? = try {
        val f = file(key)
        if (!f.isFile) null else {
            val lines = f.readText().split('\n', limit = 3)
            if (lines.size < 3) null else CachedBody(lines[1].ifEmpty { null }, lines[2], lines[0].toLong())
        }
    } catch (_: Exception) { null }

    override fun put(key: String, value: CachedBody) {
        try {
            dir.mkdirs()
            val tmp = File(dir, "tmp-${System.nanoTime()}")
            tmp.writeText("${value.savedAtMs}\n${value.etag.orEmpty()}\n${value.body}")
            if (!tmp.renameTo(file(key))) { file(key).delete(); tmp.renameTo(file(key)) }
        } catch (_: Exception) { /* a cache write failure must never break a check */ }
    }

    override fun clear() { dir.listFiles()?.forEach { it.delete() } }
}
