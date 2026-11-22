package com.tabletagent.local.runtime

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class CliVersions(
    val node: String? = null,
    val git: String? = null,
    val python: String? = null,
    val codex: String? = null,
    val agy: String? = null,
    val claude: String? = null
)

object CliInstaller {

    suspend fun getInstalledVersions(context: Context): CliVersions = withContext(Dispatchers.IO) {
        if (!BootstrapManager.isReady(context)) {
            return@withContext CliVersions()
        }

        val nodeRes = EmbeddedProcessRunner.execute(context, "node -v")
        val gitRes = EmbeddedProcessRunner.execute(context, "git --version")
        val pyRes = EmbeddedProcessRunner.execute(context, "python3 --version")
        val codexRes = EmbeddedProcessRunner.execute(context, "codex --version")
        val agyRes = EmbeddedProcessRunner.execute(context, "agy --version")
        val claudeRes = EmbeddedProcessRunner.execute(context, "claude --version")

        CliVersions(
            node = if (nodeRes.exitCode == 0) nodeRes.stdout.trim() else null,
            git = if (gitRes.exitCode == 0) gitRes.stdout.trim() else null,
            python = if (pyRes.exitCode == 0) pyRes.stdout.trim() else null,
            codex = if (codexRes.exitCode == 0) codexRes.stdout.trim() else null,
            agy = if (agyRes.exitCode == 0) agyRes.stdout.trim() else null,
            claude = if (claudeRes.exitCode == 0) claudeRes.stdout.trim() else null
        )
    }

    suspend fun installCodex(context: Context): ExecutionResult = withContext(Dispatchers.IO) {
        EmbeddedProcessRunner.execute(context, "curl -fsSL https://chatgpt.com/codex/install.sh | sh", timeoutMs = 180_000L)
    }

    suspend fun installAgy(context: Context): ExecutionResult = withContext(Dispatchers.IO) {
        EmbeddedProcessRunner.execute(context, "curl -fsSL https://antigravity.google/cli/install.sh | bash", timeoutMs = 180_000L)
    }

    suspend fun installClaude(context: Context): ExecutionResult = withContext(Dispatchers.IO) {
        EmbeddedProcessRunner.execute(context, "curl -fsSL https://claude.ai/install.sh | bash", timeoutMs = 180_000L)
    }
}
