package com.marksy.os.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Screens style themselves only through MarksyTheme, MarksyType, MarksyShape, MarksySpace, MarksySize, MarksyElevation,
 * MarksyDraw, MarksyGesture, the shared components and MarksyFormat. Any one-off choice below fails the build, except the fixed [exceptions]; there is no
 * baseline to refresh, so new styling cannot slip in by updating a counts file.
 */
class ThemeGuardTest {
    private class Rule(val name: String, val pattern: Regex)

    private companion object {
        const val DP = """\d+(?:\.\d+)?\.dp"""
    }

    private val rules = listOf(
        Rule("colour code", Regex("""Color\(0x""")),
        Rule("font size", Regex("""\.sp\b""")),
        Rule("corner radius", Regex("""RoundedCornerShape\(|CornerSize\(""")),
        Rule("named colour", Regex("""Color\.(White|Black|Gray|LightGray|DarkGray|Red|Green|Blue|Yellow|Cyan|Magenta)\b""")),
        Rule("text style", Regex("""(?<![A-Za-z])TextStyle\(""")),
        Rule("stock Material control", Regex("""(?<![A-Za-z.])(Button|OutlinedButton|TextButton|IconButton|Card|AlertDialog|OutlinedTextField|TextField|FilterChip|AssistChip|SuggestionChip|InputChip|HorizontalDivider|Divider|CircularProgressIndicator|Switch|DropdownMenu|DropdownMenuItem|ModalBottomSheet|DatePickerDialog)\(""")),
        Rule("toast", Regex("""Toast\.makeText""")),
        Rule("date pattern", Regex("""DateTimeFormatter\.ofPattern|SimpleDateFormat\(""")),
        Rule("number format", Regex("""String\.format\(|"%[-+0-9.,]*[df]"""")),
        Rule("spacing literal", Regex("""(?:(?:padding|spacedBy)\([^)]*?|Spacer\(Modifier\.(?:width|height)\()$DP""")),
        Rule("size literal", Regex("""\.(?:height|width|size|heightIn|widthIn|sizeIn|requiredSize|requiredHeight|requiredWidth|requiredSizeIn|defaultMinSize|offset)\([^)]*?$DP""")),
        Rule("elevation literal", Regex("""(?:shadow\(|[Ee]levation\s*=\s*)[^)]*?$DP""")),
        Rule("border width", Regex("""(?:border\(|BorderStroke\(|strokeWidth\s*=\s*)\s*$DP""")),
        Rule("drawing literal", Regex("""$DP\.toPx\(\)""")),
        Rule("hand-built list", Regex("""(?<![A-Za-z])LazyColumn\(""")),
        Rule("hand-built card", Regex("""(?<![A-Za-z])marksyCard\(""")),
        Rule("type override", Regex("""FontWeight\.Black|letterSpacing =|lineHeight =|fontFamily =|FontFamily\."""))
    )

    // Any dp the named rules leave: a constant, a default argument, a sum.
    private val dpLiteral = Rule("dp literal", Regex("""(?<![\w.])$DP\b"""))

    // The theme itself, and the loader that draws the one spinner.
    private val themeFiles = setOf(
        "com/marksy/os/ui/MarksyTheme.kt", "com/marksy/os/ui/MarksyComponents.kt", "com/marksy/os/MarksyFormat.kt", "com/marksy/os/ui/Refresh.kt"
    )

    // Not display styling; each entry is exact (a stale one fails too). Add one only with the user's agreement.
    private val exceptions = mapOf(
        // Parses dates out of message text.
        "com/marksy/os/intelligence/EventExtractor.kt" to mapOf("date pattern" to 2),
        // Formats coordinates into the weather API URL.
        "com/marksy/os/weather/WeatherRepository.kt" to mapOf("number format" to 1)
    )

    @Test
    fun noScreenAddsItsOwnStyling() {
        val root = File("src/main/java")
        val found = root.walkTopDown().filter { it.extension == "kt" }
            .map { it.relativeTo(root).invariantSeparatorsPath to it }
            .filter { (path, _) -> path !in themeFiles }
            .flatMap { (path, file) ->
                val code = file.readLines().filterNot { it.trimStart().startsWith("import ") }.joinToString("\n")
                val rest = rules.fold(code) { left, rule -> rule.pattern.replace(left, "") }
                rules.map { Triple(path, it.name, it.pattern.findAll(code).count()) } + Triple(path, dpLiteral.name, dpLiteral.pattern.findAll(rest).count())
            }
            .filter { it.third > 0 }
            .toList()
        val problems = found.mapNotNull { (path, rule, n) ->
            val allowed = exceptions[path]?.get(rule) ?: 0
            if (n > allowed) "$path: $n $rule${if (allowed > 0) " (allowed $allowed)" else ""}" else null
        } + exceptions.flatMap { (path, allowed) ->
            allowed.mapNotNull { (rule, n) ->
                val actual = found.firstOrNull { it.first == path && it.second == rule }?.third ?: 0
                if (actual < n) "$path: exception for $n $rule but only $actual found; tighten the exception" else null
            }
        }
        assertTrue("Use the Marksy theme instead:\n" + problems.joinToString("\n"), problems.isEmpty())
    }

    // The theme files declare the tokens and their components use them: a dp or sp number sits only on a token's own line.
    @Test
    fun themeComponentsUseTokens() {
        val declaration = Regex("""^\s*(?:(?:private|internal)\s+)?val\s+\w+\s*=""")
        val literal = Regex("""(?<![\w.])\d+(?:\.\d+)?\.(?:dp|sp)\b""")
        val problems = themeFiles.flatMap { path ->
            File("src/main/java", path).readLines().withIndex()
                .filter { (_, line) -> literal.containsMatchIn(line) && !declaration.containsMatchIn(line) }
                .map { (i, line) -> "$path:${i + 1}: ${line.trim()}" }
        }
        assertTrue("Declare a token in MarksyTheme.kt and use it:\n" + problems.joinToString("\n"), problems.isEmpty())
    }
}
