package com.tabletagent.local.runtime

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import java.io.File
import java.io.FileOutputStream

sealed class BootstrapStatus {
    object Idle : BootstrapStatus()
    data class Extracting(val progress: Float, val currentFile: String) : BootstrapStatus()
    object Ready : BootstrapStatus()
    data class Error(val message: String) : BootstrapStatus()
}

object BootstrapManager {
    private val _status = MutableStateFlow<BootstrapStatus>(BootstrapStatus.Idle)
    val status: StateFlow<BootstrapStatus> = _status.asStateFlow()

    fun getRootfsDir(context: Context): File {
        return File(context.filesDir, "runtime/rootfs")
    }

    fun isReady(context: Context): Boolean {
        val rootfs = getRootfsDir(context)
        val busybox = File(rootfs, "bin/busybox")
        val bash = File(rootfs, "bin/bash")
        return busybox.exists() || bash.exists()
    }

    fun setupHelperLibs(context: Context) {
        val nativeDir = context.applicationInfo.nativeLibraryDir
        val libDir = File(context.filesDir, "runtime/lib").apply { mkdirs() }
        val talloc2 = File(libDir, "libtalloc.so.2")
        val nativeTalloc = File(nativeDir, "libtalloc.so")
        if (nativeTalloc.exists() && (!talloc2.exists())) {
            try {
                talloc2.delete()
                android.system.Os.symlink(nativeTalloc.absolutePath, talloc2.absolutePath)
            } catch (_: Exception) {
                try {
                    nativeTalloc.inputStream().use { input ->
                        talloc2.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                } catch (_: Exception) {}
            }
        }
    }

    suspend fun bootstrapIfNeeded(context: Context, force: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        val rootfs = getRootfsDir(context)
        if (!force && isReady(context)) {
            _status.value = BootstrapStatus.Ready
            return@withContext true
        }

        try {
            _status.value = BootstrapStatus.Extracting(0f, "Preparing filesystem...")
            rootfs.mkdirs()

            val assetManager = context.assets
            val runtimeFiles = assetManager.list("runtime")?.toList() ?: emptyList()
            val assetPath = if (runtimeFiles.contains("base_runtime.tar")) {
                "runtime/base_runtime.tar"
            } else {
                "runtime/base_runtime.tar.gz"
            }

            val assetFd = runCatching { assetManager.openFd(assetPath) }.getOrNull()
            val totalBytes = assetFd?.length ?: 50_000_000L
            assetFd?.close()

            var extractedBytes = 0L

            assetManager.open(assetPath).use { rawIn ->
                val baseIn = if (assetPath.endsWith(".gz")) GzipCompressorInputStream(rawIn) else rawIn
                TarArchiveInputStream(baseIn).use { tarIn ->
                    var entry = tarIn.nextTarEntry
                        while (entry != null) {
                            val destFile = File(rootfs, entry.name)
                            if (entry.isDirectory) {
                                destFile.mkdirs()
                            } else if (entry.isSymbolicLink) {
                                destFile.parentFile?.mkdirs()
                                destFile.delete()
                                try {
                                    android.system.Os.symlink(entry.linkName, destFile.absolutePath)
                                } catch (e: Exception) {
                                    android.util.Log.w("BootstrapManager", "Symlink failed for ${destFile.name} -> ${entry.linkName}: ${e.message}")
                                }
                            } else {
                                destFile.parentFile?.mkdirs()
                                FileOutputStream(destFile).use { out ->
                                    tarIn.copyTo(out)
                                }
                                val mode = entry.mode
                                if ((mode and 0b001_000_000) != 0 || (mode and 0b000_001_000) != 0 || (mode and 0b000_000_001) != 0) {
                                    destFile.setExecutable(true, false)
                                }
                                destFile.setReadable(true, false)
                                destFile.setWritable(true, false)
                            }

                            extractedBytes += entry.size
                            val fraction = (extractedBytes.toFloat() / (totalBytes * 2.5f)).coerceIn(0f, 0.99f)
                            _status.value = BootstrapStatus.Extracting(fraction, entry.name)

                            entry = tarIn.nextTarEntry
                        }
                    }
                }

            // Ensure essential directories
            ensureRuntimeDirs(context)

            _status.value = BootstrapStatus.Ready
            true
        } catch (e: Exception) {
            android.util.Log.e("BootstrapManager", "Bootstrap extraction error", e)
            _status.value = BootstrapStatus.Error(e.message ?: "Extraction failed")
            false
        }
    }

    /** Ensure workspace, git identity, temp, and DNS files exist inside rootfs. */
    fun ensureRuntimeDirs(context: Context) {
        val rootfs = getRootfsDir(context)
        if (!rootfs.exists()) return

        File(rootfs, "tmp").apply { mkdirs(); setReadable(true, false); setWritable(true, false); setExecutable(true, false) }
        File(rootfs, "dev").mkdirs()
        File(rootfs, "proc").mkdirs()
        File(rootfs, "sys").mkdirs()
        File(rootfs, "root").apply { mkdirs(); setReadable(true, false); setWritable(true, false); setExecutable(true, false) }
        File(rootfs, "root/.local/bin").mkdirs()
        File(rootfs, "root/tablet-agent/workspaces").mkdirs()
        File(rootfs, "opt/tablet-agent").mkdirs()

        val resolvConf = File(rootfs, "etc/resolv.conf")
        if (!resolvConf.exists() || resolvConf.length() == 0L) {
            resolvConf.parentFile?.mkdirs()
            resolvConf.writeText("nameserver 8.8.8.8\nnameserver 1.1.1.1\n")
        }

        val gitConfig = File(rootfs, "root/.gitconfig")
        if (!gitConfig.exists()) {
            gitConfig.writeText("[user]\n\tname = Tablet Agent\n\temail = agent@local.tablet\n[init]\n\tdefaultBranch = main\n")
        }
    }
}
