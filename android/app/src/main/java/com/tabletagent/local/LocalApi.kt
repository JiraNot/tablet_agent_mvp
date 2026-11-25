package com.tabletagent.local

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Restrict all API traffic to the bridge on the current Android device. */
class LocalApi(private val deviceToken: String, port: Int = 8765) {
    private val host = "http://127.0.0.1:$port"
    private fun encode(s: String) = URLEncoder.encode(s, "UTF-8")

    private suspend fun request(method: String, path: String, body: JSONObject? = null): JSONObject =
        withContext(Dispatchers.IO) {
            val conn = URL(host + path).openConnection() as HttpURLConnection
            try {
                conn.requestMethod = method
                conn.connectTimeout = 3000
                conn.readTimeout = when {
                    path == "/v1/projects" && method == "POST" -> 180000
                    path.endsWith("/attachments") && method == "POST" -> 60000
                    path.contains("/git/") && method == "POST" -> 90000
                    else -> 12000
                }
                conn.setRequestProperty("Authorization", "Bearer $deviceToken")
                conn.setRequestProperty("Accept", "application/json")
                if (body != null) {
                    conn.doOutput = true
                    conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                    conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                }
                val code = conn.responseCode
                val raw = (if (code in 200..299) conn.inputStream else conn.errorStream)
                    ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: "{}"
                val parsed = JSONObject(raw)
                if (code !in 200..299) throw IllegalStateException(parsed.optString("error", "HTTP $code"))
                parsed
            } finally { conn.disconnect() }
        }

    suspend fun healthInfo(): JSONObject? = withContext(Dispatchers.IO) {
        try {
            val conn = URL(host + "/health").openConnection() as HttpURLConnection
            conn.connectTimeout = 2000; conn.readTimeout = 2000
            try {
                if (conn.responseCode == 200) {
                    val raw = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                    JSONObject(raw)
                } else null
            } finally { conn.disconnect() }
        } catch (_: Exception) { null }
    }

