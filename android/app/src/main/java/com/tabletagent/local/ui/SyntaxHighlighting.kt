package com.tabletagent.local.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation

/**
 * VisualTransformation that applies syntax highlighting on-the-fly to code text.
 * Offset mapping remains 1:1 Identity so cursors, selections, and typing behave normally.
 */
class SyntaxHighlightTransformation(private val filePathOrExt: String) : VisualTransformation {

    private val language = detectLanguage(filePathOrExt)

    override fun filter(text: AnnotatedString): TransformedText {
        val raw = text.text
        if (raw.length > 50000 || language == "plaintext") {
            return TransformedText(text, OffsetMapping.Identity)
        }
        val annotated = highlightCode(raw, language)
        return TransformedText(annotated, OffsetMapping.Identity)
    }

    companion object {
        fun detectLanguage(path: String): String {
            val ext = path.substringAfterLast('.', "").lowercase()
            return when (ext) {
                "js", "jsx", "mjs", "cjs" -> "javascript"
                "ts", "tsx" -> "typescript"
                "py", "pyw" -> "python"
                "kt", "kts" -> "kotlin"
                "java" -> "java"
                "html", "htm", "vue", "svelte", "xml", "svg" -> "html"
                "css", "scss", "sass", "less" -> "css"
                "json" -> "json"
                "md", "markdown" -> "markdown"
                "sh", "bash", "zsh" -> "shell"
                "diff", "patch" -> "diff"
                "sql" -> "sql"
                else -> "plaintext"
            }
        }

        fun highlightCode(code: String, language: String): AnnotatedString {
            val builder = AnnotatedString.Builder(code)
            val defaultColor = Color(0xFFD4D4D4)
            val keywordColor = Color(0xFFC792EA)   // soft purple
            val stringColor = Color(0xFFC3E88D)    // soft green
            val commentColor = Color(0xFF6B7280)   // muted slate
            val numberColor = Color(0xFFF78C6C)    // orange
            val functionColor = Color(0xFF82AAFF)  // sky blue
            val typeColor = Color(0xFFFFCB6B)      // gold/yellow
            val tagColor = Color(0xFF89DDFF)       // cyan

            builder.addStyle(SpanStyle(color = defaultColor, fontFamily = FontFamily.Monospace), 0, code.length)

            // 1. Comments
            val commentRegex = if (language == "html") {
                Regex("""<!--[\s\S]*?-->""")
            } else if (language in listOf("python", "shell")) {
                Regex("""#.*""")
            } else {
                Regex("""(?://.*|/\*[\s\S]*?\*/)""")
            }
            for (m in commentRegex.findAll(code)) {
                builder.addStyle(SpanStyle(color = commentColor, fontStyle = FontStyle.Italic), m.range.first, m.range.last + 1)
            }

            // 2. Strings
            val stringRegex = Regex(""""(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*'|`(?:\\.|[^`\\])*`""")
            for (m in stringRegex.findAll(code)) {
                builder.addStyle(SpanStyle(color = stringColor), m.range.first, m.range.last + 1)
            }

            // 3. Numbers
            val numberRegex = Regex("""\b\d+(?:\.\d+)?(?:[eE][+-]?\d+)?\b""")
            for (m in numberRegex.findAll(code)) {
                builder.addStyle(SpanStyle(color = numberColor), m.range.first, m.range.last + 1)
            }

            // 4. Keywords
            val keywords = when (language) {
                "python" -> setOf(
                    "def", "class", "import", "from", "return", "if", "elif", "else", "for", "while",
                    "try", "except", "finally", "with", "as", "async", "await", "lambda", "yield",
                    "True", "False", "None", "pass", "break", "continue", "raise", "in", "is", "not", "and", "or"
                )
                "kotlin", "java" -> setOf(
                    "fun", "val", "var", "class", "interface", "object", "return", "if", "else", "for",
                    "while", "when", "import", "package", "override", "suspend", "data", "sealed",
                    "public", "private", "protected", "internal", "true", "false", "null", "new"
                )
                "html" -> setOf("html", "head", "body", "div", "span", "h1", "h2", "h3", "p", "a", "button", "input", "script", "style", "table", "tr", "td", "th")
                "css" -> setOf("display", "position", "margin", "padding", "color", "background", "width", "height", "flex", "grid", "border", "font", "rem", "px", "em")
                "json" -> setOf("true", "false", "null")
                "shell" -> setOf("if", "then", "else", "fi", "for", "while", "do", "done", "case", "esac", "echo", "exit", "export", "source")
                else -> setOf(
                    "function", "const", "let", "var", "return", "if", "else", "for", "while", "import",
                    "export", "from", "default", "class", "async", "await", "try", "catch", "finally",
                    "new", "type", "interface", "true", "false", "null", "undefined", "this", "super",
                    "switch", "case", "break", "continue"
                )
            }
            if (keywords.isNotEmpty()) {
                val kwPattern = Regex("""\b(${keywords.joinToString("|")})\b""")
                for (m in kwPattern.findAll(code)) {
                    builder.addStyle(SpanStyle(color = keywordColor, fontWeight = FontWeight.Bold), m.range.first, m.range.last + 1)
                }
            }

            // 5. Functions & Methods
            if (language !in listOf("json", "css", "html")) {
                val fnRegex = Regex("""\b([a-zA-Z_][a-zA-Z0-9_]*)\s*(?=\()""")
                for (m in fnRegex.findAll(code)) {
                    builder.addStyle(SpanStyle(color = functionColor), m.range.first, m.range.last + 1)
                }
            }

            // 6. JSON Keys or HTML Tags
            if (language == "json") {
                val jsonKeyRegex = Regex(""""([^"\\]+)"\s*(?=:)""")
                for (m in jsonKeyRegex.findAll(code)) {
                    builder.addStyle(SpanStyle(color = tagColor, fontWeight = FontWeight.SemiBold), m.range.first, m.range.last + 1)
                }
            } else if (language == "html") {
                val htmlTagRegex = Regex("""</?([a-zA-Z0-9-]+)""")
                for (m in htmlTagRegex.findAll(code)) {
                    builder.addStyle(SpanStyle(color = tagColor), m.range.first, m.range.last + 1)
                }
            }

            return builder.toAnnotatedString()
        }
    }
}
