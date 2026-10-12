package app.tvessentials.core.download

import app.tvessentials.core.install.StorageGateway
import app.tvessentials.core.net.HostPolicy
import app.tvessentials.core.net.HttpRequest
import app.tvessentials.core.net.NetFailure
import app.tvessentials.core.net.SafeHttp
import app.tvessentials.core.util.Hex
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest

data class DownloadRequest(
    val url: String,
    /** Final location of the finished APK. Must live inside the app's private cache directory. */
    val destination: File,
    val expectedSize: Long?,
    /** SHA-256 published by the host (lower-case hex). Enforced when present. */
    val expectedSha256: String?,
    val policy: HostPolicy,
)

data class DownloadProgress(val downloadedBytes: Long, val totalBytes: Long?)

data class DownloadedFile(val file: File, val sha256: String, val sizeBytes: Long)

class DownloadFailure(val kind: Kind, message: String, val net: NetFailure? = null, cause: Throwable? = null) :
    Exception(message, cause) {
    enum class Kind { INSUFFICIENT_STORAGE, HASH_MISMATCH, SIZE_MISMATCH, TRUNCATED, IO, NETWORK }
}

/**
 * Downloads one APK into the app's private storage.
 *
 *  - resumable: partial data is kept across cancellation, failures and restarts, and only continued
 *    when the server vouches (ETag/Last-Modified via If-Range) that it is still the same file
 *  - hashes while streaming, then enforces size and the published SHA-256 before the file is published
 *  - writes to `<name>.part` and renames atomically, so a half-written file is never mistaken for an APK
 *  - progress is throttled so the UI stays smooth during a 200 MB transfer
 */
