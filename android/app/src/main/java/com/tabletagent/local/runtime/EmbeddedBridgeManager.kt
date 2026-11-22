package com.tabletagent.local.runtime

import android.content.Context
import android.system.Os
import android.util.Base64
import android.util.Log
import com.tabletagent.local.SecureDeviceToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom

sealed class EmbeddedBridgeStatus {
    object Stopped : EmbeddedBridgeStatus()
    object Starting : EmbeddedBridgeStatus()
    data class Running(val port: Int) : EmbeddedBridgeStatus()
    data class Error(val message: String) : EmbeddedBridgeStatus()
}

/**
 * Manages the Python supervisor bridge (core.py + server.py) running inside
 * the APK's embedded PRoot userland without requiring Termux.
 */
object EmbeddedBridgeManager {
    private const val TAG = "EmbeddedBridge"
    const val DEFAULT_PORT = 8765
    const val FALLBACK_PORT = 8766

    private val mutex = Mutex()
    private val _status = MutableStateFlow<EmbeddedBridgeStatus>(EmbeddedBridgeStatus.Stopped)
    val status: StateFlow<EmbeddedBridgeStatus> = _status.asStateFlow()

    private val _port = MutableStateFlow(DEFAULT_PORT)
    val port: StateFlow<Int> = _port.asStateFlow()

    @Volatile
    private var daemonProcess: Process? = null

    /** Ensure a persisted device token exists in KeyStore and is synced to PRoot bridge home. */
    fun ensureDeviceToken(context: Context): String {
        var token = SecureDeviceToken.get(context).trim()
        val rootfs = BootstrapManager.getRootfsDir(context)
        val tokenFile = File(rootfs, "root/tablet-agent/device_token")

        if (token.isBlank() && tokenFile.isFile) {
            val existing = runCatching { tokenFile.readText(Charsets.UTF_8).trim() }.getOrDefault("")
            if (existing.isNotBlank()) {
                SecureDeviceToken.save(context, existing)
                token = existing
            }
        }
        if (token.isBlank()) {
            val bytes = ByteArray(32)
            SecureRandom().nextBytes(bytes)
            token = Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
            SecureDeviceToken.save(context, token)
        }
        syncTokenFile(context, token)
        return token
    }

    /** Write device token inside PRoot home with 0600 permissions so LocalCore shares auth. */
    fun syncTokenFile(context: Context, token: String) {
        if (token.isBlank()) return
        val rootfs = BootstrapManager.getRootfsDir(context)
        val homeDir = File(rootfs, "root/tablet-agent").apply { mkdirs() }
        val tokenFile = File(homeDir, "device_token")
        tokenFile.writeText(token.trim() + "\n", Charsets.UTF_8)
        runCatching { Os.chmod(tokenFile.absolutePath, 0b110_000_000) }
    }

    /** Copy bundled core.py and server.py from APK assets into PRoot /opt/tablet-agent. */
    fun syncBridgeScripts(context: Context) {
        val rootfs = BootstrapManager.getRootfsDir(context)
        val targetDir = File(rootfs, "opt/tablet-agent").apply { mkdirs() }
        for (name in listOf("core.py", "server.py")) {
            val assetBytes = context.assets.open("bridge/$name").use { it.readBytes() }
            val dest = File(targetDir, name)
            val changed = !dest.exists() || dest.length() != assetBytes.size.toLong() ||
                !dest.readBytes().contentEquals(assetBytes)
            if (changed) {
                dest.writeBytes(assetBytes)
                dest.setReadable(true, false)
            }
        }
    }

    private fun probeHealthRuntime(port: Int): String? {
        return try {
            val conn = URL("http://127.0.0.1:$port/health").openConnection() as HttpURLConnection
            conn.connectTimeout = 800
            conn.readTimeout = 800
            try {
                if (conn.responseCode == 200) {
                    val raw = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                    JSONObject(raw).optString("runtime", "termux")
                } else null
            } finally {
                conn.disconnect()
            }
        } catch (_: Exception) {
            null
        }
    }

