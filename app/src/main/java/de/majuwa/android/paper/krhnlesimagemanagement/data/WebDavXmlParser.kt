package de.majuwa.android.paper.krhnlesimagemanagement.data

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.UnsupportedEncodingException

internal const val DAV_NS = "DAV:"
internal const val OC_NS = "http://owncloud.org/ns"

internal data class WebDavEntry(
    val href: String,
    val displayName: String,
    val isDirectory: Boolean,
    val contentType: String,
    val fileId: String?,
)

/** One `d:response` of a PROPFIND multistatus, as yielded by [PropfindEntryReader]. */
internal sealed interface PropfindItem {
    data class Entry(
        val entry: WebDavEntry,
    ) : PropfindItem

    /** The entry exceeded a [PropfindEntryLimits] bound and was skipped (its content is not retained). */
    data object Skipped : PropfindItem
}

/**
 * Bounds of [PropfindEntryReader]. Exceeding a per-entry bound skips that entry; exceeding a
 * document bound fails the whole response with [MalformedMultistatusException] (no legitimate
 * WebDAV server comes close to them, and KXml's arrays grow linearly, so unbounded nesting,
 * attributes or namespace declarations would cost quadratic time).
 */
internal data class PropfindEntryLimits(
    /** Elements inside one `d:response` (excluding the `d:response` element itself). */
    val maxElementsPerEntry: Int = 256,
    /** Nesting levels below `d:response`. */
    val maxEntryDepth: Int = 16,
    /** Characters of one captured value (`d:href`, `d:displayname`, `d:getcontenttype`, `oc:fileid`). */
    val maxTextLength: Int = 8 * 1024,
    /** Document bound: absolute element depth (`d:multistatus` is 1). */
    val maxDocumentDepth: Int = 32,
    /** Document bound: attributes of one element (namespace declarations excluded). */
    val maxAttributesPerElement: Int = 64,
    /** Document bound: namespace declarations of one element. */
    val maxNamespacesPerElement: Int = 64,
    /**
     * Document bound: bytes between two markup starts (`<` outside CDATA, comments and processing
     * instructions), i.e. the raw size of one tag with all its attributes, or of one text node.
     * Enforced on the byte stream (see [XmlTokenSizeLimitingInputStream]) *before* KXml buffers it.
     * A maximal captured value ([maxTextLength] characters of 4-byte UTF-8) still fits.
     */
    val maxTokenBytes: Int = 64 * 1024,
)

