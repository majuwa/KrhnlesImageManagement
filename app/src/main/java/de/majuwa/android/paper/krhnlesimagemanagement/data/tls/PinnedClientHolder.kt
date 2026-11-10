package de.majuwa.android.paper.krhnlesimagemanagement.data.tls

import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Holds one client per effective pin for long-lived callers (the image loader). When the pin
 * changes (stop trusting, trust, logout, server change) the old client's pooled connections are
 * evicted and a new client is built, so a removed pin can never be bypassed by a pooled connection.
 */
class PinnedClientHolder(
    private val currentPin: () -> PinnedCertificate?,
    private val build: (PinnedCertificate?) -> OkHttpClient,
) : Call.Factory {
    private var builtFor: PinnedCertificate? = null
    private var client: OkHttpClient? = null

    /** The client for the current pin; rebuilt (old pool evicted) when the pin differs from the last one. */
    @Synchronized
    fun client(): OkHttpClient {
        val pin = currentPin()
        val existing = client
        if (existing != null && pin == builtFor) return existing
        existing?.connectionPool?.evictAll()
        return build(pin).also {
            client = it
            builtFor = pin
        }
    }

    override fun newCall(request: Request): Call = client().newCall(request)
}
