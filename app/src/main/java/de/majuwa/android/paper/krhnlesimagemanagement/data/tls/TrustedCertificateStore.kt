package de.majuwa.android.paper.krhnlesimagemanagement.data.tls

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withTimeoutOrNull
import okio.ByteString.Companion.decodeHex
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

internal const val TRUSTED_CERTIFICATES_DATASTORE_NAME = "trusted_certificates"

// An unreadable file means no pin: self-signed connections fail until the user trusts again (fail closed).
internal val trustedCertificatesCorruptionHandler = ReplaceFileCorruptionHandler<Preferences> { emptyPreferences() }

private val Context.trustedCertificatesDataStore: DataStore<Preferences> by preferencesDataStore(
    name = TRUSTED_CERTIFICATES_DATASTORE_NAME,
    corruptionHandler = trustedCertificatesCorruptionHandler,
)

/** The certificate the user trusted, with the details shown at review. */
data class TrustedCertificateInfo(
    val certificate: ServerCertificate,
    val trustedAtMillis: Long,
) {
    val pin: PinnedCertificate get() = PinnedCertificate(certificate.origin, certificate.sha256)
}

/** Persists at most one trusted (pinned) server certificate. */
interface TrustedCertificateRepository {
    /** The stored record, or `null` (none, unreadable or invalid). */
    val trustedCertificate: Flow<TrustedCertificateInfo?>

    /** Trusts [certificate] for its origin, replacing any previous pin. */
    suspend fun trust(certificate: ServerCertificate)

    suspend fun clear()
}

/**
 * The pin for [serverUrl]: the stored one if (and only if) its origin equals the configured `https`
 * origin; `null` otherwise (no record, other origin, non-https or invalid URL).
 */
fun TrustedCertificateInfo?.pinFor(serverUrl: String): PinnedCertificate? {
    val info = this ?: return null
    val origin = ServerOrigin.of(serverUrl) ?: return null
    return info.pin.takeIf { it.origin == origin }
}

/**
 * The pin for [serverUrl] read once with a bound: a timeout or failure yields `null` (system trust
 * only, fail closed); cancellation is rethrown.
 */
suspend fun TrustedCertificateRepository.pinFor(
    serverUrl: String,
    timeoutMillis: Long = PIN_READ_TIMEOUT_MS,
): PinnedCertificate? =
    try {
        withTimeoutOrNull(timeoutMillis) { trustedCertificate.first() }.pinFor(serverUrl)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w("TrustedCertificate", "Reading the pin failed: ${e.javaClass.simpleName}")
        null
    }

/** Upper bound for reading the pin before a run, entry or check. */
const val PIN_READ_TIMEOUT_MS = 3_000L

class TrustedCertificateStore internal constructor(
    private val dataStore: DataStore<Preferences>,
) : TrustedCertificateRepository {
    /** Production constructor using the process-wide DataStore (the internal one is for tests). */
    constructor(context: Context) : this(context.applicationContext.trustedCertificatesDataStore)

    override val trustedCertificate: Flow<TrustedCertificateInfo?> =
        dataStore.data
            .map { prefs -> prefs[KEY_RECORD]?.let(::parseTrustedCertificate) }
            // An unreadable file means no pin (fail closed: system trust only).
            .catch { e ->
                if (e !is IOException) throw e
                Log.w("TrustedCertificate", "Reading the pin failed: ${e.javaClass.simpleName}")
                emit(null)
            }

    override suspend fun trust(certificate: ServerCertificate) {
        val record = TrustedCertificateInfo(certificate, System.currentTimeMillis())
        dataStore.edit { it[KEY_RECORD] = record.toJson().toString() }
    }

    override suspend fun clear() {
        dataStore.edit { it.remove(KEY_RECORD) }
    }

    private companion object {
        val KEY_RECORD = stringPreferencesKey("trusted_certificate")
    }
}

private fun TrustedCertificateInfo.toJson(): JSONObject =
    JSONObject()
        .put("origin", certificate.origin.serialize())
        .put("sha256", certificate.sha256.hex())
        .put("subject", certificate.subject)
        .put("issuer", certificate.issuer)
        .put("notBefore", certificate.notBeforeMillis)
        .put("notAfter", certificate.notAfterMillis)
        .put("trustedAt", trustedAtMillis)

/** Strict parse: any missing, wrong-typed or invalid field means no pin. */
internal fun parseTrustedCertificate(json: String): TrustedCertificateInfo? =
    try {
        parseRecord(JSONObject(json))
    } catch (_: JSONException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

private fun parseRecord(obj: JSONObject): TrustedCertificateInfo? {
    val origin = (obj.opt("origin") as? String)?.let(ServerOrigin::parse) ?: return null
    val hex = obj.opt("sha256") as? String ?: return null
    if (hex.length != SHA256_HEX_LENGTH || hex != hex.lowercase()) return null
    val certificate =
        ServerCertificate(
            origin = origin,
            sha256 = hex.decodeHex(),
            subject = obj.opt("subject") as? String ?: return null,
            issuer = obj.opt("issuer") as? String ?: return null,
            notBeforeMillis = obj.opt("notBefore").asIntegralLong() ?: return null,
            notAfterMillis = obj.opt("notAfter").asIntegralLong() ?: return null,
        )
    return TrustedCertificateInfo(certificate, obj.opt("trustedAt").asIntegralLong() ?: return null)
}

private const val SHA256_HEX_LENGTH = 64

/** org.json yields `Int` for small numbers and `Long` for large ones; anything else is not a timestamp. */
private fun Any?.asIntegralLong(): Long? =
    when (this) {
        is Int -> toLong()
        is Long -> this
        else -> null
    }