/** The PROPFIND response is not a well-formed, DOCTYPE-free `{DAV:}multistatus` within the document bounds. */
class MalformedMultistatusException(
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

/** The PROPFIND response body exceeded the byte cap of [LimitedInputStream]. */
class PropfindResponseTooLargeException : IOException("PROPFIND response exceeds the size limit")

/**
 * Streaming reader of a PROPFIND multistatus (issues #27 / #46): [next] yields one `d:response`
 * at a time, so memory is bounded by the caller's retained entries, not by the XML size.
 *
 * XXE defense: a stream cannot be pre-sanitized, so the prolog is read with `nextToken()` and any
 * `DOCDECL` (DOCTYPE, also a benign one) or `ENTITY_REF` fails with [MalformedMultistatusException];
 * `FEATURE_PROCESS_DOCDECL` is off as defense in depth and the root must be `{DAV:}multistatus`.
 * KXml rejects a DOCTYPE after the root start tag, so no entity can ever be declared; the parser
 * rejects undeclared entity references (only the XML built-ins and character references are accepted).
 *
 * Parse-cost defense: the document bounds of [PropfindEntryLimits] are checked on every start tag
 * (depth, attributes, namespace declarations) and on the byte stream (token size), so a hostile
 * body fails fast instead of costing quadratic time or one huge text buffer.
 *
 * Uses `android.util.Xml.newPullParser()` only, so unit tests (Robolectric) run the production parser.
 * The parser is created lazily inside [next] (KXml already reads in `setInput`), so every failure
 * is classified the same way: stream [IOException]s (timeouts, `Call.cancel()`,
 * [PropfindResponseTooLargeException]) propagate unchanged — also when KXml wraps them — and
 * everything else becomes [MalformedMultistatusException]. Exception messages may contain server
 * content: callers log the exception type only.
 */
internal class PropfindEntryReader(
    input: InputStream,
    private val limits: PropfindEntryLimits = PropfindEntryLimits(),
) {
    private val input: InputStream = XmlTokenSizeLimitingInputStream(input, limits.maxTokenBytes)
    private lateinit var parser: XmlPullParser
    private var started = false
    private var finished = false

    /** The next entry, [PropfindItem.Skipped] for an oversized one, or `null` after the root element. */
    fun next(): PropfindItem? =
        try {
            if (!started) {
                parser = newParser()
                readProlog()
                started = true
            }
            if (finished) null else nextResponse()
        } catch (e: XmlPullParserException) {
            throw e.toStreamFailure()
        }

    private fun newParser(): XmlPullParser =
        Xml.newPullParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
            setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false)
            setInput(input, null) // reads the first bytes: must run inside next()'s try
        }

    private fun readProlog() {
        while (true) {
            val problem =
                when (parser.nextToken()) {
                    XmlPullParser.DOCDECL, XmlPullParser.ENTITY_REF -> {
                        "Document type declarations are not allowed"
                    }

                    XmlPullParser.START_TAG -> {
                        checkStartTag()
                        if (parser.namespace == DAV_NS && parser.name == "multistatus") return
                        "Root element is not a DAV multistatus"
                    }

                    XmlPullParser.END_DOCUMENT -> {
                        "Empty PROPFIND response"
                    }

                    else -> {
                        null // XML declaration, processing instructions, comments, whitespace
                    }
                }
            if (problem != null) throw MalformedMultistatusException(problem)
        }
    }

    /** Enforces the document bounds of [PropfindEntryLimits] on the current start tag. */
    private fun checkStartTag() {
        val depth = parser.depth
        val namespaces = parser.getNamespaceCount(depth) - parser.getNamespaceCount(depth - 1)
        val problem =
            when {
                depth > limits.maxDocumentDepth -> "PROPFIND XML nested too deeply"
                parser.attributeCount > limits.maxAttributesPerElement -> "Too many attributes in PROPFIND XML"
                namespaces > limits.maxNamespacesPerElement -> "Too many namespace declarations in PROPFIND XML"
                else -> null
            }
        if (problem != null) throw MalformedMultistatusException(problem)
    }

    private fun nextResponse(): PropfindItem? {
        while (true) {
            when (parser.next()) {
                XmlPullParser.START_TAG -> {
                    checkStartTag()
                    val item = if (isResponseStart()) readResponse() else skipElement()
                    if (item != null) return item
                }

                XmlPullParser.END_TAG -> {
                    // Only the root's END_TAG is seen here (children are consumed completely).
                    finished = true
                    return null
                }

                XmlPullParser.END_DOCUMENT -> {
                    throw MalformedMultistatusException("Unexpected end of PROPFIND response")
                }

                else -> {
                    // text / comments between elements are ignored
                }
            }
        }
    }

    private fun isResponseStart(): Boolean =
        parser.depth == RESPONSE_DEPTH && parser.namespace == DAV_NS && parser.name == "response"

    /** Consumes the current element and its content without retaining anything. */
    private fun skipElement(): PropfindItem? {
        val depth = parser.depth
        while (true) {
            when (parser.next()) {
                XmlPullParser.START_TAG -> checkStartTag()
                XmlPullParser.END_TAG -> if (parser.depth == depth) return null
                XmlPullParser.END_DOCUMENT -> throw MalformedMultistatusException("Unexpected end of PROPFIND response")
                else -> Unit
            }
        }
    }

    /** Reads the current `d:response`; `null` when it has no `d:href` (skipped as before). */
    private fun readResponse(): PropfindItem? {
        val collector = ResponseCollector(limits)
        while (true) {
            when (parser.next()) {
                XmlPullParser.START_TAG -> {
                    checkStartTag()
                    collector.onStart(parser.namespace, parser.name, parser.depth - RESPONSE_DEPTH)
                }

                XmlPullParser.TEXT -> {
                    collector.onText(parser.text)
                }

                XmlPullParser.END_TAG -> {
                    if (parser.depth == RESPONSE_DEPTH) return collector.result()
                    collector.onEnd(parser.depth - RESPONSE_DEPTH)
                }

                XmlPullParser.END_DOCUMENT -> {
                    throw MalformedMultistatusException("Unexpected end of PROPFIND response")
                }

                else -> {
                    // text / comments between elements are ignored
                }
            }
        }
    }

    private companion object {
        /** `d:multistatus` is depth 1, its `d:response` children depth 2. */
        const val RESPONSE_DEPTH = 2
    }
}

