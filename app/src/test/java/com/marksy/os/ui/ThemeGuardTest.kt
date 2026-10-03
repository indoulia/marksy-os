package com.marksy.os.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Screens style themselves only through MarksyTheme, MarksyType, MarksyShape, MarksySpace, MarksySize, the shared
 * components and MarksyFormat. Each one-off choice below is counted per file against theme-baseline.txt: a count may
 * fall but never rise, and a new file starts at zero. The current counts are written to
 * build/theme-baseline.actual.txt; copy it over the baseline after a migration lowers them.
 */
class ThemeGuardTest {
    private class Rule(val name: String, val pattern: Regex)

    private val rules = listOf(
        Rule("colour code", Regex("""Color\(0x""")),
        Rule("font size", Regex("""\.sp\b""")),
        Rule("corner radius", Regex("""RoundedCornerShape\(""")),
        Rule("named colour", Regex("""Color\.(White|Black|Gray|LightGray|DarkGray|Red|Green|Blue|Yellow|Cyan|Magenta)\b""")),
        Rule("text style", Regex("""(?<![A-Za-z])TextStyle\(""")),
        Rule("stock Material control", Regex("""(?<![A-Za-z.])(Button|OutlinedButton|TextButton|Card|AlertDialog|OutlinedTextField|TextField|FilterChip|AssistChip|SuggestionChip|InputChip|HorizontalDivider|Divider|CircularProgressIndicator)\(""")),
        Rule("toast", Regex("""Toast\.makeText""")),
        Rule("date pattern", Regex("""DateTimeFormatter\.ofPattern|SimpleDateFormat\(""")),
        Rule("number format", Regex("""String\.format\(|"%[-+0-9.,]*[df]"""")),
        Rule("spacing literal", Regex("""(?:padding|spacedBy)\([^)]*?\d+(?:\.\d+)?\.dp""")),
        Rule("type override", Regex("""FontWeight\.Black|letterSpacing =|lineHeight ="""))
    )
    // The theme itself, and the loader that draws the one spinner.
    private val themeFiles = setOf(
        "com/marksy/os/ui/MarksyTheme.kt", "com/marksy/os/ui/MarksyComponents.kt", "com/marksy/os/MarksyFormat.kt", "com/marksy/os/ui/Refresh.kt"
    )

    @Test
    fun noScreenAddsItsOwnStyling() {
        val baseline = javaClass.classLoader!!.getResource("theme-baseline.txt")!!.readText().lines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .associate { line -> line.split(" ").let { it[0] to it.drop(1).map(String::toInt) } }
        val root = File("src/main/java")
        val counts = root.walkTopDown().filter { it.extension == "kt" }.map { it.relativeTo(root).invariantSeparatorsPath to it }
            .filter { (path, _) -> path !in themeFiles }
            .map { (path, file) ->
                val code = file.readLines().filterNot { it.trimStart().startsWith("import ") }.joinToString("\n")
                path to rules.map { it.pattern.findAll(code).count() }
            }
            .filter { (_, n) -> n.any { it > 0 } }
            .sortedBy { it.first }.toList()
        File("build/theme-baseline.actual.txt").writeText(
            "# path ${rules.joinToString(" ") { it.name.replace(' ', '-') }} -- counts may only go down; see ThemeGuardTest\n" +
                counts.joinToString("") { (path, n) -> "$path ${n.joinToString(" ")}\n" }
        )
        val grown = counts.flatMap { (path, n) ->
            val allowed = baseline[path] ?: List(rules.size) { 0 }
            rules.indices.filter { n[it] > allowed.getOrElse(it) { 0 } }.map { "$path: ${n[it]} ${rules[it].name} literals, baseline ${allowed.getOrElse(it) { 0 }}" }
        }
        assertTrue("Use the Marksy theme instead:\n" + grown.joinToString("\n"), grown.isEmpty())
    }
}
