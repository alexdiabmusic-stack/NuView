package app.tvessentials.core.net

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.ConnectionSpec
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class HttpRequest(
    val url: String,
    val method: String = "GET",
    val headers: Map<String, String> = emptyMap(),
    /** Sends `Range: bytes=start-` (or `start-end`) when set. */
    val rangeStart: Long? = null,
    val rangeEnd: Long? = null,
    /** Sends `Range: bytes=-N` (the last N bytes) when set. */
    val suffixRange: Long? = null,
)

class HttpResponse internal constructor(
    val url: HttpUrl,
    val code: Int,
    val headers: Headers,
    private val body: ResponseBody?,
    val redirectChain: List<String>,
) {
    val isSuccessful: Boolean get() = code in 200..299
    fun header(name: String): String? = headers[name]
    val contentLength: Long get() = body?.contentLength() ?: -1L
    val contentType: String? get() = headers["Content-Type"]
    fun byteStream(): java.io.InputStream = body?.byteStream() ?: java.io.ByteArrayInputStream(ByteArray(0))

    /** Reads at most [maxBytes]; fails with RESPONSE_TOO_LARGE rather than buffering unbounded data. */
    fun readBytes(maxBytes: Int): ByteArray {
        val src = byteStream()
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8 * 1024)
        while (true) {
            val n = src.read(buf)
            if (n < 0) break
            if (out.size() + n > maxBytes) {
                throw NetFailure(NetFailure.Kind.RESPONSE_TOO_LARGE, "Response larger than $maxBytes bytes")
            }
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    fun readText(maxBytes: Int = 4 * 1024 * 1024): String = String(readBytes(maxBytes), Charsets.UTF_8)
}

/**
 * HTTP client wrapper that enforces the security rules for everything this app fetches:
 * HTTPS only, every redirect hop re-checked against the [HostPolicy], a bounded redirect count,
 * and no credentials ever attached. Redirects are followed manually so no hop can escape review.
 */
class SafeHttp(
    client: OkHttpClient,
    private val userAgent: String,
    private val maxRedirects: Int = 6,
) {
    private val http: OkHttpClient = client.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .connectionSpecs(listOf(ConnectionSpec.MODERN_TLS))
        .build()

    /**
     * Executes [request], following redirects that stay inside [policy], and hands the final response
     * to [block]. The response is closed afterwards; cancelling the calling coroutine aborts the
     * in-flight transfer immediately.
     */
    suspend fun <T> fetch(request: HttpRequest, policy: HostPolicy, block: suspend (HttpResponse) -> T): T {
        var url = parseAndCheck(request.url, policy)
        val chain = ArrayList<String>()
        chain += redacted(url)
        var hops = 0
        while (true) {
            val call = http.newCall(build(request, url))
            val response = try {
                call.await()
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                throw translate(e)
            }
            if (response.isRedirect) {
                val location = response.header("Location")
                response.close()
                if (++hops > maxRedirects) throw NetFailure(NetFailure.Kind.TOO_MANY_REDIRECTS, "Too many redirects")
                val next = location?.let { url.resolve(it) }
                    ?: throw NetFailure(NetFailure.Kind.MALFORMED, "Redirect without a usable Location")
                url = checkHop(next, policy)
                chain += redacted(url)
                continue
            }
            val failure = classifyFailure(response)
            if (failure != null) {
                response.close()
                throw failure
            }
            return coroutineScope {
                // If the caller is cancelled mid-transfer, kill the socket so blocking reads return.
                val watcher = launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                    try {
                        awaitCancellation()
                    } finally {
                        call.cancel()
                    }
                }
                try {
                    // Body reads block; keep them off the caller's thread so cancellation is always processed.
                    withContext(Dispatchers.IO) { block(HttpResponse(url, response.code, response.headers, response.body, chain)) }
                } catch (e: IOException) {
                    // An abort caused by our own cancellation must surface as cancellation, not as a network error.
                    currentCoroutineContext().ensureActive()
                    if (e is NetFailure) throw e
                    throw translate(e)
                } finally {
                    watcher.cancel()
                    response.close()
                }
            }
        }
    }

    private fun parseAndCheck(raw: String, policy: HostPolicy): HttpUrl {
        val url = raw.toHttpUrlOrNull() ?: throw NetFailure(NetFailure.Kind.MALFORMED, "Malformed URL")
        return checkHop(url, policy)
    }

    private fun checkHop(url: HttpUrl, policy: HostPolicy): HttpUrl {
        when (policy.check(url)) {
            null -> return url
            HostPolicy.Violation.NOT_HTTPS ->
                throw NetFailure(NetFailure.Kind.INSECURE_REDIRECT, "Refusing non-HTTPS URL to ${url.host}")
            else -> throw NetFailure(NetFailure.Kind.BLOCKED_HOST, "Host not on the reviewed list: ${url.host}")
        }
    }

    private fun build(req: HttpRequest, url: HttpUrl): Request {
        val b = Request.Builder().url(url).header("User-Agent", userAgent)
        for ((k, v) in req.headers) {
            // Credentials must never be attached by callers; enforce it here too.
            require(!k.equals("Authorization", true) && !k.equals("Cookie", true)) { "Header $k is not allowed" }
            b.header(k, v)
        }
        when {
            req.suffixRange != null -> b.header("Range", "bytes=-${req.suffixRange}")
            req.rangeStart != null ->
                b.header("Range", "bytes=${req.rangeStart}-${req.rangeEnd?.toString().orEmpty()}")
        }
        b.method(req.method, null)
        return b.build()
    }

    private fun classifyFailure(r: Response): NetFailure? {
        val code = r.code
        val challenge = r.header("cf-mitigated").equals("challenge", ignoreCase = true)
        if (challenge) {
            return NetFailure(NetFailure.Kind.BROWSER_CHECK, "Origin requires an interactive browser check", httpCode = code)
        }
        if (code in 200..299 || code == 304) return null
        if (code == 429 || (code == 403 && r.header("X-RateLimit-Remaining") == "0")) {
            val reset = r.header("X-RateLimit-Reset")?.toLongOrNull()?.times(1000)
                ?: r.header("Retry-After")?.toLongOrNull()?.let { System.currentTimeMillis() + it * 1000 }
            return NetFailure(NetFailure.Kind.RATE_LIMITED, "Rate limited", httpCode = code, retryAtMs = reset)
        }
        if (code == 404 || code == 410) return NetFailure(NetFailure.Kind.NOT_FOUND, "Not found", httpCode = code)
        if (code == 416) return null // handled by the downloader (range not satisfiable)
        return NetFailure(NetFailure.Kind.HTTP_ERROR, "HTTP $code", httpCode = code)
    }

    private fun translate(e: IOException): NetFailure = when (e) {
        is NetFailure -> e
        is UnknownHostException, is ConnectException, is NoRouteToHostException ->
            NetFailure(NetFailure.Kind.OFFLINE, "Cannot reach the server", cause = e)
        is SocketTimeoutException -> NetFailure(NetFailure.Kind.TIMEOUT, "The connection timed out", cause = e)
        is SSLException -> NetFailure(NetFailure.Kind.TLS, "Secure connection failed", cause = e)
        else -> NetFailure(NetFailure.Kind.OTHER, e.message ?: "Network error", cause = e)
    }

    /** URL for logs: scheme + host + path, never query strings (they can carry signed tokens). */
    private fun redacted(url: HttpUrl): String = "${url.scheme}://${url.host}${url.encodedPath}"
}

private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (cont.isActive) cont.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            if (cont.isActive) cont.resume(response) else response.close()
        }
    })
}
