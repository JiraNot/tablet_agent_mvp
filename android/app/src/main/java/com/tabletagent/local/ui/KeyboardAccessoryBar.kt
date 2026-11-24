package com.tabletagent.local.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun KeyboardAccessoryBar(
    modifier: Modifier = Modifier,
    onInsertText: (String) -> Unit
) {
    val commonSymbols = listOf(
        "{" to "{\n  \n}",
        "}" to "}",
        "(" to "()",
        ")" to ")",
        "[" to "[]",
        "]" to "]",
        "=>" to " => ",
        "\"\"" to "\"\"",
        "''" to "''",
        "`" to "``",
        ";" to ";",
        ":" to ": ",
        "// " to "// ",
        "=" to " = ",
        "==" to " === ",
        "&&" to " && ",
        "||" to " || ",
        "$" to "$",
        "<" to "<",
        ">" to ">",
        "/" to "/"
    )

    val quickSnippets = listOf(
        "Tab" to "  ",
        "log" to "console.log();",
        "const" to "const  = ;",
        "let" to "let  = ;",
        "return" to "return ;",
        "async" to "async ",
        "await" to "await ",
        "import" to "import  from '';\n"
    )

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 4.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // Snippets (Tab, const, log, return)
            quickSnippets.forEach { (label, insert) ->
                FilledTonalButton(
                    onClick = { onInsertText(insert) },
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    modifier = Modifier.height(34.dp),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer
                    )
                ) {
                    Text(
                        label,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }

            VerticalDivider(modifier = Modifier.height(20.dp).padding(horizontal = 2.dp))

            // Programming Symbols
            commonSymbols.forEach { (label, insert) ->
                OutlinedButton(
                    onClick = { onInsertText(insert) },
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    modifier = Modifier.height(34.dp)
                ) {
                    Text(
                        label,
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