    suspend fun startIfNeeded(context: Context): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (!BootstrapManager.isReady(context) || !EmbeddedProcessRunner.isProotAvailable(context)) {
                _status.value = EmbeddedBridgeStatus.Error("Embedded runtime or PRoot binary unavailable")
                return@withContext false
            }

            BootstrapManager.ensureRuntimeDirs(context)
            ensureDeviceToken(context)
            syncBridgeScripts(context)

            val currentPort = _port.value
            val currentProc = daemonProcess
            if (currentProc != null && currentProc.isAlive && probeHealthRuntime(currentPort) == "embedded") {
                _status.value = EmbeddedBridgeStatus.Running(currentPort)
                return@withContext true
            }

            // Pick canonical port 8765 unless occupied by external Termux bridge, then fallback to 8766.
            val health8765 = probeHealthRuntime(DEFAULT_PORT)
            if (health8765 == "embedded") {
                _port.value = DEFAULT_PORT
                _status.value = EmbeddedBridgeStatus.Running(DEFAULT_PORT)
                return@withContext true
            }

            val targetPort = if (health8765 == null) {
                DEFAULT_PORT
            } else {
                val healthFallback = probeHealthRuntime(FALLBACK_PORT)
                if (healthFallback == "embedded") {
                    _port.value = FALLBACK_PORT
                    _status.value = EmbeddedBridgeStatus.Running(FALLBACK_PORT)
                    return@withContext true
                }
                FALLBACK_PORT
            }

            stopInternal()
            _port.value = targetPort
            _status.value = EmbeddedBridgeStatus.Starting

            val recentLogs = ArrayDeque<String>(16)
            val env = mutableMapOf(
                "TABLET_AGENT_HOME" to "/root/tablet-agent",
                "TABLET_AGENT_PORT" to targetPort.toString(),
                "TABLET_AGENT_EMBEDDED" to "1",
                "PYTHONUNBUFFERED" to "1"
            )
            env.putAll(SecureSecrets.asEnvMap(context))
            val proc = EmbeddedProcessRunner.startDaemon(
                context = context,
                command = "exec python3 -u /opt/tablet-agent/server.py",
                workingDir = "/root/tablet-agent",
                extraEnv = env
            )
            daemonProcess = proc

            Thread {
                runCatching {
                    BufferedReader(InputStreamReader(proc.inputStream)).use { reader ->
                        var line: String?
                        while (reader.readLine().also { line = it } != null) {
                            val clean = line ?: continue
                            synchronized(recentLogs) {
                                if (recentLogs.size >= 16) recentLogs.removeFirst()
                                recentLogs.addLast(clean)
                            }
                            Log.d(TAG, clean)
                        }
                    }
                }
            }.apply {
                isDaemon = true
                name = "embedded-bridge-log"
                start()
            }

            repeat(35) {
                if (!proc.isAlive) {
                    val errDetail = synchronized(recentLogs) { recentLogs.joinToString(" | ") }
                    _status.value = EmbeddedBridgeStatus.Error(
                        errDetail.ifBlank { "Bridge exited with code ${proc.exitValue()}" }
                    )
                    return@withContext false
                }
                if (probeHealthRuntime(targetPort) == "embedded") {
                    _status.value = EmbeddedBridgeStatus.Running(targetPort)
                    return@withContext true
                }
                delay(200)
            }

            val detail = synchronized(recentLogs) { recentLogs.lastOrNull() } ?: "Timed out waiting for 127.0.0.1:$targetPort"
            _status.value = EmbeddedBridgeStatus.Error(detail)
            false
        }
    }

    suspend fun restart(context: Context): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            stopInternal()
        }
        startIfNeeded(context)
    }

    fun stop() {
        stopInternal()
        _status.value = EmbeddedBridgeStatus.Stopped
    }

    private fun stopInternal() {
        val proc = daemonProcess
        daemonProcess = null
        if (proc != null) {
            runCatching { proc.destroy() }
            runCatching {
                if (proc.isAlive) {
                    Thread.sleep(250)
                    if (proc.isAlive) proc.destroyForcibly()
                }
            }
        }
    }
}