    suspend fun health(): Boolean = healthInfo()?.optBoolean("ok", false) == true
    suspend fun projects(): JSONArray = request("GET", "/v1/projects").getJSONArray("projects")
    suspend fun createProject(name: String, url: String, token: String = "") = request("POST", "/v1/projects",
        JSONObject().put("name", name).put("git_url", url).put("token", token))
    suspend fun tasks(project: String): JSONArray = request("GET", "/v1/tasks?project=${encode(project)}").getJSONArray("tasks")
    suspend fun agents(): JSONArray = request("GET", "/v1/agents").getJSONArray("agents")
    suspend fun startTask(project: String, prompt: String, mode: String, agent: String = "codex", model: String = "", taskId: String = "", useWorktree: Boolean = false): JSONObject = request("POST", "/v1/tasks",
        JSONObject().put("project", project).put("prompt", prompt).put("mode", mode).put("agent", agent).put("model", model).put("task_id", taskId).put("use_worktree", useWorktree))
    suspend fun eventsPage(taskId: String, after: Int): JSONObject = request("GET", "/v1/tasks/${encode(taskId)}/events?after=$after")
    suspend fun events(taskId: String, after: Int): JSONArray = eventsPage(taskId, after).getJSONArray("events")
    suspend fun settings(): JSONObject = request("GET", "/v1/settings")
    suspend fun updateSettings(codexProotBypass: Boolean): JSONObject = request("PUT", "/v1/settings",
        JSONObject().put("codex_proot_sandbox_bypass", codexProotBypass))
    suspend fun approvals(taskId: String): JSONArray = request("GET", "/v1/tasks/${encode(taskId)}/approvals").getJSONArray("approvals")
    suspend fun respondApproval(taskId: String, approvalId: String, allow: Boolean): JSONObject = request("POST",
        "/v1/tasks/${encode(taskId)}/approvals/${encode(approvalId)}",
        JSONObject().put("decision", if (allow) "allow" else "deny"))
    suspend fun cancelTask(taskId: String) = request("POST", "/v1/tasks/${encode(taskId)}/cancel", JSONObject())
    suspend fun files(project: String, dir: String = "."): JSONArray = request("GET", "/v1/projects/${encode(project)}/files?path=${encode(dir)}").getJSONArray("files")
    suspend fun file(project: String, path: String): JSONObject = request("GET", "/v1/projects/${encode(project)}/file?path=${encode(path)}")
    suspend fun saveFile(project: String, path: String, text: String, createDirs: Boolean = false): JSONObject =
        request("PUT", "/v1/projects/${encode(project)}/file?path=${encode(path)}",
            JSONObject().put("content", text).put("create_dirs", createDirs))
    suspend fun gitStatus(project: String): JSONObject = request("GET", "/v1/projects/${encode(project)}/git/status")
    suspend fun gitDiff(project: String): JSONObject = request("GET", "/v1/projects/${encode(project)}/git/diff")
    suspend fun gitCommit(project: String, message: String, userName: String = "", userEmail: String = ""): JSONObject =
        request("POST", "/v1/projects/${encode(project)}/git/commit",
            JSONObject().put("message", message).put("user_name", userName).put("user_email", userEmail))
    suspend fun gitRollback(project: String, sha: String = "HEAD"): JSONObject = request("POST", "/v1/projects/${encode(project)}/git/rollback", JSONObject().put("sha", sha))
    suspend fun projectInstructions(project: String): JSONObject = request("GET", "/v1/projects/${encode(project)}/instructions")
    suspend fun saveProjectInstructions(project: String, content: String): JSONObject = request("PUT", "/v1/projects/${encode(project)}/instructions", JSONObject().put("content", content))
    suspend fun previewStatus(project: String): JSONObject = request("GET", "/v1/projects/${encode(project)}/preview")
    suspend fun previewStart(project: String): JSONObject = request("POST", "/v1/projects/${encode(project)}/preview/start", JSONObject())
    suspend fun previewStop(project: String): JSONObject = request("POST", "/v1/projects/${encode(project)}/preview/stop", JSONObject())
    suspend fun exportProject(project: String, includeEnv: Boolean = false): JSONObject = request("POST", "/v1/projects/${encode(project)}/export", JSONObject().put("include_env", includeEnv))
    suspend fun importProject(zipPath: String, name: String = ""): JSONObject = request("POST", "/v1/projects/import", JSONObject().put("zip_path", zipPath).put("name", name))
    suspend fun listApks(project: String): JSONArray = request("GET", "/v1/projects/${encode(project)}/apks").optJSONArray("apks") ?: JSONArray()
    suspend fun hermesMemory(project: String): JSONObject = request("GET", "/v1/projects/${encode(project)}/memory")
    suspend fun uploadAttachment(project: String, filename: String, base64Data: String): JSONObject =
        request("POST", "/v1/projects/${encode(project)}/attachments",
            JSONObject().put("filename", filename).put("data_b64", base64Data))

    // Phase 2 & 3: Project Management, Git & PTY Terminal
    suspend fun deleteProject(project: String): JSONObject = request("DELETE", "/v1/projects/${encode(project)}")
    suspend fun renameProject(project: String, newName: String): JSONObject = request("POST", "/v1/projects/${encode(project)}/rename", JSONObject().put("new_name", newName))
    suspend fun searchFiles(project: String, query: String, caseSensitive: Boolean = false): JSONObject =
        request("GET", "/v1/projects/${encode(project)}/search?q=${encode(query)}&case_sensitive=$caseSensitive")

