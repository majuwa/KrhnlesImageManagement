package de.majuwa.android.paper.krhnlesimagemanagement

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source-level guard that every translatable text of `values/` has a German twin in `values-de/`
 * with identical placeholders and plural quantities, correct escaping, informal address and the
 * removal wording ("entfernen" / "Papierkorb", never "löschen") of the local trash flow.
 */
class StringResourceParityTest {
    private val default by lazy { StringResourceScanner.parse(StringResourceScanner.resDir("values")) }
    private val german by lazy { StringResourceScanner.parse(StringResourceScanner.resDir("values-de")) }

    /** Every German text with its resource key (plural quantities as `name[quantity]`). */
    private fun germanTexts(): Map<String, String> =
        german.strings +
            german.plurals.flatMap { (name, items) -> items.map { (q, text) -> "$name[$q]" to text } } +
            german.arrays.flatMap { (name, items) -> items.mapIndexed { i, text -> "$name[$i]" to text } }

    @Test
    fun `sources are found`() {
        assertTrue(
            "Expected ${StringResourceScanner.resDir("values").path} (system property krhnles.moduleDir)",
            default.allKeys.isNotEmpty(),
        )
    }

    @Test
    fun `every translatable key has a German translation and no extra key exists`() {
        val missing = (default.translatableKeys - german.allKeys).sorted()
        val extra = (german.allKeys - default.translatableKeys).sorted()
        assertTrue("Missing in values-de: $missing", missing.isEmpty())
        assertTrue("Extra in values-de (no translatable default): $extra", extra.isEmpty())
    }

    @Test
    fun `non-translatable keys have no German copy`() {
        val copied = (default.nonTranslatable intersect german.allKeys).sorted()
        assertTrue("translatable=\"false\" keys copied to values-de: $copied", copied.isEmpty())
    }

    @Test
    fun `plural quantities are equal and include one and other`() {
        val problems =
            default.plurals
                .filterKeys { it !in default.nonTranslatable && it in german.plurals }
                .flatMap { (name, items) -> pluralProblems(name, items.keys, german.plurals.getValue(name).keys) }
        assertTrue("Plural problems: $problems", problems.isEmpty())
    }

    private fun pluralProblems(
        name: String,
        english: Set<String>,
        de: Set<String>,
    ): List<String> =
        listOfNotNull(if (de != english) "$name: quantities ${de.sorted()} != ${english.sorted()}" else null) +
            listOf("one", "other").filter { it !in de }.map { "$name: missing German '$it'" }

    @Test
    fun `string arrays have equal item counts`() {
        val problems =
            default.arrays
                .filterKeys { it !in default.nonTranslatable }
                .mapNotNull { (name, items) ->
                    val de = german.arrays[name] ?: return@mapNotNull null
                    if (de.size != items.size) "$name: ${de.size} != ${items.size}" else null
                }
        assertTrue("String array problems: $problems", problems.isEmpty())
    }

    /** English / German text pairs of every translated string, plural quantity and array item. */
    private fun textPairs(): List<Triple<String, String, String>> {
        val strings =
            default.strings
                .filterKeys { it !in default.nonTranslatable && it in german.strings }
                .map { (name, en) -> Triple(name, en, german.strings.getValue(name)) }
        val plurals =
            default.plurals
                .filterKeys { it !in default.nonTranslatable && it in german.plurals }
                .flatMap { (name, items) ->
                    items.mapNotNull { (q, en) ->
                        german.plurals.getValue(name)[q]?.let { Triple("$name[$q]", en, it) }
                    }
                }
        val arrays =
            default.arrays
                .filterKeys { it !in default.nonTranslatable && it in german.arrays }
                .flatMap { (name, items) ->
                    items.zip(german.arrays.getValue(name)).mapIndexed { i, (en, de) -> Triple("$name[$i]", en, de) }
                }
        return strings + plurals + arrays
    }

    @Test
    fun `placeholders match the English text per string and plural quantity`() {
        val problems = mutableListOf<String>()
        textPairs().forEach { (key, en, de) -> check(key, en, de, problems) }
        assertTrue("Placeholder mismatches:\n${problems.joinToString("\n")}", problems.isEmpty())
    }

