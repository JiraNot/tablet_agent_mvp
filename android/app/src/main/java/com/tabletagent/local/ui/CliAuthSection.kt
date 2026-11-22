package com.tabletagent.local.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tabletagent.local.runtime.CliAuthState
import com.tabletagent.local.runtime.OAuthSessionState
import com.tabletagent.local.runtime.ProviderKeySummary
import com.tabletagent.local.runtime.SecureSecrets

@Composable
fun OfficialCliCard(
    title: String,
    subtitle: String,
    authState: CliAuthState,
    isInstalled: Boolean,
    isInstalling: Boolean,
    anyInstalling: Boolean,
    onSignIn: () -> Unit,
    onSignOut: () -> Unit,
    onInstallOrUpdate: (() -> Unit)? = null
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(title, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall)
                    Surface(
                        color = if (authState.signedIn) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer,
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            authState.label,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = if (authState.signedIn) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
                Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }

            if (isInstalled) {
                if (authState.signedIn && authState.method != "api_key") {
                    OutlinedButton(
                        onClick = onSignOut,
                        enabled = !anyInstalling,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        modifier = Modifier.height(30.dp)
                    ) {
                        Text("Sign Out", style = MaterialTheme.typography.labelSmall)
                    }
                } else {
                    FilledTonalButton(
                        onClick = onSignIn,
                        enabled = !anyInstalling,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        modifier = Modifier.height(30.dp)
                    ) {
                        Text(if (authState.signedIn) "Browser Login" else "Sign In", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }

            if (onInstallOrUpdate != null) {
                Button(
                    onClick = onInstallOrUpdate,
                    enabled = !anyInstalling,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    modifier = Modifier.height(30.dp)
                ) {
                    Text(
                        if (isInstalling) "Installing…" else if (isInstalled) "Update" else "Install",
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        }
    }
}

private data class ProviderKeySpec(
    val envName: String,
    val label: String,
    val placeholder: String,
    val configured: Boolean,
    val hint: String
)

@Composable
fun ProviderApiKeysCard(
    summary: ProviderKeySummary,
    busy: Boolean,
    onSaveKey: (envName: String, value: String) -> Unit
) {
    val specs = listOf(
        ProviderKeySpec(SecureSecrets.KEY_OPENAI, "OpenAI API Key (Codex)", "sk-...", summary.openAiConfigured, summary.openAiHint),
        ProviderKeySpec(SecureSecrets.KEY_ANTHROPIC, "Anthropic Key (Claude)", "sk-ant-...", summary.anthropicConfigured, summary.anthropicHint),
        ProviderKeySpec(SecureSecrets.KEY_GEMINI, "Gemini API Key (Optional)", "AIza...", summary.geminiConfigured, summary.geminiHint)
    )

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(10.dp),
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Key, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(6.dp))
                Text("Optional API Keys (KeyStore AES-256)", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                Text("Fallback when OAuth is not used", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                specs.forEach { spec ->
                    var entry by remember(spec.envName) { mutableStateOf("") }
                    Surface(
                        color = MaterialTheme.colorScheme.surface,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    spec.label,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    if (spec.configured) "Saved" else "Not set",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (spec.configured) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                                )
                            }
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Box(
                                    contentAlignment = Alignment.CenterStart,
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(28.dp)
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(6.dp))
                                        .padding(horizontal = 6.dp)
                                ) {
                                    if (entry.isEmpty()) {
                                        Text(
                                            spec.placeholder,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.outline,
                                            maxLines = 1
                                        )
                                    }
                                    BasicTextField(
                                        value = entry,
                                        onValueChange = { entry = it },
                                        singleLine = true,
                                        visualTransformation = PasswordVisualTransformation(),
                                        textStyle = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurface),
                                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                                Button(
                                    onClick = {
                                        val v = entry.trim()
                                        entry = ""
                                        onSaveKey(spec.envName, v)
                                    },
                                    enabled = !busy && entry.isNotBlank(),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                    modifier = Modifier.height(28.dp)
                                ) { Text("Save", style = MaterialTheme.typography.labelSmall) }
                                if (spec.configured) {
                                    OutlinedButton(
                                        onClick = {
                                            entry = ""
                                            onSaveKey(spec.envName, "")
                                        },
                                        enabled = !busy,
                                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                                        modifier = Modifier.height(28.dp)
                                    ) { Text("Clear", style = MaterialTheme.typography.labelSmall) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun CliOAuthDialog(
    state: OAuthSessionState,
    onSubmitCode: (String) -> Unit,
    onCancel: () -> Unit,
    onDone: () -> Unit
) {
    if (state.cliType.isBlank()) return
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var codeEntry by remember(state.cliType) { mutableStateOf("") }
    var copiedMsg by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { if (!state.active) onDone() },
        title = { Text("Sign in to ${state.cliType} CLI") },
        text = {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (state.active) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }

                if (state.deviceCode.isNotBlank()) {
                    Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.small) {
                        Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("One-time device code:", style = MaterialTheme.typography.labelSmall)
                                Text(state.deviceCode, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                            }
                            FilledTonalButton(onClick = {
                                clipboard.setText(AnnotatedString(state.deviceCode))
                                copiedMsg = true
                            }) { Text(if (copiedMsg) "Copied" else "Copy Code") }
                        }
                    }
                }

                if (state.oauthUrl.isNotBlank()) {
                    Button(
                        onClick = {
                            if (state.deviceCode.isNotBlank()) {
                                clipboard.setText(AnnotatedString(state.deviceCode))
                                copiedMsg = true
                            }
                            runCatching {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(state.oauthUrl)))
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.OpenInBrowser, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (state.deviceCode.isNotBlank()) "Copy Code & Open Browser" else "Open Sign-In in Browser")
                    }
                    Text(state.oauthUrl, style = MaterialTheme.typography.labelSmall, maxLines = 2, color = MaterialTheme.colorScheme.outline)
                }

                if (state.needsCodePaste && state.active) {
                    OutlinedTextField(
                        value = codeEntry,
                        onValueChange = { codeEntry = it },
                        label = { Text("Paste auth code if prompted") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedButton(
                        onClick = {
                            onSubmitCode(codeEntry)
                            codeEntry = ""
                        },
                        enabled = codeEntry.isNotBlank()
                    ) { Text("Submit Code") }
                }

                if (state.completed) {
                    Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.small) {
                        Text("Signed in cleanly! Embedded Bridge updated.", Modifier.padding(10.dp), fontWeight = FontWeight.Bold)
                    }
                }
                if (state.error.isNotBlank()) {
                    Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.small) {
                        Text(state.error, Modifier.padding(10.dp), style = MaterialTheme.typography.bodySmall)
                    }
                }

                if (state.logs.isNotEmpty()) {
                    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.small) {
                        Text(
                            state.logs.joinToString("\n"),
                            Modifier.fillMaxWidth().padding(8.dp),
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            }
        },
        confirmButton = {
            if (state.active) {
                TextButton(onClick = onCancel) { Text("Cancel") }
            } else {
                Button(onClick = onDone) { Text("Done") }
            }
        }
    )
}