/**
 * KXml wraps stream failures of `setInput` (and of its encoding detection) as
 * [XmlPullParserException] with the original exception as `detail`. A stream [IOException]
 * (timeout, `Call.cancel()`, size / token cap) is rethrown unchanged so callers can classify it
 * (cancellation, timeout); an unsupported declared encoding and every parse error are malformed.
 */
private fun XmlPullParserException.toStreamFailure(): IOException {
    val streamFailure = (cause ?: detail) as? IOException
    return if (streamFailure != null && streamFailure !is UnsupportedEncodingException) {
        streamFailure
    } else {
        MalformedMultistatusException("Invalid PROPFIND XML", this)
    }
}

/** Fields of one `d:response`: the first occurrence of each, text content trimmed (like the former DOM parser). */
private enum class EntryField(
    val namespace: String,
    val localName: String,
) {
    HREF(DAV_NS, "href"),
    DISPLAY_NAME(DAV_NS, "displayname"),
    CONTENT_TYPE(DAV_NS, "getcontenttype"),
    FILE_ID(OC_NS, "fileid"),
}

/** Collects one `d:response` while enforcing [PropfindEntryLimits]. */
private class ResponseCollector(
    private val limits: PropfindEntryLimits,
) {
    private val values = HashMap<EntryField, String?>()
    private var isDirectory = false
    private var elementCount = 0
    private var oversized = false
    private var capturing: EntryField? = null
    private var captureDepth = 0
    private val text = StringBuilder()

    fun onStart(
        namespace: String,
        name: String,
        relativeDepth: Int,
    ) {
        elementCount++
        if (elementCount > limits.maxElementsPerEntry || relativeDepth > limits.maxEntryDepth) oversized = true
        if (oversized) return
        if (namespace == DAV_NS && name == "collection") isDirectory = true
        if (capturing != null) return
        val field = EntryField.entries.firstOrNull { it.namespace == namespace && it.localName == name }
        if (field != null && field !in values) {
            capturing = field
            captureDepth = relativeDepth
            text.setLength(0)
        }
    }

    fun onText(chunk: String) {
        if (oversized || capturing == null) return
        if (text.length + chunk.length > limits.maxTextLength) {
            oversized = true
            text.setLength(0)
        } else {
            text.append(chunk)
        }
    }

    fun onEnd(relativeDepth: Int) {
        val field = capturing ?: return
        if (oversized || relativeDepth != captureDepth) return
        values[field] = text.toString().trim().ifBlank { null }
        capturing = null
        text.setLength(0)
    }

    fun result(): PropfindItem? {
        if (oversized) return PropfindItem.Skipped
        val href = values[EntryField.HREF] ?: return null
        // The id ends up in a Memories preview URL: accept plain alphanumerics only, so a
        // malicious server cannot inject path segments or query parameters.
        val fileId = values[EntryField.FILE_ID]?.takeIf { FILE_ID_REGEX.matches(it) }
        return PropfindItem.Entry(
            WebDavEntry(
                href = href,
                displayName = values[EntryField.DISPLAY_NAME] ?: href.trimEnd('/').substringAfterLast('/'),
                isDirectory = isDirectory,
                contentType = values[EntryField.CONTENT_TYPE] ?: "",
                fileId = fileId,
            ),
        )
    }
}