    suspend fun gitBranches(project: String): JSONObject = request("GET", "/v1/projects/${encode(project)}/git/branches")
    suspend fun gitCheckout(project: String, branch: String, create: Boolean = false): JSONObject =
        request("POST", "/v1/projects/${encode(project)}/git/checkout", JSONObject().put("branch", branch).put("create", create))
    suspend fun gitPush(project: String, remote: String = "origin", branch: String = "", token: String = ""): JSONObject =
        request("POST", "/v1/projects/${encode(project)}/git/push", JSONObject().put("remote", remote).put("branch", branch).put("token", token))
    suspend fun gitCreateAndPush(project: String, repoName: String = "", isPrivate: Boolean = true, description: String = "", token: String = ""): JSONObject =
        request("POST", "/v1/projects/${encode(project)}/git/create-and-push", JSONObject().put("repo_name", repoName).put("private", isPrivate).put("description", description).put("token", token))
    suspend fun gitPull(project: String, remote: String = "origin", branch: String = "", token: String = ""): JSONObject =
        request("POST", "/v1/projects/${encode(project)}/git/pull", JSONObject().put("remote", remote).put("branch", branch).put("token", token))
    suspend fun gitLog(project: String, limit: Int = 50): JSONObject =
        request("GET", "/v1/projects/${encode(project)}/git/log?limit=$limit")
    suspend fun gitDiscard(project: String, path: String = "", all: Boolean = false): JSONObject =
        request("POST", "/v1/projects/${encode(project)}/git/discard", JSONObject().put("path", path).put("all", all))
    suspend fun gitFileDiff(project: String, path: String): JSONObject =
        request("GET", "/v1/projects/${encode(project)}/git/file-diff?path=${encode(path)}")
    suspend fun gitAiCommit(project: String): JSONObject =
        request("POST", "/v1/projects/${encode(project)}/git/ai-commit", JSONObject())
    suspend fun gitCheckpoint(project: String, label: String = "apply-code"): JSONObject =
        request("POST", "/v1/projects/${encode(project)}/git/checkpoint", JSONObject().put("label", label))
    suspend fun gitDraftPr(project: String, base: String = "main"): JSONObject =
        request("POST", "/v1/projects/${encode(project)}/git/draft-pr", JSONObject().put("base", base))
    suspend fun gitCreatePr(project: String, title: String, body: String, head: String, base: String = "main", token: String = ""): JSONObject =
        request("POST", "/v1/projects/${encode(project)}/git/create-pr", JSONObject().put("title", title).put("body", body).put("head", head).put("base", base).put("token", token))
    suspend fun gitResolveConflicts(project: String): JSONObject =
        request("POST", "/v1/projects/${encode(project)}/git/resolve-conflicts", JSONObject())

    suspend fun terminalStart(project: String, cols: Int = 80, rows: Int = 24): JSONObject =
        request("POST", "/v1/projects/${encode(project)}/terminal/start", JSONObject().put("cols", cols).put("rows", rows))
    suspend fun terminalInput(project: String, data: String): JSONObject =
        request("POST", "/v1/projects/${encode(project)}/terminal/input", JSONObject().put("data", data))
    suspend fun terminalPoll(project: String, after: Int): JSONObject =
        request("GET", "/v1/projects/${encode(project)}/terminal/poll?after=$after")
    suspend fun terminalResize(project: String, cols: Int, rows: Int): JSONObject =
        request("POST", "/v1/projects/${encode(project)}/terminal/resize", JSONObject().put("cols", cols).put("rows", rows))
    suspend fun terminalStop(project: String): JSONObject =
        request("POST", "/v1/projects/${encode(project)}/terminal/stop", JSONObject())

    // Worktrees & Offline Local SLM
    suspend fun listWorktrees(project: String): JSONArray =
        request("GET", "/v1/projects/${encode(project)}/worktrees").optJSONArray("worktrees") ?: JSONArray()
    suspend fun createWorktree(project: String, taskId: String): JSONObject =
        request("POST", "/v1/projects/${encode(project)}/worktrees", JSONObject().put("task_id", taskId))
    suspend fun deleteWorktree(project: String, taskId: String): JSONObject =
        request("DELETE", "/v1/projects/${encode(project)}/worktrees", JSONObject().put("task_id", taskId))
    suspend fun pruneWorktrees(project: String): JSONObject =
        request("POST", "/v1/projects/${encode(project)}/worktrees/prune", JSONObject())

    suspend fun slmStatus(): JSONObject = request("GET", "/v1/slm/status")
    suspend fun slmSetup(action: String = "check", model: String = "qwen2.5-coder-1.5b"): JSONObject =
        request("POST", "/v1/slm/setup", JSONObject().put("action", action).put("model", model))
    suspend fun slmDownload(model: String = "qwen2.5-coder-1.5b"): JSONObject =
        request("POST", "/v1/slm/download", JSONObject().put("model", model))

    // Super Suite: Templates, Audit, PWA & Attachments
    suspend fun initTemplate(project: String, templateId: String): JSONObject =
        request("POST", "/v1/templates/init", JSONObject().put("project", project).put("template_id", templateId))
    suspend fun gitAudit(project: String): JSONObject =
        request("POST", "/v1/projects/${encode(project)}/git/audit", JSONObject())
    suspend fun pwaConfigure(project: String): JSONObject =
        request("POST", "/v1/projects/${encode(project)}/pwa/configure", JSONObject())
    suspend fun saveAttachment(project: String, filename: String, dataB64: String): JSONObject =
        request("POST", "/v1/projects/${encode(project)}/attachments", JSONObject().put("filename", filename).put("data_b64", dataB64))
}
