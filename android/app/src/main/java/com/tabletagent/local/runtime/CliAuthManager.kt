package com.tabletagent.local.runtime

import android.content.Context
import android.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class CliAuthState(
    val signedIn: Boolean = false,
    val method: String = "none",
    val label: String = "Not signed in"
)

data class AllCliAuthStatus(
    val codex: CliAuthState = CliAuthState(),
    val agy: CliAuthState = CliAuthState(),
    val claude: CliAuthState = CliAuthState(),
    val github: CliAuthState = CliAuthState(),
    val loaded: Boolean = false
)

data class GitHubRepoItem(
    val name: String,
    val fullName: String,
    val cloneUrl: String,
    val isPrivate: Boolean,
    val description: String,
    val updatedAt: String
)

data class OAuthSessionState(
    val cliType: String = "",
    val active: Boolean = false,
    val oauthUrl: String = "",
    val deviceCode: String = "",
    val needsCodePaste: Boolean = false,
    val logs: List<String> = emptyList(),
    val completed: Boolean = false,
    val error: String = ""
)

/**
 * Manages authentication status checks, API key provisioning, and interactive
 * browser OAuth / Device-Code sign-in inside the embedded PRoot runtime.
 */
object CliAuthManager {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val ansiRegex = Regex("\\u001B\\[[;?0-9]*[a-zA-Z]|\\u001B\\].*?\\u0007|\\r")
    private val urlRegex = Regex("""https://[^\s"'<>()]+""")
    private val deviceCodeRegex = Regex("""\b([A-Z0-9]{4}-[A-Z0-9]{4,5})\b""")

    private val _oauthState = MutableStateFlow(OAuthSessionState())
    val oauthState: StateFlow<OAuthSessionState> = _oauthState.asStateFlow()

    @Volatile
    private var activeProcess: Process? = null
    @Volatile
    private var activeJob: Job? = null
    private const val GITHUB_OAUTH_CLIENT_ID = "178c6fc778ccc68e1d6a"

    suspend fun getAuthStatus(context: Context): AllCliAuthStatus = withContext(Dispatchers.IO) {
        if (!BootstrapManager.isReady(context)) return@withContext AllCliAuthStatus()

        val hasOpenAiKey = SecureSecrets.hasKey(context, SecureSecrets.KEY_OPENAI)
        val hasAnthropicKey = SecureSecrets.hasKey(context, SecureSecrets.KEY_ANTHROPIC)
        val hasGeminiKey = SecureSecrets.hasKey(context, SecureSecrets.KEY_GEMINI)

        val codexStatus = checkCodexAuth(context, hasOpenAiKey)
        val claudeStatus = checkClaudeAuth(context, hasAnthropicKey)
        val agyStatus = checkAgyAuth(context, hasGeminiKey)
        val githubStatus = checkGitHubAuth(context)

        AllCliAuthStatus(
            codex = codexStatus,
            agy = agyStatus,
            claude = claudeStatus,
            github = githubStatus,
            loaded = true
        )
    }

    private fun checkGitHubAuth(context: Context): CliAuthState {
        val hasToken = SecureSecrets.hasKey(context, SecureSecrets.KEY_GITHUB)
        if (!hasToken) return CliAuthState(false, "none", "Not signed in")
        val ghUser = SecureSecrets.getKey(context, SecureSecrets.KEY_GITHUB_USER)
        val ghEmail = SecureSecrets.getKey(context, SecureSecrets.KEY_GITHUB_EMAIL)
        val label = when {
            ghUser.isNotBlank() && ghEmail.isNotBlank() -> "Signed in as @$ghUser ($ghEmail)"
            ghUser.isNotBlank() -> "Signed in as @$ghUser"
            else -> "Signed in (${SecureSecrets.maskedHint(context, SecureSecrets.KEY_GITHUB)})"
        }
        return CliAuthState(true, "oauth", label)
    }

