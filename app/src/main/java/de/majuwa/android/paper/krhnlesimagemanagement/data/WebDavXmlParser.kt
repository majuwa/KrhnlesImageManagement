package de.majuwa.android.paper.krhnlesimagemanagement.data

import org.w3c.dom.Element
import org.w3c.dom.NodeList
import javax.xml.parsers.DocumentBuilderFactory

internal const val DAV_NS = "DAV:"
internal const val OC_NS = "http://owncloud.org/ns"

internal data class WebDavEntry(
    val href: String,
    val displayName: String,
    val isDirectory: Boolean,
    val contentType: String,
    val fileId: String?,
)

// Strips DOCTYPE declarations (including internal subsets) to prevent XXE injection.
// Android's Harmony/Expat parser doesn't support the standard SAX security features
// (external-general-entities, external-parameter-entities), so we strip DOCTYPE
// before parsing and disable entity expansion as defense-in-depth.
private val DOCTYPE_PATTERN = Regex("""<!DOCTYPE[^\[>]*(\[[^\]]*])?\s*>""")

internal fun parsePropfindXml(xml: String): List<WebDavEntry> {
    val sanitized = DOCTYPE_PATTERN.replace(xml, "")
    val factory =
        DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            isExpandEntityReferences = false
        }
    val doc = factory.newDocumentBuilder().parse(sanitized.byteInputStream())
    val responses = doc.getElementsByTagNameNS(DAV_NS, "response")
    val entries = mutableListOf<WebDavEntry>()

    for (i in 0 until responses.length) {
        val resp = responses.item(i) as? Element
        val href = resp?.firstTextNS(DAV_NS, "href")
        if (resp == null || href == null) continue
        val displayName =
            resp.firstTextNS(DAV_NS, "displayname")
                ?: href.trimEnd('/').substringAfterLast('/')
        val isDirectory =
            resp
                .getElementsByTagNameNS(DAV_NS, "collection")
                .length > 0
        val contentType = resp.firstTextNS(DAV_NS, "getcontenttype") ?: ""
        val fileId = resp.firstTextNS(OC_NS, "fileid")
        entries +=
            WebDavEntry(
                href = href,
                displayName = displayName,
                isDirectory = isDirectory,
                contentType = contentType,
                fileId = fileId,
            )
    }
    return entries
}

internal fun Element.firstTextNS(
    ns: String,
    local: String,
): String? =
    (getElementsByTagNameNS(ns, local) as NodeList)
        .let { nl ->
            if (nl.length > 0) {
                nl
                    .item(0)
                    .textContent
                    .trim()
                    .ifBlank { null }
            } else {
                null
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
