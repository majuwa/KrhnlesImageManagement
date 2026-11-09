package de.majuwa.android.paper.krhnlesimagemanagement

import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** String resources parsed from the XML sources of one `values*` folder (test support). */
class ParsedStrings(
    val strings: Map<String, String>,
    val plurals: Map<String, Map<String, String>>,
    val arrays: Map<String, List<String>>,
    val nonTranslatable: Set<String>,
) {
    /** Translatable keys of every kind (strings, plurals, arrays). */
    val translatableKeys: Set<String> =
        (strings.keys + plurals.keys + arrays.keys) - nonTranslatable

    val allKeys: Set<String> = strings.keys + plurals.keys + arrays.keys
}

/** One format placeholder: argument index (explicit or sequential) and conversion character. */
data class Placeholder(
    val index: Int,
    val conversion: Char,
)

object StringResourceScanner {
    private val PLACEHOLDER = Regex("""%(\d+\$)?[-#+ 0,(<]*\d*(?:\.\d+)?([a-zA-Z%])""")

    fun moduleDir(): File = File(System.getProperty("krhnles.moduleDir") ?: ".").absoluteFile

    fun resDir(name: String): File = File(moduleDir(), "src/main/res/$name")

    /** Parses every XML file directly inside [dir]; a missing folder yields an empty result. */
    fun parse(dir: File): ParsedStrings {
        val strings = linkedMapOf<String, String>()
        val plurals = linkedMapOf<String, Map<String, String>>()
        val arrays = linkedMapOf<String, List<String>>()
        val nonTranslatable = linkedSetOf<String>()
        val files = dir.listFiles { f -> f.isFile && f.extension == "xml" }?.sortedBy { it.name }.orEmpty()
        val builder = DocumentBuilderFactory.newInstance().newDocumentBuilder()
        for (file in files) {
            val root = builder.parse(file).documentElement
            if (root.tagName != "resources") continue
            val children = root.childNodes
            for (i in 0 until children.length) {
                val node = children.item(i) as? Element ?: continue
                val name = node.getAttribute("name")
                if (node.getAttribute("translatable") == "false") nonTranslatable += name
                when (node.tagName) {
                    "string" -> strings[name] = node.textContent
                    "plurals" -> plurals[name] = items(node).associate { it.getAttribute("quantity") to it.textContent }
                    "string-array" -> arrays[name] = items(node).map { it.textContent }
                }
            }
        }
        return ParsedStrings(strings, plurals, arrays, nonTranslatable)
    }

    private fun items(parent: Element): List<Element> {
        val result = mutableListOf<Element>()
        val children = parent.childNodes
        for (i in 0 until children.length) {
            val node = children.item(i)
            if (node is Element && node.tagName == "item") result += node
        }
        return result
    }

    /**
     * Placeholders of [text] (ignoring `%%`); non-positional ones are numbered sequentially.
     * The second value is true when positional and non-positional placeholders are mixed.
     */
    fun placeholders(text: String): Pair<List<Placeholder>, Boolean> {
        var sequential = 0
        var positional = false
        var plain = false
        val result = mutableListOf<Placeholder>()
        for (match in PLACEHOLDER.findAll(text)) {
            val conversion = match.groupValues[2].single()
            if (conversion == '%') continue
            val explicit = match.groupValues[1]
            if (explicit.isNotEmpty()) {
                positional = true
                result += Placeholder(explicit.dropLast(1).toInt(), conversion)
            } else {
                plain = true
                sequential++
                result += Placeholder(sequential, conversion)
            }
        }
        return result to (positional && plain)
    }

    /** True when the placeholder multisets of both texts are equal. */
    fun samePlaceholders(
        english: String,
        german: String,
    ): Boolean =
        placeholders(english).first.sortedBy { it.index * 256 + it.conversion.code } ==
            placeholders(german).first.sortedBy { it.index * 256 + it.conversion.code }

    /** ASCII quotes that are not escaped, or a three-dot ellipsis. Empty list = fine. */
    fun escapingProblems(text: String): List<String> {
        val problems = mutableListOf<String>()
        text.forEachIndexed { i, c ->
            val escaped = i > 0 && text[i - 1] == '\\'
            if (c in "'\"" && !escaped) problems += "unescaped $c"
        }
        if ("..." in text) problems += "three dots instead of the ellipsis character"
        return problems
    }

    private val FORMAL = Regex("""\b(Sie|Ihr|Ihre|Ihren|Ihrem|Ihrer|Ihres)\b""")
    private val FORMAL_ALWAYS = Regex("""\bIhnen\b""")
    private val CAPITAL_DU = Regex("""\b(Du|Dich|Dir|Dein|Deine|Deinen|Deinem|Deiner|Deines)\b""")

    /** Formal address ("Sie", "Ihnen") or capitalised "du" forms in the middle of a sentence. */
    fun addressProblems(text: String): List<String> {
        val problems = mutableListOf<String>()
        if (FORMAL_ALWAYS.containsMatchIn(text)) problems += "formal 'Ihnen'"
        for (match in FORMAL.findAll(text)) {
            if (!atSentenceStart(text, match.range.first)) problems += "formal '${match.value}'"
        }
        for (match in CAPITAL_DU.findAll(text)) {
            if (!atSentenceStart(text, match.range.first)) problems += "capitalised '${match.value}'"
        }
        return problems
    }

    private fun atSentenceStart(
        text: String,
        index: Int,
    ): Boolean {
        var i = index - 1
        while (i >= 0 && text[i] == ' ') i--
        if (i < 0) return true
        // sentence end, or an escaped line break (backslash + n) in the unprocessed text
        return text[i] in ".!?:" || endsWithEscapedNewline(text, i)
    }

    private fun endsWithEscapedNewline(
        text: String,
        index: Int,
    ): Boolean = text[index] == 'n' && index >= 1 && text[index - 1] == '\\'
}