class ApkDownloader(
    private val http: SafeHttp,
    private val storage: StorageGateway,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val maxAutoRetries: Int = 3,
    private val retryDelayMs: (attempt: Int) -> Long = { 1000L shl (it - 1).coerceAtMost(3) },
    /** Space the system installer needs for its own staging copy, as a multiple of the APK size. */
    private val installerStagingFactor: Double = 1.0,
    private val safetyMarginBytes: Long = 48L * 1024 * 1024,
) {
    suspend fun download(req: DownloadRequest, onProgress: (DownloadProgress) -> Unit): DownloadedFile =
        withContext(Dispatchers.IO) {
            val dir = req.destination.parentFile ?: throw DownloadFailure(DownloadFailure.Kind.IO, "Bad destination")
            dir.mkdirs()

            existingComplete(req)?.let { done ->
                onProgress(DownloadProgress(done.sizeBytes, done.sizeBytes))
                return@withContext done
            }

            val part = File(dir, req.destination.name + ".part")
            val meta = File(dir, req.destination.name + ".part.meta")
            var failuresInARow = 0
            var totalFailures = 0
            while (true) {
                val before = part.length()
                try {
                    return@withContext attempt(req, part, meta, onProgress)
                } catch (e: CancellationException) {
                    throw e // partial data is intentionally kept for a later resume
                } catch (e: DownloadFailure) {
                    if (e.kind == DownloadFailure.Kind.HASH_MISMATCH || e.kind == DownloadFailure.Kind.SIZE_MISMATCH) {
                        part.delete(); meta.delete()
                    }
                    val transient = e.kind == DownloadFailure.Kind.TRUNCATED ||
                        (e.kind == DownloadFailure.Kind.NETWORK && e.net?.kind in TRANSIENT)
                    if (!transient) throw e
                    // Only real progress earns a fresh retry budget, and there is a hard cap overall,
                    // so a link that trickles a few KB per attempt cannot keep this running for hours.
                    if (part.length() - before >= MEANINGFUL_PROGRESS_BYTES) failuresInARow = 0
                    totalFailures++
                    if (++failuresInARow > maxAutoRetries || totalFailures > MAX_TOTAL_RETRIES) throw e
                    delay(retryDelayMs(failuresInARow))
                }
            }
            @Suppress("UNREACHABLE_CODE")
            error("unreachable")
        }

    private suspend fun attempt(req: DownloadRequest, part: File, meta: File, onProgress: (DownloadProgress) -> Unit): DownloadedFile {
        val saved = readMeta(meta)
        var resumeFrom = if (part.isFile) part.length() else 0L
        val canResume = resumeFrom > 0 && (saved?.validator != null || req.expectedSha256 != null)
        if (!canResume && part.exists()) { part.delete(); meta.delete(); resumeFrom = 0 }

        val headers = HashMap<String, String>()
        headers["Accept-Encoding"] = "identity"
        if (resumeFrom > 0) saved?.validator?.let { headers["If-Range"] = it }

        val request = HttpRequest(req.url, headers = headers, rangeStart = resumeFrom.takeIf { it > 0 })
        try {
            return http.fetch(request, req.policy) { r ->
                val ctx = currentCoroutineContext()
                var offset = resumeFrom
                val digest = MessageDigest.getInstance("SHA-256")

                when {
                    r.code == 416 -> {
                        val total = r.header("Content-Range")?.substringAfter("/")?.toLongOrNull()
                        if (total != null && total == resumeFrom && part.isFile) {
                            return@fetch finish(req, part, meta, hashOf(part), onProgress)
                        }
                        part.delete(); meta.delete()
                        throw DownloadFailure(DownloadFailure.Kind.TRUNCATED, "Resume point rejected; restarting")
                    }
                    r.code == 206 && resumeFrom > 0 -> {
                        val start = r.header("Content-Range")?.substringAfter("bytes ")?.substringBefore("-")?.trim()?.toLongOrNull()
                        if (start != resumeFrom) {
                            part.delete(); meta.delete()
                            throw DownloadFailure(DownloadFailure.Kind.TRUNCATED, "Server resumed at the wrong offset")
                        }
                        hashInto(part, digest) // re-hash what we already have
                    }
                    r.code == 200 -> {
                        // Fresh download, or the server ignored/refused the range because the file changed.
                        if (resumeFrom > 0) { part.delete(); meta.delete() }
                        offset = 0
                    }
                    else -> throw DownloadFailure(DownloadFailure.Kind.NETWORK, "Unexpected HTTP ${r.code}", NetFailure(NetFailure.Kind.HTTP_ERROR, "HTTP ${r.code}", httpCode = r.code))
                }

                val remaining = r.contentLength.takeIf { it >= 0 }
                val total: Long? = when {
                    r.code == 206 -> r.header("Content-Range")?.substringAfter("/")?.toLongOrNull() ?: remaining?.plus(offset)
                    else -> remaining
                } ?: req.expectedSize
                if (req.expectedSize != null && total != null && total != req.expectedSize) {
                    throw DownloadFailure(DownloadFailure.Kind.SIZE_MISMATCH, "Server reports $total bytes, expected ${req.expectedSize}")
                }
                checkStorage(part.parentFile!!, total, offset)

                writeMeta(meta, Meta(r.header("ETag")?.takeIf { !it.startsWith("W/") } ?: r.header("Last-Modified"), total))

                var downloaded = offset
                var lastEmit = 0L
                onProgress(DownloadProgress(downloaded, total))
                val buf = ByteArray(128 * 1024)
                val input = r.byteStream()
                FileOutputStream(part, offset > 0).use { out ->
                    while (true) {
                        ctx.ensureActive()
                        val n = try { input.read(buf) } catch (e: IOException) {
                            if (e is NetFailure) throw DownloadFailure(DownloadFailure.Kind.NETWORK, e.message ?: "network", e)
                            throw DownloadFailure(DownloadFailure.Kind.TRUNCATED, "Connection interrupted", cause = e)
                        }
                        if (n < 0) break
                        try { out.write(buf, 0, n) } catch (e: IOException) { throw diskError(e) }
                        digest.update(buf, 0, n)
                        downloaded += n
                        val now = nowMs()
                        if (now - lastEmit >= 120) { lastEmit = now; onProgress(DownloadProgress(downloaded, total)) }
                    }
                    out.fd.sync()
                }
                if (total != null && downloaded < total) {
                    throw DownloadFailure(DownloadFailure.Kind.TRUNCATED, "Connection closed after $downloaded of $total bytes")
                }
                onProgress(DownloadProgress(downloaded, total ?: downloaded))
                finish(req, part, meta, Hex.encode(digest.digest()), onProgress)
            }
        } catch (e: NetFailure) {
            throw DownloadFailure(DownloadFailure.Kind.NETWORK, e.message ?: "network", e)
        }
    }

    private fun finish(req: DownloadRequest, part: File, meta: File, sha: String, onProgress: (DownloadProgress) -> Unit): DownloadedFile {
        val size = part.length()
        if (req.expectedSize != null && size != req.expectedSize) {
            throw DownloadFailure(DownloadFailure.Kind.SIZE_MISMATCH, "Downloaded $size bytes, expected ${req.expectedSize}")
        }
        if (req.expectedSha256 != null && !req.expectedSha256.equals(sha, ignoreCase = true)) {
            throw DownloadFailure(DownloadFailure.Kind.HASH_MISMATCH, "Checksum does not match the published value")
        }
        req.destination.delete()
        if (!part.renameTo(req.destination)) {
            try { part.copyTo(req.destination, overwrite = true); part.delete() } catch (e: IOException) { throw diskError(e) }
        }
        meta.delete()
        onProgress(DownloadProgress(size, size))
        return DownloadedFile(req.destination, sha, size)
    }

    /** A finished file from an earlier session counts only if it still satisfies every published expectation. */
    private fun existingComplete(req: DownloadRequest): DownloadedFile? {
        val f = req.destination
        if (!f.isFile || f.length() == 0L) return null
        if (req.expectedSize != null && f.length() != req.expectedSize) { f.delete(); return null }
        val sha = hashOf(f)
        if (req.expectedSha256 != null && !req.expectedSha256.equals(sha, ignoreCase = true)) { f.delete(); return null }
        return DownloadedFile(f, sha, f.length())
    }

    private fun checkStorage(dir: File, total: Long?, alreadyHave: Long) {
        if (total == null) return
        val needed = (total - alreadyHave) + (total * installerStagingFactor).toLong() + safetyMarginBytes
        val free = storage.freeBytes(dir)
        if (free in 0 until needed) {
            throw DownloadFailure(DownloadFailure.Kind.INSUFFICIENT_STORAGE, "Needs ${needed / (1024 * 1024)} MB free, ${free / (1024 * 1024)} MB available")
        }
    }

    private fun diskError(e: IOException): DownloadFailure {
        val full = e.message?.contains("ENOSPC", ignoreCase = true) == true || e.message?.contains("No space", ignoreCase = true) == true
        return DownloadFailure(if (full) DownloadFailure.Kind.INSUFFICIENT_STORAGE else DownloadFailure.Kind.IO, e.message ?: "disk error", cause = e)
    }

    private fun hashOf(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        hashInto(file, md)
        return Hex.encode(md.digest())
    }

    private fun hashInto(file: File, md: MessageDigest) {
        FileInputStream(file).use { s ->
            val buf = ByteArray(128 * 1024)
            while (true) { val n = s.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        }
    }

    private data class Meta(val validator: String?, val total: Long?)

    private fun readMeta(f: File): Meta? = try {
        if (!f.isFile) null else f.readText().split('\n').let { Meta(it.getOrNull(0)?.ifEmpty { null }, it.getOrNull(1)?.toLongOrNull()) }
    } catch (_: IOException) { null }

    private fun writeMeta(f: File, m: Meta) {
        try { f.writeText("${m.validator.orEmpty()}\n${m.total ?: ""}") } catch (_: IOException) { /* resume simply won't be possible */ }
    }

    private companion object {
        const val MEANINGFUL_PROGRESS_BYTES = 256L * 1024
        const val MAX_TOTAL_RETRIES = 30
        val TRANSIENT = setOf(NetFailure.Kind.OFFLINE, NetFailure.Kind.TIMEOUT, NetFailure.Kind.OTHER)
    }
}