/** Parses a complete multistatus [xml] (tests / small bodies); oversized entries are dropped. */
internal fun parsePropfindXml(xml: String): List<WebDavEntry> {
    val reader = PropfindEntryReader(xml.byteInputStream())
    return generateSequence { reader.next() }
        .filterIsInstance<PropfindItem.Entry>()
        .map { it.entry }
        .toList()
}

/**
 * Counts the bytes read from [delegate] and throws [tooLarge] (default
 * [PropfindResponseTooLargeException]) on the first read that would go beyond [maxBytes] (a body
 * of exactly [maxBytes] is accepted). Wrap the decompressed `ResponseBody.byteStream()` so the cap
 * applies to what is parsed / decoded.
 */
internal class LimitedInputStream(
    delegate: InputStream,
    private val maxBytes: Long,
    private val tooLarge: () -> IOException = { PropfindResponseTooLargeException() },
) : FilterInputStream(delegate) {
    private var count = 0L

    override fun read(): Int {
        if (count >= maxBytes) return probeEnd()
        val b = super.read()
        if (b >= 0) count++
        return b
    }

    override fun read(
        b: ByteArray,
        off: Int,
        len: Int,
    ): Int {
        if (len == 0) return 0
        if (count >= maxBytes) return probeEnd()
        val n = super.read(b, off, minOf(len.toLong(), maxBytes - count).toInt())
        if (n > 0) count += n
        return n
    }

    override fun skip(n: Long): Long {
        if (n <= 0) return 0
        if (count >= maxBytes) {
            probeEnd()
            return 0
        }
        val skipped = super.skip(minOf(n, maxBytes - count))
        count += skipped
        return skipped
    }

    /** Never more than the cap allows, so a reader's read-ahead stops at the cap instead of hitting it. */
    override fun available(): Int = minOf(super.available().toLong(), maxBytes - count).toInt()

    override fun markSupported(): Boolean = false

    /** At the cap: end of stream is fine, any further byte means the body is too large. */
    private fun probeEnd(): Int {
        if (super.read() == -1) return -1
        throw tooLarge()
    }
}

/**
 * Fails with [MalformedMultistatusException] as soon as more than [maxTokenBytes] bytes arrive
 * without a markup start, before KXml buffers them (security review of issues #27 / #46).
 *
 * A markup start is a `<` outside CDATA sections, comments and processing instructions; those
 * three do not end a token because `next()` coalesces the text around them into one text node.
 * So one token is one tag including all its attributes and namespace declarations, or one
 * (coalesced) text node — the inputs whose size KXml handles with linearly growing arrays /
 * buffers. Works on bytes: `<` and the ASCII delimiters never occur inside a multi-byte UTF-8
 * sequence (WebDAV servers send UTF-8; for other encodings the bound is approximate).
 */