    private fun check(
        key: String,
        english: String,
        german: String,
        problems: MutableList<String>,
    ) {
        if (StringResourceScanner.placeholders(english).second) problems += "$key: English mixes positional and plain"
        if (StringResourceScanner.placeholders(german).second) problems += "$key: German mixes positional and plain"
        if (!StringResourceScanner.samePlaceholders(english, german)) {
            problems += "$key: '$english' vs '$german'"
        }
    }

    @Test
    fun `German texts are escaped and use the ellipsis character`() {
        val problems =
            germanTexts().flatMap { (key, text) ->
                StringResourceScanner.escapingProblems(text).map { "$key: $it" }
            }
        assertTrue("Escaping problems: $problems", problems.isEmpty())
    }

    @Test
    fun `German texts address the user informally`() {
        val problems =
            germanTexts().flatMap { (key, text) ->
                StringResourceScanner.addressProblems(text).map { "$key: $it" }
            }
        assertTrue("Address problems: $problems", problems.isEmpty())
    }

    @Test
    fun `local removal flow never speaks of deleting`() {
        val offenders =
            germanTexts().filter { (key, text) ->
                val name = key.substringBefore('[')
                (name == "action_remove_uploaded" || name == "action_move_to_trash" || name.startsWith("removal_")) &&
                    text.contains("lösch", ignoreCase = true)
            }
        assertTrue("German removal texts must not say 'löschen': ${offenders.keys}", offenders.isEmpty())
    }

    @Test
    fun `values-de holds no lint suppression`() {
        val offenders =
            (
                StringResourceScanner
                    .resDir("values-de")
                    .listFiles()
                    ?.toList()
                    .orEmpty()
            ).filter { it.isFile && it.readText().contains("tools:ignore") }
                .map(File::getName)
        assertTrue("tools:ignore in values-de: $offenders", offenders.isEmpty())
    }

    // ---- self-tests of the extractors ----

    @Test
    fun `placeholder extractor handles positional plain and escaped percent`() {
        val (positional, mixed) = StringResourceScanner.placeholders("Photo %1\$d of %2\$d, %3\$s")
        assertEquals(listOf(Placeholder(1, 'd'), Placeholder(2, 'd'), Placeholder(3, 's')), positional)
        assertFalse(mixed)
        val plain = StringResourceScanner.placeholders("%d of %s").first
        assertEquals(listOf(Placeholder(1, 'd'), Placeholder(2, 's')), plain)
        assertEquals(listOf(Placeholder(1, 'd')), StringResourceScanner.placeholders("%d %%").first)
        assertTrue(StringResourceScanner.placeholders("%1\$d and %s").second)
    }

    @Test
    fun `placeholder comparison allows reordering but not a changed conversion`() {
        assertTrue(StringResourceScanner.samePlaceholders("%1\$s of %2\$s", "%2\$s von %1\$s"))
        assertFalse(StringResourceScanner.samePlaceholders("%1\$d items", "%1\$s Elemente"))
        assertFalse(StringResourceScanner.samePlaceholders("%d items", "Elemente"))
    }

    @Test
    fun `escaping checker flags raw quotes and three dots`() {
        assertTrue(StringResourceScanner.escapingProblems("It's").isNotEmpty())
        assertTrue(StringResourceScanner.escapingProblems("Wird geladen...").isNotEmpty())
        assertTrue(StringResourceScanner.escapingProblems("Album „x“ löschen…").isEmpty())
        assertTrue(StringResourceScanner.escapingProblems("Dem Server\\'s").isEmpty())
    }

    @Test
    fun `address checker rejects formal address and accepts sentence starts`() {
        assertTrue(StringResourceScanner.addressProblems("Bitte prüfen Sie die Serveradresse").isNotEmpty())
        assertTrue(StringResourceScanner.addressProblems("Das ist Ihnen egal").isNotEmpty())
        assertTrue(StringResourceScanner.addressProblems("Sie wurden hochgeladen.").isEmpty())
        assertTrue(StringResourceScanner.addressProblems("Fertig.\\nSie wurden hochgeladen.").isEmpty())
        assertTrue(StringResourceScanner.addressProblems("Prüfe Dein Passwort").isNotEmpty())
        assertTrue(StringResourceScanner.addressProblems("Prüfe dein Passwort").isEmpty())
        assertTrue(StringResourceScanner.addressProblems("Dein Passwort stimmt nicht").isEmpty())
    }
}
