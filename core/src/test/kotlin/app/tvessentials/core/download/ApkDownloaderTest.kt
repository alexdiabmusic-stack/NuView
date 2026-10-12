package app.tvessentials.core.download

import app.tvessentials.core.install.StorageGateway
import app.tvessentials.core.testing.TlsFixture
import app.tvessentials.core.testing.rangeDispatch
import app.tvessentials.core.testing.response
import app.tvessentials.core.net.SafeHttp
import app.tvessentials.core.util.Digests
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import okhttp3.Interceptor
import okhttp3.Response
import okhttp3.ResponseBody.Companion.asResponseBody
import okio.Buffer
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ApkDownloaderTest {
    private val fx = TlsFixture()
    @get:Rule val tmp = TemporaryFolder()
    @AfterTest fun tearDown() = fx.close()

    private val blob = ByteArray(1_000_000) { (it * 31 + 7).toByte() }
    private val blobSha = Digests.sha256(blob)
    private val roomy = StorageGateway { Long.MAX_VALUE / 4 }
    private val noWait = { _: Int -> 0L }

    private fun downloader(http: SafeHttp = fx.http, storage: StorageGateway = roomy, retries: Int = 3) =
        ApkDownloader(http, storage, maxAutoRetries = retries, retryDelayMs = noWait, safetyMarginBytes = 0)

    private fun request(dest: File, size: Long? = blob.size.toLong(), sha: String? = blobSha) =
        DownloadRequest(fx.url("github.com", "/x.apk"), dest, size, sha, fx.policy("github.com"))

    private fun dest() = File(tmp.root, "dl/app__1.0__x.apk")

    @Test fun `downloads, verifies and publishes atomically`() = runBlocking {
        fx.dispatch(rangeDispatch(blob))
        val progress = mutableListOf<DownloadProgress>()
        val done = downloader().download(request(dest())) { progress += it }
        assertEquals(blobSha, done.sha256)
        assertEquals(blob.size.toLong(), done.file.length())
        assertFalse(File(dest().path + ".part").exists())
        assertFalse(File(dest().path + ".part.meta").exists())
        assertTrue(progress.zipWithNext().all { (a, b) -> b.downloadedBytes >= a.downloadedBytes }, "progress must not go backwards")
        assertEquals(blob.size.toLong(), progress.last().downloadedBytes)
        assertEquals(blob.size.toLong(), progress.last().totalBytes)
    }

    @Test fun `a checksum that differs from the published one is rejected and nothing is published`() = runBlocking {
        fx.dispatch(rangeDispatch(blob))
        val e = assertFailsWith<DownloadFailure> { downloader().download(request(dest(), sha = "0".repeat(64))) {} }
        assertEquals(DownloadFailure.Kind.HASH_MISMATCH, e.kind)
        assertFalse(dest().exists())
        assertFalse(File(dest().path + ".part").exists())
    }

    @Test fun `a file of the wrong size is rejected`() = runBlocking {
        fx.dispatch(rangeDispatch(blob))
        val e = assertFailsWith<DownloadFailure> { downloader().download(request(dest(), size = blob.size + 10L, sha = null)) {} }
        assertEquals(DownloadFailure.Kind.SIZE_MISMATCH, e.kind)
        assertFalse(dest().exists())
    }

    /** Cuts the response body after [limit] bytes for the first [times] calls, like a dropped Wi-Fi link. */
    private fun cutting(limit: Long, times: Int): SafeHttp {
        val remaining = AtomicInteger(times)
        val client = fx.client.newBuilder().addNetworkInterceptor(Interceptor { chain ->
            val r: Response = chain.proceed(chain.request())
            if (remaining.get() > 0 && r.code in 200..206) {
                remaining.decrementAndGet()
                val real = r.body.source()
                val failing = object : Source {
                    var read = 0L
                    override fun read(sink: Buffer, byteCount: Long): Long {
                        if (read >= limit) throw IOException("simulated connection drop")
                        val n = real.read(sink, minOf(byteCount, limit - read))
                        if (n > 0) read += n
                        return n
                    }
                    override fun timeout(): Timeout = real.timeout()
                    override fun close() = real.close()
                }
                r.newBuilder().body(failing.buffer().asResponseBody(r.body.contentType(), r.body.contentLength())).build()
            } else r
        }).build()
        return SafeHttp(client, "TvEssentialsTest/1")
    }

    @Test fun `an interrupted transfer resumes from where it stopped and the result still verifies`() = runBlocking {
        val ranges = mutableListOf<String?>()
        fx.dispatch(rangeDispatch(blob) { ranges += it.headers["Range"] })
        val done = downloader(cutting(limit = 300_000, times = 1)).download(request(dest())) {}
        assertEquals(blobSha, done.sha256)
        assertEquals(2, ranges.size)
        assertEquals(null, ranges[0])
        assertTrue(ranges[1]!!.startsWith("bytes=") && ranges[1]!!.endsWith("-"), "second request should resume: ${ranges[1]}")
        val from = ranges[1]!!.removePrefix("bytes=").removeSuffix("-").toLong()
        assertTrue(from in 1 until blob.size, "resume offset $from")
    }

    @Test fun `a link that only trickles a few KB per attempt gives up instead of retrying for hours`() = runBlocking {
        fx.dispatch(rangeDispatch(blob))
        val e = assertFailsWith<DownloadFailure> { downloader(cutting(limit = 10_000, times = 99), retries = 2).download(request(dest())) {} }
        assertTrue(e.kind == DownloadFailure.Kind.TRUNCATED || e.kind == DownloadFailure.Kind.NETWORK)
        assertFalse(dest().exists())
        assertTrue(File(dest().path + ".part").length() in 1 until blob.size, "the partial data is kept for a manual retry")
    }

    @Test fun `a link that keeps making real progress is allowed to keep resuming`() = runBlocking {
        fx.dispatch(rangeDispatch(blob))
        val done = downloader(cutting(limit = 300_000, times = 3), retries = 1).download(request(dest())) {}
        assertEquals(blobSha, done.sha256)
    }

    @Test fun `if the file changed on the server the old partial data is discarded`() = runBlocking {
        val v2 = ByteArray(blob.size) { (it * 13 + 1).toByte() }
        // Stage 1 leaves a partial from v1 behind...
        fx.dispatch(rangeDispatch(blob, etag = "\"v1\""))
        assertFailsWith<DownloadFailure> { downloader(cutting(200_000, 99), retries = 0).download(request(dest(), sha = null)) {} }
        assertTrue(File(dest().path + ".part").length() > 0)
        // ...stage 2: the server now has v2 with a new ETag; If-Range makes it answer 200 with the full new file.
        fx.dispatch(rangeDispatch(v2, etag = "\"v2\""))
        val done = downloader().download(request(dest(), sha = Digests.sha256(v2))) {}
        assertEquals(Digests.sha256(v2), done.sha256)
        assertNotEquals(blobSha, done.sha256)
    }

    @Test fun `a server that ignores range requests still completes from scratch`() = runBlocking {
        fx.dispatch(rangeDispatch(blob, ignoreRange = true))
        assertFailsWith<DownloadFailure> { downloader(cutting(150_000, 99), retries = 0).download(request(dest())) {} }
        val done = downloader().download(request(dest())) {}
        assertEquals(blobSha, done.sha256)
    }

    @Test fun `not enough free space fails before a single byte is written`() = runBlocking {
        fx.dispatch(rangeDispatch(blob))
        val e = assertFailsWith<DownloadFailure> { downloader(storage = StorageGateway { 500_000L }).download(request(dest())) {} }
        assertEquals(DownloadFailure.Kind.INSUFFICIENT_STORAGE, e.kind)
        assertFalse(File(dest().path + ".part").let { it.exists() && it.length() > 0 })
    }

    @Test fun `an unknown content length reports indeterminate progress and still completes`() = runBlocking {
        fx.dispatch { MockResponse.Builder().code(200).chunkedBody(Buffer().write(blob), 8192).build() }
        val progress = mutableListOf<DownloadProgress>()
        val done = downloader().download(request(dest(), size = null, sha = null)) { progress += it }
        assertEquals(blobSha, done.sha256)
        assertTrue(progress.first().totalBytes == null, "total must be unknown while streaming")
    }

    @Test fun `cancelling keeps the partial file so the next attempt resumes`() = runBlocking {
        fx.dispatch { MockResponse.Builder().code(200).body(Buffer().write(blob)).addHeader("ETag", "\"v1\"").throttleBody(60_000, 100, TimeUnit.MILLISECONDS).build() }
        val job = launch(start = CoroutineStart.UNDISPATCHED) { downloader().download(request(dest(), sha = null, size = null)) {} }
        delay(450)
        withTimeout(3_000) { job.cancelAndJoin() }
        val partial = File(dest().path + ".part")
        assertTrue(partial.length() in 1 until blob.size, "partial=${partial.length()}")
        assertFalse(dest().exists())
        // Second attempt resumes and finishes against a full-speed server.
        val ranges = mutableListOf<String?>()
        fx.dispatch(rangeDispatch(blob) { ranges += it.headers["Range"] })
        // The first server response set no validator that rangeDispatch recognises as "v1" only if etag matches:
        val done = downloader().download(request(dest(), sha = blobSha)) {}
        assertEquals(blobSha, done.sha256)
        assertTrue(ranges.isNotEmpty())
    }

    @Test fun `a complete file from an earlier session is reused only if it still matches`() = runBlocking {
        val d = dest().also { it.parentFile.mkdirs(); it.writeBytes(blob) }
        var hits = 0
        fx.dispatch { hits++; response(500) }
        val done = downloader().download(request(d)) {}
        assertEquals(0, hits, "a valid cached file must not be downloaded again")
        assertEquals(blobSha, done.sha256)
        d.writeBytes(blob.copyOf(blob.size - 5)) // corrupted since
        fx.dispatch(rangeDispatch(blob))
        assertEquals(blobSha, downloader().download(request(d)) {}.sha256)
    }

    @Test fun `downloads only ever touch allowed hosts`() = runBlocking {
        fx.dispatch { response(302, headers = mapOf("Location" to fx.url("evil.example", "/x.apk"))) }
        val e = assertFailsWith<DownloadFailure> { downloader().download(request(dest())) {} }
        assertEquals(DownloadFailure.Kind.NETWORK, e.kind)
        assertEquals(app.tvessentials.core.net.NetFailure.Kind.BLOCKED_HOST, e.net?.kind)
    }
}
