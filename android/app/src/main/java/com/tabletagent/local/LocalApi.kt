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
                conn.readTimeout = if (path == "/v1/projects" && method == "POST") 180000 else 12000
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
    suspend fun createProject(name: String, url: String) = request("POST", "/v1/projects",
        JSONObject().put("name", name).put("git_url", url))
    suspend fun tasks(project: String): JSONArray = request("GET", "/v1/tasks?project=${encode(project)}").getJSONArray("tasks")
    suspend fun agents(): JSONArray = request("GET", "/v1/agents").getJSONArray("agents")
    suspend fun startTask(project: String, prompt: String, mode: String, agent: String = "codex", model: String = ""): JSONObject = request("POST", "/v1/tasks",
        JSONObject().put("project", project).put("prompt", prompt).put("mode", mode).put("agent", agent).put("model", model))
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
    suspend fun saveFile(project: String, path: String, text: String): JSONObject = request("PUT", "/v1/projects/${encode(project)}/file?path=${encode(path)}", JSONObject().put("content", text))
    suspend fun gitStatus(project: String): JSONObject = request("GET", "/v1/projects/${encode(project)}/git/status")
    suspend fun gitDiff(project: String): JSONObject = request("GET", "/v1/projects/${encode(project)}/git/diff")
    suspend fun gitCommit(project: String, message: String): JSONObject = request("POST", "/v1/projects/${encode(project)}/git/commit", JSONObject().put("message", message))
    suspend fun projectInstructions(project: String): JSONObject = request("GET", "/v1/projects/${encode(project)}/instructions")
    suspend fun saveProjectInstructions(project: String, content: String): JSONObject = request("PUT", "/v1/projects/${encode(project)}/instructions", JSONObject().put("content", content))
    suspend fun previewStatus(project: String): JSONObject = request("GET", "/v1/projects/${encode(project)}/preview")
    suspend fun previewStart(project: String): JSONObject = request("POST", "/v1/projects/${encode(project)}/preview/start", JSONObject())
    suspend fun previewStop(project: String): JSONObject = request("POST", "/v1/projects/${encode(project)}/preview/stop", JSONObject())
}