    suspend fun fetchGitHubUserRepos(context: Context): Result<List<GitHubRepoItem>> = withContext(Dispatchers.IO) {
        runCatching {
            val token = SecureSecrets.getKey(context, SecureSecrets.KEY_GITHUB).trim()
            if (token.isBlank()) throw IllegalStateException("Not signed in to GitHub")
            val conn = (URL("https://api.github.com/user/repos?sort=updated&per_page=40&affiliation=owner,collaborator,organization_member").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10000
                readTimeout = 10000
                setRequestProperty("Authorization", "Bearer $token")
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "TabletAgentStudio/0.1")
            }
            val code = conn.responseCode
            val raw = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: "[]"
            conn.disconnect()
            if (code !in 200..299) {
                throw IllegalStateException("GitHub API HTTP $code")
            }
            val arr = JSONArray(raw)
            val list = mutableListOf<GitHubRepoItem>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(
                    GitHubRepoItem(
                        name = obj.optString("name"),
                        fullName = obj.optString("full_name"),
                        cloneUrl = obj.optString("clone_url"),
                        isPrivate = obj.optBoolean("private", false),
                        description = obj.optString("description", "").takeIf { it != "null" } ?: "",
                        updatedAt = obj.optString("updated_at", "").take(10)
                    )
                )
            }
            list
        }
    }

    private suspend fun checkCodexAuth(context: Context, hasApiKey: Boolean): CliAuthState {
        val res = EmbeddedProcessRunner.execute(context, "codex login status 2>&1", timeoutMs = 8000L)
        val out = res.stdout.trim()
        val loggedIn = res.exitCode == 0 && out.contains("Logged in", ignoreCase = true) &&
            !out.contains("Not logged in", ignoreCase = true)
        return when {
            loggedIn -> CliAuthState(true, "oauth", out.lines().firstOrNull() ?: "Signed in")
            hasApiKey -> CliAuthState(true, "api_key", "API Key configured")
            else -> CliAuthState(false, "none", "Not signed in")
        }
    }

    private suspend fun checkClaudeAuth(context: Context, hasApiKey: Boolean): CliAuthState {
        val res = EmbeddedProcessRunner.execute(context, "claude auth status 2>&1", timeoutMs = 8000L)
        val parsed = runCatching { JSONObject(res.stdout.trim()) }.getOrNull()
        val loggedIn = parsed?.optBoolean("loggedIn", false) == true
        val method = parsed?.optString("authMethod", "oauth") ?: "oauth"
        return when {
            loggedIn -> CliAuthState(true, method, "Signed in ($method)")
            hasApiKey -> CliAuthState(true, "api_key", "API Key configured")
            else -> CliAuthState(false, "none", "Not signed in")
        }
    }

    private suspend fun checkAgyAuth(context: Context, hasApiKey: Boolean): CliAuthState {
        val rootfs = BootstrapManager.getRootfsDir(context)
        val hasCredFiles = listOf(
            File(rootfs, "root/.antigravity"),
            File(rootfs, "root/.config/antigravity"),
            File(rootfs, "root/.gemini")
        ).any { dir ->
            dir.isDirectory && (dir.walkTopDown().any { it.isFile && it.name.contains("oauth", ignoreCase = true) || it.name.contains("cred", ignoreCase = true) || it.name.contains("token", ignoreCase = true) })
        }

        if (hasCredFiles) {
            val res = EmbeddedProcessRunner.execute(context, "agy models 2>&1", timeoutMs = 8000L)
            if (res.exitCode == 0 && !res.stdout.contains("Please sign in", ignoreCase = true)) {
                return CliAuthState(true, "oauth", "Google OAuth signed in")
            }
        }
        return if (hasApiKey) {
            CliAuthState(true, "api_key", "API Key configured")
        } else {
            CliAuthState(false, "none", "Not signed in")
        }
    }

    /** Save an API key securely in KeyStore, bind Codex token if applicable, and restart bridge. */
    suspend fun saveProviderApiKey(
        context: Context,
        envName: String,
        value: String
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val trimmed = value.trim()
            SecureSecrets.saveKey(context, envName, trimmed)

            if (envName == SecureSecrets.KEY_OPENAI) {
                if (trimmed.isNotBlank()) {
                    // Feed API key via process environment stdin so it never appears in ps/args/logs.
                    val proc = EmbeddedProcessRunner.startDaemon(
                        context = context,
                        command = "printenv OPENAI_API_KEY | codex login --with-api-key",
                        extraEnv = mapOf(SecureSecrets.KEY_OPENAI to trimmed)
                    )
                    proc.waitFor()
                } else {
                    EmbeddedProcessRunner.execute(context, "codex logout 2>&1", timeoutMs = 8000L)
                }
            }

            if (BootstrapManager.isReady(context)) {
                EmbeddedBridgeManager.restart(context)
            }

            if (trimmed.isBlank()) "$envName cleared" else "$envName saved to KeyStore & synced"
        }
    }

    suspend fun logoutCli(context: Context, cliType: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            when (cliType) {
                "Codex" -> EmbeddedProcessRunner.execute(context, "codex logout 2>&1", timeoutMs = 8000L)
                "Claude" -> EmbeddedProcessRunner.execute(context, "claude auth logout 2>&1", timeoutMs = 8000L)
                "AGY" -> {
                    val rootfs = BootstrapManager.getRootfsDir(context)
                    listOf("root/.antigravity", "root/.config/antigravity", "root/.gemini").forEach { rel ->
                        File(rootfs, rel).deleteRecursively()
                    }
                }
                "GitHub" -> {
                    SecureSecrets.saveKey(context, SecureSecrets.KEY_GITHUB, "")
                    SecureSecrets.saveKey(context, SecureSecrets.KEY_GITHUB_USER, "")
                    SecureSecrets.saveKey(context, SecureSecrets.KEY_GITHUB_EMAIL, "")
                    val rootfs = BootstrapManager.getRootfsDir(context)
                    if (rootfs.isDirectory) {
                        runCatching {
                            File(rootfs, "root/.git-credentials").delete()
                            EmbeddedProcessRunner.execute(context, "git config --global --unset credential.helper 2>&1")
                        }
                    }
                    if (BootstrapManager.isReady(context)) {
                        EmbeddedBridgeManager.restart(context)
                    }
                }
            }
            "$cliType signed out"
        }
    }

    private fun startGitHubDeviceOAuth(context: Context) {
        _oauthState.value = OAuthSessionState(
            cliType = "GitHub",
            active = true,
            logs = listOf("Requesting GitHub OAuth device code...")
        )
        activeJob = scope.launch {
            try {
                val form = "client_id=${URLEncoder.encode(GITHUB_OAUTH_CLIENT_ID, "UTF-8")}&scope=${URLEncoder.encode("repo read:user user:email", "UTF-8")}"
                val reqConn = (URL("https://github.com/login/device/code").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 10000
                    readTimeout = 10000
                    doOutput = true
                    setRequestProperty("Accept", "application/json")
                    setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                    setRequestProperty("User-Agent", "TabletAgentStudio/0.1")
                }
                reqConn.outputStream.use { it.write(form.toByteArray(Charsets.UTF_8)) }
                val reqCode = reqConn.responseCode
                val rawResp = (if (reqCode in 200..299) reqConn.inputStream else reqConn.errorStream)
                    ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: "{}"
                reqConn.disconnect()

                val json = JSONObject(rawResp)
                val deviceCode = json.optString("device_code")
                val userCode = json.optString("user_code")
                val verifyUri = json.optString("verification_uri", "https://github.com/login/device")
                var intervalSec = json.optInt("interval", 5).coerceAtLeast(5)
                val expiresIn = json.optInt("expires_in", 900)

                if (deviceCode.isBlank() || userCode.isBlank()) {
                    _oauthState.value = _oauthState.value.copy(
                        active = false,
                        error = "GitHub did not return a device code: $rawResp"
                    )
                    return@launch
                }

                _oauthState.value = _oauthState.value.copy(
                    oauthUrl = verifyUri,
                    deviceCode = userCode,
                    logs = listOf(
                        "1. Tap 'Copy Code & Open Browser' below.",
                        "2. Enter code $userCode on GitHub and tap Authorize.",
                        "Waiting for GitHub authorization..."
                    )
                )

                val deadline = System.currentTimeMillis() + (expiresIn * 1000L)
                while (isActive && System.currentTimeMillis() < deadline && _oauthState.value.active) {
                    delay(intervalSec * 1000L)
                    if (!isActive || !_oauthState.value.active) break

                    val pollForm = "client_id=${URLEncoder.encode(GITHUB_OAUTH_CLIENT_ID, "UTF-8")}" +
                        "&device_code=${URLEncoder.encode(deviceCode, "UTF-8")}" +
                        "&grant_type=${URLEncoder.encode("urn:ietf:params:oauth:grant-type:device_code", "UTF-8")}"
                    val pollConn = (URL("https://github.com/login/oauth/access_token").openConnection() as HttpURLConnection).apply {
                        requestMethod = "POST"
                        connectTimeout = 10000
                        readTimeout = 10000
                        doOutput = true
                        setRequestProperty("Accept", "application/json")
                        setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                        setRequestProperty("User-Agent", "TabletAgentStudio/0.1")
                    }
                    pollConn.outputStream.use { it.write(pollForm.toByteArray(Charsets.UTF_8)) }
                    val pollCode = pollConn.responseCode
                    val pollRaw = (if (pollCode in 200..299) pollConn.inputStream else pollConn.errorStream)
                        ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: "{}"
                    pollConn.disconnect()

                    val pollJson = JSONObject(pollRaw)
                    val accessToken = pollJson.optString("access_token")
                    val err = pollJson.optString("error")

                    if (accessToken.isNotBlank()) {
                        // Fetch GitHub user profile and verified email
                        var username = ""
                        var authorName = ""
                        var email = ""

                        runCatching {
                            val uConn = (URL("https://api.github.com/user").openConnection() as HttpURLConnection).apply {
                                requestMethod = "GET"
                                connectTimeout = 8000
                                readTimeout = 8000
                                setRequestProperty("Authorization", "Bearer $accessToken")
                                setRequestProperty("Accept", "application/vnd.github+json")
                                setRequestProperty("User-Agent", "TabletAgentStudio/0.1")
                            }
                            val uCode = uConn.responseCode
                            val uRaw = (if (uCode in 200..299) uConn.inputStream else uConn.errorStream)
                                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: "{}"
                            uConn.disconnect()
                            val uObj = JSONObject(uRaw)
                            username = uObj.optString("login", "")
                            authorName = uObj.optString("name", username)
                            email = uObj.optString("email", "")
                        }

                        if (email.isBlank() || email == "null") {
                            runCatching {
                                val eConn = (URL("https://api.github.com/user/emails").openConnection() as HttpURLConnection).apply {
                                    requestMethod = "GET"
                                    connectTimeout = 8000
                                    readTimeout = 8000
                                    setRequestProperty("Authorization", "Bearer $accessToken")
                                    setRequestProperty("Accept", "application/vnd.github+json")
                                    setRequestProperty("User-Agent", "TabletAgentStudio/0.1")
                                }
                                val eCode = eConn.responseCode
                                val eRaw = (if (eCode in 200..299) eConn.inputStream else eConn.errorStream)
                                    ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: "[]"
                                eConn.disconnect()
                                val emailsArr = JSONArray(eRaw)
                                for (i in 0 until emailsArr.length()) {
                                    val item = emailsArr.getJSONObject(i)
                                    if (item.optBoolean("primary", false)) {
                                        email = item.optString("email")
                                        break
                                    }
                                }
                                if (email.isBlank() && emailsArr.length() > 0) {
                                    email = emailsArr.getJSONObject(0).optString("email")
                                }
                            }
                        }

                        if (email.isBlank() || email == "null") {
                            email = if (username.isNotBlank()) "$username@users.noreply.github.com" else "agent@tablet.local"
                        }
                        if (authorName.isBlank() || authorName == "null") {
                            authorName = username.ifBlank { "Tablet Developer" }
                        }

                        SecureSecrets.saveKey(context, SecureSecrets.KEY_GITHUB, accessToken)
                        if (username.isNotBlank()) {
                            SecureSecrets.saveKey(context, SecureSecrets.KEY_GITHUB_USER, username)
                        }
                        SecureSecrets.saveKey(context, SecureSecrets.KEY_GITHUB_EMAIL, email)

                        // Configure PRoot global git credentials and commit author
                        val rootfs = BootstrapManager.getRootfsDir(context)
                        if (rootfs.isDirectory) {
                            runCatching {
                                val gitCreds = File(rootfs, "root/.git-credentials")
                                gitCreds.parentFile?.mkdirs()
                                gitCreds.writeText("https://${username}:${accessToken}@github.com\n", Charsets.UTF_8)
                                EmbeddedProcessRunner.execute(context, "git config --global credential.helper store 2>&1")
                                EmbeddedProcessRunner.execute(context, "git config --global user.name \"$authorName\" 2>&1")
                                EmbeddedProcessRunner.execute(context, "git config --global user.email \"$email\" 2>&1")
                            }
                        }

                        if (BootstrapManager.isReady(context)) {
                            EmbeddedBridgeManager.restart(context)
                        }

                        _oauthState.value = _oauthState.value.copy(
                            active = false,
                            completed = true,
                            logs = _oauthState.value.logs + listOf(
                                "✓ Signed in to GitHub as @$username ($email)!",
                                "✓ Git credentials and commit author configured in PRoot.",
                                "✓ Remote Push, Pull, and Clone operations unlocked!"
                            )
                        )
                        return@launch
                    } else if (err == "slow_down") {
                        intervalSec += 5
                    } else if (err == "authorization_pending") {
                        // Keep polling
                    } else if (err.isNotBlank()) {
                        _oauthState.value = _oauthState.value.copy(
                            active = false,
                            error = pollJson.optString("error_description", "GitHub OAuth error: $err")
                        )
                        return@launch
                    }
                }
            } catch (e: Exception) {
                if (_oauthState.value.active) {
                    _oauthState.value = _oauthState.value.copy(
                        active = false,
                        error = e.message ?: "GitHub OAuth failed"
                    )
                }
            }
        }
    }

    fun startOAuthLogin(context: Context, cliType: String) {
        cancelOAuth()
        if (cliType == "GitHub") {
            startGitHubDeviceOAuth(context)
            return
        }
        if (!BootstrapManager.isReady(context)) {
            _oauthState.value = OAuthSessionState(cliType = cliType, error = "Embedded runtime is not ready")
            return
        }

        _oauthState.value = OAuthSessionState(
            cliType = cliType,
            active = true,
            needsCodePaste = (cliType == "Claude" || cliType == "AGY"),
            logs = listOf("Starting $cliType authentication in PRoot...")
        )

        scope.launch {
            val rootfs = BootstrapManager.getRootfsDir(context)
            val tmpDir = File(rootfs, "tmp").apply { mkdirs() }
            val urlFile = File(tmpDir, "oauth_url.txt").apply { delete() }
            File(tmpDir, "oauth_code_input.txt").delete()
            val shimScript = "#!/bin/sh\nprintf '%s\\n' \"\$1\" > /tmp/oauth_url.txt.tmp && mv /tmp/oauth_url.txt.tmp /tmp/oauth_url.txt\n"
            val xdgOpen = File(rootfs, "usr/bin/xdg-open").apply {
                parentFile?.mkdirs()
                writeText(shimScript)
                setReadable(true, false)
                setExecutable(true, false)
            }
            File(tmpDir, "oauth_capture.sh").apply {
                writeText(shimScript)
                setReadable(true, false)
                setExecutable(true, false)
            }

            val command = when (cliType) {
                "Codex" -> "codex login --device-auth"
                "Claude" -> writePtyRunnerScript(tmpDir, "claude auth login")
                "AGY" -> writePtyRunnerScript(tmpDir, "agy", autoSelectEnterPrompt = "Google OAuth", autoDetectAgyAuth = true)
                else -> {
                    _oauthState.value = _oauthState.value.copy(active = false, error = "Unknown CLI: $cliType")
                    return@launch
                }
            }

            val proc = try {
                EmbeddedProcessRunner.startDaemon(
                    context = context,
                    command = command,
                    workingDir = "/root",
                    extraEnv = mapOf("BROWSER" to "/usr/bin/${xdgOpen.name}")
                )
            } catch (e: Exception) {
                _oauthState.value = _oauthState.value.copy(active = false, error = e.message ?: "Failed to launch auth")
                return@launch
            }
            activeProcess = proc

            var capturedViaShim = false
            // Poll /tmp/oauth_url.txt captured from xdg-open/$BROWSER shim for exact unwrapped URLs.
            val urlWatcher = launch {
                while (isActive && proc.isAlive) {
                    if (urlFile.isFile) {
                        val captured = runCatching { urlFile.readText(Charsets.UTF_8).trim() }.getOrDefault("")
                        if (captured.startsWith("https://")) {
                            capturedViaShim = true
                            if (_oauthState.value.oauthUrl != captured) {
                                _oauthState.value = _oauthState.value.copy(oauthUrl = captured)
                            }
                        }
                    }
                    delay(200)
                }
            }

            runCatching {
                BufferedReader(InputStreamReader(proc.inputStream)).use { reader ->
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        val clean = ansiRegex.replace(line ?: "", "").trim()
                        if (clean.isEmpty()) continue

                        var next = _oauthState.value
                        // For Codex (and fallback only if shim hasn't captured a clean URL), scan non-truncated URL lines.
                        if (!capturedViaShim && cliType != "AGY") {
                            val matchedUrl = urlRegex.find(clean)?.value
                            if (matchedUrl != null && !matchedUrl.endsWith("=") && next.oauthUrl.isBlank()) {
                                next = next.copy(oauthUrl = matchedUrl)
                            }
                        }
                        if (cliType == "Codex") {
                            val codeMatch = deviceCodeRegex.find(clean)
                            if (codeMatch != null) {
                                next = next.copy(deviceCode = codeMatch.groupValues[1])
                            }
                        }
                        if (clean.contains("Paste code", ignoreCase = true) ||
                            clean.contains("verification code", ignoreCase = true)
                        ) {
                            next = next.copy(needsCodePaste = true)
                        }
                        next = next.copy(logs = (next.logs + clean).takeLast(14))
                        _oauthState.value = next
                    }
                }
            }

            val exitCode = runCatching { proc.waitFor() }.getOrDefault(-1)
            urlWatcher.cancel()
            if (activeProcess === proc) activeProcess = null

            val cur = _oauthState.value
            if (cur.active) {
                if (exitCode == 0) {
                    _oauthState.value = cur.copy(active = false, completed = true)
                    EmbeddedBridgeManager.restart(context)
                } else {
                    _oauthState.value = cur.copy(
                        active = false,
                        error = cur.error.ifBlank { "Sign-in did not complete (exit $exitCode)" }
                    )
                }
            }
        }
    }

    fun submitPastedCode(context: Context, code: String) {
        val trimmed = code.trim()
        if (trimmed.isEmpty()) return
        _oauthState.value = _oauthState.value.copy(
            logs = (_oauthState.value.logs + "Submitting verification code...").takeLast(14),
            error = ""
        )
        scope.launch {
            runCatching {
                val tmpDir = File(BootstrapManager.getRootfsDir(context), "tmp").apply { mkdirs() }
                val tmpIn = File(tmpDir, "oauth_code_input.txt.tmp")
                val target = File(tmpDir, "oauth_code_input.txt")
                tmpIn.writeText(trimmed, Charsets.UTF_8)
                tmpIn.renameTo(target)
            }
        }
    }

    fun cancelOAuth() {
        activeJob?.cancel()
        activeJob = null
        val proc = activeProcess
        activeProcess = null
        if (proc != null) {
            runCatching { proc.destroy() }
            runCatching { if (proc.isAlive) proc.destroyForcibly() }
        }
        _oauthState.value = OAuthSessionState()
    }

    /**
     * Writes a standalone Python PTY runner inside /tmp so fd 0 is never piped,
     * eliminating EOF spin loops and delivering pasted codes via atomic file drop + CR.
     */
    private fun writePtyRunnerScript(
        tmpDir: File,
        cliCommand: String,
        autoSelectEnterPrompt: String? = null,
        autoDetectAgyAuth: Boolean = false
    ): String {
        val promptLiteral = if (autoSelectEnterPrompt != null) {
            Base64.encodeToString(autoSelectEnterPrompt.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        } else ""
        val detectFlag = if (autoDetectAgyAuth) "True" else "False"
        val pyScript = """
import base64, fcntl, os, pty, select, signal, struct, sys, termios, time

CODE_FILE = '/tmp/oauth_code_input.txt'

def has_agy_creds():
    for base in ('/root/.antigravity', '/root/.config/antigravity', '/root/.gemini'):
        if os.path.isdir(base):
            for dp, _, files in os.walk(base):
                for fn in files:
                    low = fn.lower()
                    if any(k in low for k in ('oauth', 'cred', 'token', 'auth')) and os.path.getsize(os.path.join(dp, fn)) > 10:
                        return True
    return False

master, fd = pty.openpty()
try:
    fcntl.ioctl(fd, termios.TIOCSWINSZ, struct.pack('HHHH', 40, 1000, 0, 0))
except Exception:
    pass
pid = os.fork()
if pid == 0:
    os.close(master)
    os.setsid()
    try:
        fcntl.ioctl(fd, termios.TIOCSCTTY, 0)
    except Exception:
        pass
    os.dup2(fd, 0); os.dup2(fd, 1); os.dup2(fd, 2)
    if fd > 2:
        os.close(fd)
    os.execvp('/bin/bash', ['/bin/bash', '-c', '$cliCommand'])
os.close(fd)

prompt_b = base64.b64decode('$promptLiteral') if '$promptLiteral' else b''
detect_agy = $detectFlag
sent_enter = False
submitted_code = False
had_creds_initially = has_agy_creds() if detect_agy else False
buf_acc = b''
exit_override = None

while True:
    try:
        r, _, _ = select.select([master], [], [], 0.15)
    except Exception:
        break
    if master in r:
        try:
            data = os.read(master, 8192)
        except OSError:
            break
        if not data:
            break
        os.write(1, data)
        buf_acc = (buf_acc + data)[-6144:]
        if prompt_b and not sent_enter and prompt_b in buf_acc:
            sent_enter = True
            time.sleep(0.35)
            try:
                os.write(master, b'\r')
            except OSError:
                pass
        if detect_agy and submitted_code:
            low_buf = buf_acc.lower()
            if b'got an error:' in low_buf or b'token exchange failed' in low_buf:
                time.sleep(0.35)
                try:
                    os.kill(pid, signal.SIGTERM)
                except OSError:
                    pass
                exit_override = 1
                break
            if b'successfully' in low_buf or b'signed in' in low_buf:
                time.sleep(0.4)
                try:
                    os.kill(pid, signal.SIGTERM)
                except OSError:
                    pass
                exit_override = 0
                break
    if os.path.isfile(CODE_FILE):
        try:
            with open(CODE_FILE, 'r', encoding='utf-8') as f:
                code_txt = f.read().strip()
            os.remove(CODE_FILE)
            if code_txt:
                os.write(master, code_txt.encode('utf-8'))
                time.sleep(0.15)
                os.write(master, b'\r')
                submitted_code = True
                buf_acc = b''
        except Exception:
            pass
    if detect_agy and (submitted_code or not had_creds_initially) and sent_enter and has_agy_creds():
        time.sleep(0.4)
        try:
            os.kill(pid, signal.SIGTERM)
        except OSError:
            pass
        exit_override = 0
        break

try:
    _, status = os.waitpid(pid, 0)
    rc = os.waitstatus_to_exitcode(status) if hasattr(os, 'waitstatus_to_exitcode') else (status >> 8)
except Exception:
    rc = 0
sys.exit(exit_override if exit_override is not None else rc)
""".trimIndent()
        val runnerFile = File(tmpDir, "oauth_pty_runner.py").apply {
            writeText(pyScript, Charsets.UTF_8)
            setReadable(true, false)
        }
        return "exec python3 -u /tmp/${runnerFile.name}"
    }
}
