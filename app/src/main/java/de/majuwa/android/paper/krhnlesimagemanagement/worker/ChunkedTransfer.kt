package de.majuwa.android.paper.krhnlesimagemanagement.worker

import de.majuwa.android.paper.krhnlesimagemanagement.data.MAX_CHUNK_NUMBER
import de.majuwa.android.paper.krhnlesimagemanagement.data.isSha256Hex
import de.majuwa.android.paper.krhnlesimagemanagement.data.isValidTransferId
import org.json.JSONObject
import java.security.MessageDigest

private const val BYTES_PER_MIB = 1024L * 1024L

private const val KEY_TRANSFER_ID = "transferId"
private const val KEY_CHUNK_BYTES = "chunkBytes"
private const val KEY_TOTAL_BYTES = "totalBytes"
private const val KEY_CONFIRMED_CHUNKS = "confirmedChunks"
private const val KEY_PREFIX_SHA256 = "prefixSha256"
private const val KEY_ORIGINAL = "original"
private const val KEY_BINDING = "binding"

/**
 * When and how a file is uploaded in chunks (issue #69): files of at least [thresholdBytes] on a
 * Nextcloud files URL, in chunks of [chunkBytes], at most [maxChunks] per file. The defaults are
 * 50 MiB / 10 MiB / 10,000 (the highest chunk number Nextcloud accepts); tests inject tiny values.
 */
internal data class ChunkedUploadPolicy(
    val thresholdBytes: Long = DEFAULT_THRESHOLD_BYTES,
    val chunkBytes: Long = DEFAULT_CHUNK_BYTES,
    val maxChunks: Int = MAX_CHUNK_NUMBER,
) {
    /** True if a file of the known [length] is uploaded in chunks (an unknown length never is). */
    fun isChunked(length: Long): Boolean = length > 0L && length >= thresholdBytes

    /**
     * Chunk size of a transfer of [total] bytes: [chunkBytes], or, for a file that would need more
     * than [maxChunks] chunks, the smallest whole number of MiB that keeps the count within it.
     */
    fun chunkBytesFor(total: Long): Long {
        val needed = (total + maxChunks - 1) / maxChunks
        return if (needed <= chunkBytes) chunkBytes else (needed + BYTES_PER_MIB - 1) / BYTES_PER_MIB * BYTES_PER_MIB
    }

    companion object {
        const val DEFAULT_THRESHOLD_BYTES = 50L * BYTES_PER_MIB
        const val DEFAULT_CHUNK_BYTES = 10L * BYTES_PER_MIB
    }
}

/** Number of chunks of [total] bytes in chunks of [chunkBytes]. */
internal fun chunkCountOf(
    total: Long,
    chunkBytes: Long,
): Int = ((total + chunkBytes - 1) / chunkBytes).toInt()

/**
 * Where a chunked transfer stands, persisted in the queue file entry (`chunked` object) after every
 * confirmed chunk. [prefixSha256] is the SHA-256 of the first `min(confirmedChunks * chunkBytes,
 * totalBytes)` bytes (of empty input for 0); [binding] ties the transfer to its server, account and
 * destination ([chunkedBinding]) without storing the URLs.
 */
internal data class ChunkedTransferState(
    val transferId: String,
    val chunkBytes: Long,
    val totalBytes: Long,
    val confirmedChunks: Int,
    val prefixSha256: String,
    val original: Boolean,
    val binding: String,
) {
    val chunkCount: Int get() = chunkCountOf(totalBytes, chunkBytes)

    /** Bytes of the confirmed prefix. */
    val confirmedBytes: Long get() = minOf(confirmedChunks.toLong() * chunkBytes, totalBytes)

    fun toJson(): JSONObject =
        JSONObject()
            .put(KEY_TRANSFER_ID, transferId)
            .put(KEY_CHUNK_BYTES, chunkBytes)
            .put(KEY_TOTAL_BYTES, totalBytes)
            .put(KEY_CONFIRMED_CHUNKS, confirmedChunks)
            .put(KEY_PREFIX_SHA256, prefixSha256)
            .put(KEY_ORIGINAL, original)
            .put(KEY_BINDING, binding)

    companion object {
        /**
         * Reads the `chunked` value of a queue entry. Strict (`opt` + type and range checks); anything
         * invalid gives `null`: the entry is then uploaded from the start. Progress is an
         * optimization, so unlike a wrong folder it never makes the whole queue malformed.
         */
        fun fromJson(value: Any?): ChunkedTransferState? {
            val obj = value as? JSONObject ?: return null
            val state =
                ChunkedTransferState(
                    transferId = obj.opt(KEY_TRANSFER_ID) as? String ?: "",
                    chunkBytes = obj.opt(KEY_CHUNK_BYTES).asWholeNumber(),
                    totalBytes = obj.opt(KEY_TOTAL_BYTES).asWholeNumber(),
                    confirmedChunks =
                        obj
                            .opt(
                                KEY_CONFIRMED_CHUNKS,
                            ).asWholeNumber()
                            .coerceAtMost(Int.MAX_VALUE.toLong())
                            .toInt(),
                    prefixSha256 = (obj.opt(KEY_PREFIX_SHA256) as? String).orEmpty().lowercase(),
                    original = obj.opt(KEY_ORIGINAL) == true,
                    binding = (obj.opt(KEY_BINDING) as? String).orEmpty().lowercase(),
                )
            return state.takeIf { obj.opt(KEY_ORIGINAL) is Boolean && it.isConsistent() }
        }

        /** The value of an `Int` / `Long` JSON number; anything else (string, fraction, missing) is -1. */
        private fun Any?.asWholeNumber(): Long = if (this is Int || this is Long) (this as Number).toLong() else -1L
    }

    /** True if every field is in range: a valid id, positive sizes, at most the real chunk count, hex hashes. */
    private fun isConsistent(): Boolean =
        isValidTransferId(transferId) &&
            chunkBytes > 0L &&
            totalBytes > 0L &&
            chunkCount <= MAX_CHUNK_NUMBER &&
            confirmedChunks in 0..chunkCount &&
            isSha256Hex(prefixSha256) &&
            isSha256Hex(binding)
}

/**
 * SHA-256 (lower-case hex) of the uploads collection URL and the destination URL: a saved transfer
 * is resumed only while both are unchanged (other server, account, base folder or file name = a
 * different binding).
 */
internal fun chunkedBinding(
    collectionUrl: String,
    destinationUrl: String,
): String =
    MessageDigest
        .getInstance("SHA-256")
        .digest("$collectionUrl\n$destinationUrl".toByteArray(Charsets.UTF_8))
        .toHexString()
