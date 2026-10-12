package app.tvessentials.core.net

import java.io.IOException

/** Every way a network operation can fail, in terms the UI can explain in plain language. */
class NetFailure(
    val kind: Kind,
    message: String,
    val httpCode: Int? = null,
    /** Epoch millis when a rate limit lifts, if the server said so. */
    val retryAtMs: Long? = null,
    cause: Throwable? = null,
) : IOException(message, cause) {

    enum class Kind {
        /** No route / DNS failure: the device or network is offline. */
        OFFLINE,
        TIMEOUT,
        TLS,

        /** A request or redirect pointed outside the reviewed host list. */
        BLOCKED_HOST,
        INSECURE_REDIRECT,
        TOO_MANY_REDIRECTS,
        RATE_LIMITED,

        /** The origin answered with a bot/browser check instead of the file. Never bypassed. */
        BROWSER_CHECK,
        NOT_FOUND,
        HTTP_ERROR,
        RESPONSE_TOO_LARGE,
        MALFORMED,
        OTHER,
    }
}
