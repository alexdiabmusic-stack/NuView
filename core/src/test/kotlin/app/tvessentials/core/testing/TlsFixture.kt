package app.tvessentials.core.testing

import app.tvessentials.core.net.HostPolicy
import app.tvessentials.core.net.SafeHttp
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import java.net.InetAddress
import java.util.concurrent.TimeUnit

/**
 * An HTTPS server that answers for several host names, plus a client that trusts only its certificate
 * and resolves every name to loopback. Production code therefore runs unmodified (real TLS, real host
 * policy); only DNS and the trust anchor are substituted.
 */
class TlsFixture(vararg hostnames: String) : AutoCloseable {
    private val names = hostnames.toList().ifEmpty { listOf("api.github.com", "github.com", "objects.githubusercontent.com", "implayer.tv", "cdn.implayer.tv", "evil.example") }
    private val held = HeldCertificate.Builder().apply { names.forEach { addSubjectAlternativeName(it) }; commonName("test") }.build()
    private val serverCerts = HandshakeCertificates.Builder().heldCertificate(held).build()
    private val clientCerts = HandshakeCertificates.Builder().addTrustedCertificate(held.certificate).build()

    // HTTP/1.1 only: MockWebServer's chunked-body emulation is HTTP/1.1 framing and corrupts payloads over HTTP/2.
    val server = MockWebServer().apply { useHttps(serverCerts.sslSocketFactory()); protocols = listOf(okhttp3.Protocol.HTTP_1_1); start() }
    val port: Int get() = server.port

    val client: OkHttpClient = OkHttpClient.Builder()
        .sslSocketFactory(clientCerts.sslSocketFactory(), clientCerts.trustManager)
        .dns(Dns { listOf(InetAddress.getByName("127.0.0.1")) })
        .proxy(java.net.Proxy.NO_PROXY) // CI sandboxes may set JVM-wide HTTPS proxies; test traffic is loopback-only
        .readTimeout(5, TimeUnit.SECONDS)
        .connectTimeout(5, TimeUnit.SECONDS)
        .build()

    val http = SafeHttp(client, userAgent = "TvEssentialsTest/1")

    fun url(host: String, path: String) = "https://$host:$port$path"
    fun policy(vararg patterns: String) = HostPolicy(patterns.toList(), setOf(port))

    fun dispatch(block: (RecordedRequest) -> MockResponse) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = block(request)
        }
    }

    override fun close() { server.close() }
}

fun response(code: Int = 200, body: String = "", headers: Map<String, String> = emptyMap()): MockResponse =
    MockResponse.Builder().code(code).body(body).apply { headers.forEach { (k, v) -> addHeader(k, v) } }.build()

fun bytesResponse(code: Int, bytes: ByteArray, headers: Map<String, String> = emptyMap()): MockResponse {
    val buf = okio.Buffer().write(bytes)
    return MockResponse.Builder().code(code).body(buf).apply { headers.forEach { (k, v) -> addHeader(k, v) } }.build()
}

/** A Range-aware server for one blob (MockWebServer does not implement ranges by itself). */
fun rangeDispatch(blob: ByteArray, etag: String = "\"v1\"", ignoreRange: Boolean = false, onRequest: (RecordedRequest) -> Unit = {}): (RecordedRequest) -> MockResponse = { req ->
    onRequest(req)
    val range = req.headers["Range"]
    val ifRange = req.headers["If-Range"]
    val base = mapOf("ETag" to etag, "Accept-Ranges" to "bytes", "Content-Type" to "application/vnd.android.package-archive")
    if (range == null || ignoreRange || (ifRange != null && ifRange != etag)) {
        bytesResponse(200, blob, base)
    } else {
        val spec = range.removePrefix("bytes=")
        val (s, e) = if (spec.startsWith("-")) {
            val n = spec.drop(1).toLong(); (blob.size - n).coerceAtLeast(0) to (blob.size - 1L)
        } else {
            val parts = spec.split("-")
            parts[0].toLong() to (parts.getOrNull(1)?.takeIf { it.isNotEmpty() }?.toLong() ?: (blob.size - 1L))
        }
        if (s >= blob.size) {
            response(416, "", base + ("Content-Range" to "bytes */${blob.size}"))
        } else {
            val end = minOf(e, blob.size - 1L)
            bytesResponse(206, blob.copyOfRange(s.toInt(), (end + 1).toInt()), base + ("Content-Range" to "bytes $s-$end/${blob.size}"))
        }
    }
}
