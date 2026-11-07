package de.majuwa.android.paper.krhnlesimagemanagement.data.tls

import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.security.auth.x500.X500Principal

/** SHA-256 of the DER encoding, the value `openssl x509 -noout -fingerprint -sha256` prints. */
internal fun X509Certificate.sha256Fingerprint(): ByteString =
    MessageDigest.getInstance("SHA-256").digest(encoded).toByteString()

/** Constant-time comparison of two fingerprints. */
internal fun ByteString.constantTimeEquals(other: ByteString): Boolean =
    MessageDigest.isEqual(toByteArray(), other.toByteArray())

/** Fingerprint as upper-case hex pairs separated by colons, for display. */
fun ByteString.groupedHex(): String =
    hex()
        .uppercase()
        .chunked(2)
        .joinToString(":")

/**
 * A server leaf certificate as shown for review. [subject] and [issuer] come from the server, so
 * they are sanitized and length-capped and must never be logged.
 */
data class ServerCertificate(
    val origin: ServerOrigin,
    val sha256: ByteString,
    val subject: String,
    val issuer: String,
    val notBeforeMillis: Long,
    val notAfterMillis: Long,
) {
    fun isValidAt(nowMillis: Long): Boolean = nowMillis in notBeforeMillis..notAfterMillis

    companion object {
        /** Maximum length of the displayed subject / issuer text. */
        const val MAX_NAME_LENGTH = 256

        fun of(
            origin: ServerOrigin,
            certificate: X509Certificate,
        ): ServerCertificate =
            ServerCertificate(
                origin = origin,
                sha256 = certificate.sha256Fingerprint(),
                subject = sanitizeName(certificate.subjectX500Principal.getName(X500Principal.RFC2253)),
                issuer = sanitizeName(certificate.issuerX500Principal.getName(X500Principal.RFC2253)),
                notBeforeMillis = certificate.notBefore.time,
                notAfterMillis = certificate.notAfter.time,
            )

        /** Removes control, format (incl. bidi) and line / paragraph separator characters and caps the length. */
        fun sanitizeName(raw: String): String {
            val builder = StringBuilder()
            var count = 0
            var index = 0
            while (index < raw.length && count < MAX_NAME_LENGTH) {
                val codePoint = raw.codePointAt(index)
                index += Character.charCount(codePoint)
                if (isDisplayable(codePoint)) {
                    builder.appendCodePoint(codePoint)
                    count++
                }
            }
            return builder.toString()
        }

        private fun isDisplayable(codePoint: Int): Boolean =
            when (Character.getType(codePoint).toByte()) {
                Character.CONTROL, Character.FORMAT, Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR -> false
                else -> true
            }
    }
}

/** A certificate was pinned for the origin but the server presented a different untrusted one. */
class CertificateChangedException(
    cause: Throwable,
) : CertificateException("Pinned certificate changed", cause)

/**
 * The tested origin presented a certificate that is neither system-trusted nor pinned. Carries the
 * captured [certificate] for review; [previousSha256] is the pin in effect when [changed].
 */
class CertificateReviewException(
    val certificate: ServerCertificate,
    val changed: Boolean,
    val previousSha256: ByteString?,
    cause: Throwable,
) : Exception("Server certificate needs review", cause)
