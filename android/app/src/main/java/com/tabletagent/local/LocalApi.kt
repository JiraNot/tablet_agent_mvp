package com.tabletagent.local

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Restrict all API traffic to the bridge on the current Android device. */
class LocalApi(private val deviceToken: String) {
    private val host = "http://127.0.0.1:8765"
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

    suspend fun health(): Boolean = withContext(Dispatchers.IO) {
        try {
            val conn = URL(host + "/health").openConnection() as HttpURLConnection
            conn.connectTimeout = 2000; conn.readTimeout = 2000
            try { conn.responseCode == 200 } finally { conn.disconnect() }
        } catch (_: Exception) { false }
    }
    suspend fun projects(): JSONArray = request("GET", "/v1/projects").getJSONArray("projects")
    suspend fun createProject(name: String, url: String) = request("POST", "/v1/projects",
        JSONObject().put("name", name).put("git_url", url))
    suspend fun tasks(project: String): JSONArray = request("GET", "/v1/tasks?project=${encode(project)}").getJSONArray("tasks")
    suspend fun startTask(project: String, prompt: String, mode: String): JSONObject = request("POST", "/v1/tasks",
        JSONObject().put("project", project).put("prompt", prompt).put("mode", mode).put("agent", "codex"))
    suspend fun events(taskId: String, after: Int): JSONArray = request("GET", "/v1/tasks/${encode(taskId)}/events?after=$after").getJSONArray("events")
    suspend fun cancelTask(taskId: String) = request("POST", "/v1/tasks/${encode(taskId)}/cancel", JSONObject())
    suspend fun files(project: String, dir: String = "."): JSONArray = request("GET", "/v1/projects/${encode(project)}/files?path=${encode(dir)}").getJSONArray("files")
    suspend fun file(project: String, path: String): JSONObject = request("GET", "/v1/projects/${encode(project)}/file?path=${encode(path)}")
    suspend fun saveFile(project: String, path: String, text: String): JSONObject = request("PUT", "/v1/projects/${encode(project)}/file?path=${encode(path)}", JSONObject().put("content", text))
    suspend fun previewStatus(project: String): JSONObject = request("GET", "/v1/projects/${encode(project)}/preview")
    suspend fun previewStart(project: String): JSONObject = request("POST", "/v1/projects/${encode(project)}/preview/start", JSONObject())
    suspend fun previewStop(project: String): JSONObject = request("POST", "/v1/projects/${encode(project)}/preview/stop", JSONObject())
}
