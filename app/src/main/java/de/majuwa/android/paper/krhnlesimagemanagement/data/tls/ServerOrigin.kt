package de.majuwa.android.paper.krhnlesimagemanagement.data.tls

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okio.ByteString

/**
 * The `https` origin (normalized host and effective port) a certificate is bound to. Built only
 * from an `https` [HttpUrl], so host normalization (lower case, IDN to ASCII, IPv6 without
 * brackets) is the one used for the credential check `hasSameOriginAs()` as well.
 */
data class ServerOrigin(
    val host: String,
    val port: Int,
) {
    /** True if a connection to [peerHost] : [peerPort] (as reported by the TLS session) is this origin. */
    fun matches(
        peerHost: String?,
        peerPort: Int,
    ): Boolean {
        val normalized = peerHost?.let(::normalizeHost) ?: return false
        return peerPort == port && normalized == host
    }

    /** Stable text form for storage: `host:port`. */
    fun serialize(): String = if (':' in host) "[$host]:$port" else "$host:$port"

    companion object {
        /** The origin of an `https` URL, or `null` for any other scheme. */
        fun of(url: HttpUrl): ServerOrigin? = if (url.isHttps) ServerOrigin(url.host, url.port) else null

        /** The origin of an `https` URL string, or `null` if it is not a valid `https` URL. */
        fun of(url: String): ServerOrigin? = url.trim().toHttpUrlOrNull()?.let(::of)

        /** Inverse of [serialize]; `null` for anything that is not a normalized `host:port`. */
        fun parse(text: String): ServerOrigin? {
            val separator = text.lastIndexOf(':')
            if (separator <= 0) return null
            val port = text.substring(separator + 1).toIntOrNull() ?: return null
            if (port !in PORT_RANGE) return null
            val rawHost = text.substring(0, separator).removeSurrounding("[", "]")
            val host = normalizeHost(rawHost) ?: return null
            return ServerOrigin(host, port).takeIf { it.serialize() == text }
        }

        private val PORT_RANGE = 1..65_535
    }
}

/** Host as `HttpUrl` normalizes it (lower case, punycode, IPv6 without brackets), or `null` if invalid. */
internal fun normalizeHost(host: String): String? =
    runCatching {
        HttpUrl
            .Builder()
            .scheme("https")
            .host(host)
            .build()
            .host
    }.getOrNull()

/** A leaf certificate (SHA-256 of its DER encoding) trusted for exactly one [origin]. */
data class PinnedCertificate(
    val origin: ServerOrigin,
    val sha256: ByteString,
)
