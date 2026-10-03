package com.marksy.os.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Screens style themselves only through MarksyTheme, MarksyType, MarksyShape and the shared components. Hard-coded
 * colours, font sizes and corner radii are counted per file against theme-baseline.txt; a count may fall (lower
 * the baseline in the same change) but never rise, and a new file starts at zero.
 */
class ThemeGuardTest {
    private val patterns = listOf(Regex("""Color\(0x"""), Regex("""\b\d+(?:\.\d+)?\.sp\b"""), Regex("""RoundedCornerShape\(\d"""))
    private val names = listOf("colour", "font size", "corner radius")
    private val themeFiles = setOf("com/marksy/os/ui/MarksyTheme.kt", "com/marksy/os/ui/MarksyComponents.kt")

    @Test
    fun noScreenAddsItsOwnColoursSizesOrCorners() {
        val baseline = javaClass.classLoader!!.getResource("theme-baseline.txt")!!.readText().lines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .associate { line -> line.split(" ").let { it[0] to it.drop(1).map(String::toInt) } }
        val root = File("src/main/java")
        val grown = root.walkTopDown().filter { it.extension == "kt" }.mapNotNull { file ->
            val path = file.relativeTo(root).invariantSeparatorsPath
            if (path in themeFiles) return@mapNotNull null
            val text = file.readText()
            val allowed = baseline[path] ?: listOf(0, 0, 0)
            patterns.indices.mapNotNull { i ->
                val n = patterns[i].findAll(text).count()
                if (n > allowed[i]) "$path: $n hard-coded ${names[i]} literals, baseline ${allowed[i]}" else null
            }.takeIf { it.isNotEmpty() }
        }.flatten().toList()
        assertTrue("Use MarksyTheme/MarksyType/MarksyShape instead:\n" + grown.joinToString("\n"), grown.isEmpty())
    }
}