internal class XmlTokenSizeLimitingInputStream(
    delegate: InputStream,
    private val maxTokenBytes: Int,
) : FilterInputStream(delegate) {
    private var tokenBytes = 0L

    /** Bytes of the current token before the last `<` (restored when it opens a section). */
    private var bytesBeforeMarkup = 0L
    private var mode = Mode.TEXT

    /** In [Mode.OPENER]: bytes of a possible section opener matched so far (including the `<`). */
    private var openerMatched = 0

    /** In [Mode.OPENER]: openers (bit per [SECTIONS] index) still matching. */
    private var openerCandidates = 0

    /** In [Mode.SECTION]: the section being skipped and how much of its terminator was seen. */
    private var section = SECTIONS[0]
    private var terminatorMatched = 0

    private enum class Mode { TEXT, OPENER, SECTION }

    /** A section opener and its terminator; every terminator is `c…c>` (see [onSectionByte]). */
    private class Section(
        val opener: ByteArray,
        val terminator: ByteArray,
    )

    override fun read(): Int {
        val b = super.read()
        if (b >= 0) onByte(b)
        return b
    }

    override fun read(
        b: ByteArray,
        off: Int,
        len: Int,
    ): Int {
        val n = super.read(b, off, len)
        for (i in off until off + maxOf(n, 0)) onByte(b[i].toInt() and BYTE_MASK)
        return n
    }

    /** Skipped bytes are inspected too (read and discarded). */
    override fun skip(n: Long): Long {
        if (n <= 0) return 0
        val buffer = ByteArray(minOf(n, SKIP_BUFFER_SIZE.toLong()).toInt())
        val read = read(buffer, 0, buffer.size)
        return maxOf(read, 0).toLong()
    }

    override fun markSupported(): Boolean = false

    private fun onByte(b: Int) {
        when (mode) {
            Mode.TEXT -> onTextByte(b)
            Mode.OPENER -> onOpenerByte(b)
            Mode.SECTION -> onSectionByte(b)
        }
        if (tokenBytes > maxTokenBytes) throw MalformedMultistatusException("PROPFIND XML token too large")
    }

    private fun onTextByte(b: Int) {
        if (b == LT) {
            bytesBeforeMarkup = tokenBytes
            tokenBytes = 1
            mode = Mode.OPENER
            openerMatched = 1
            openerCandidates = ALL_SECTIONS
        } else {
            tokenBytes++
        }
    }

    private fun onOpenerByte(b: Int) {
        var candidates = 0
        SECTIONS.forEachIndexed { i, s ->
            if (openerCandidates and (1 shl i) != 0 &&
                s.opener[openerMatched].toInt() == b
            ) {
                candidates = candidates or (1 shl i)
            }
        }
        if (candidates == 0) {
            // A tag (or `<!DOCTYPE`, rejected by the reader): the new token continues as usual.
            mode = Mode.TEXT
            onTextByte(b)
            return
        }
        tokenBytes++
        openerMatched++
        openerCandidates = candidates
        val opened =
            SECTIONS.indices.firstOrNull {
                candidates and (1 shl it) != 0 &&
                    SECTIONS[it].opener.size == openerMatched
            }
        if (opened != null) {
            // CDATA / comment / PI: part of the surrounding text token, not a markup start.
            tokenBytes += bytesBeforeMarkup
            section = SECTIONS[opened]
            terminatorMatched = 0
            mode = Mode.SECTION
        }
    }

    private fun onSectionByte(b: Int) {
        tokenBytes++
        val terminator = section.terminator
        if (terminator[terminatorMatched].toInt() == b) {
            terminatorMatched++
            if (terminatorMatched == terminator.size) mode = Mode.TEXT
        } else if (terminator[0].toInt() != b) {
            // Terminators are `c…c>`: a further `c` keeps the match (`--->`, `]]]>`), anything else resets it.
            terminatorMatched = 0
        }
    }

    private companion object {
        const val LT = '<'.code
        const val BYTE_MASK = 0xFF
        const val SKIP_BUFFER_SIZE = 8 * 1024
        val SECTIONS =
            listOf(
                Section("<!--".toByteArray(), "-->".toByteArray()),
                Section("<![CDATA[".toByteArray(), "]]>".toByteArray()),
                Section("<?".toByteArray(), "?>".toByteArray()),
            )
        val ALL_SECTIONS = (1 shl SECTIONS.size) - 1
    }
}

internal fun buildOrigin(url: String): String =
    try {
        val u = java.net.URL(url)
        buildString {
            append(u.protocol).append("://").append(u.host)
            if (u.port != -1) append(":").append(u.port)
        }
    } catch (_: Exception) {
        ""
    }

internal fun buildNextcloudBase(url: String): String? {
    val idx = url.indexOf("/remote.php/")
    return if (idx > 0) url.substring(0, idx) else null
}

private val FILE_ID_REGEX = Regex("[A-Za-z0-9]+")
