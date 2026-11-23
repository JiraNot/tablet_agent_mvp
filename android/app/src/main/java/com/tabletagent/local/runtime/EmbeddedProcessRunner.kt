package com.tabletagent.local.runtime

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

data class ExecutionResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String
)

object EmbeddedProcessRunner {

    fun isProotAvailable(context: Context): Boolean {
        val nativeDir = context.applicationInfo.nativeLibraryDir
        val proot = File(nativeDir, "libproot.so")
        return proot.exists()
    }

    fun buildProotCommand(
        context: Context,
        command: String,
        workingDir: String = "/root",
        customBinds: List<Pair<String, String>> = emptyList(),
        extraEnv: Map<String, String> = emptyMap()
    ): Pair<List<String>, Map<String, String>> {
        BootstrapManager.setupHelperLibs(context)
        val nativeDir = context.applicationInfo.nativeLibraryDir
        val libDir = File(context.filesDir, "runtime/lib").apply { mkdirs() }
        val proot = File(nativeDir, "libproot.so").absolutePath
        val loader = File(nativeDir, "libloader.so").absolutePath
        val rootfs = BootstrapManager.getRootfsDir(context).absolutePath
        val tmpDir = File(context.cacheDir, "proot_tmp").apply { mkdirs() }.absolutePath

        val env = mutableMapOf(
            "LD_LIBRARY_PATH" to "${libDir.absolutePath}:$nativeDir",
            "PROOT_LOADER" to loader,
            "PROOT_TMP_DIR" to tmpDir,
            "PATH" to "/bin:/usr/bin:/sbin:/usr/sbin:/root/.local/bin:/root/.codex/packages/standalone/current/bin",
            "HOME" to "/root",
            "TMPDIR" to "/tmp",
            "LANG" to "C.UTF-8"
        )
        env.putAll(extraEnv)

        val args = mutableListOf(
            proot,
            "-r", rootfs,
            "-0",
            "-b", "/dev",
            "-b", "/proc",
            "-b", "/sys",
            "-b", "/system",
            "-b", "/apex"
        )

        for ((hostPath, guestPath) in customBinds) {
            args.add("-b")
            args.add("$hostPath:$guestPath")
        }

        args.add("-w")
        args.add(workingDir)
        args.add("/bin/bash")
        args.add("-c")
        args.add(command)

        return Pair(args, env)
    }

    fun startDaemon(
        context: Context,
        command: String,
        workingDir: String = "/root",
        customBinds: List<Pair<String, String>> = emptyList(),
        extraEnv: Map<String, String> = emptyMap()
    ): Process {
        val (cmd, env) = buildProotCommand(context, command, workingDir, customBinds, extraEnv)
        val processBuilder = ProcessBuilder(cmd)
        val pEnv = processBuilder.environment()
        for ((k, v) in env) {
            pEnv[k] = v
        }
        processBuilder.redirectErrorStream(true)
        return processBuilder.start()
    }

    suspend fun execute(
        context: Context,
        command: String,
        workingDir: String = "/root",
        customBinds: List<Pair<String, String>> = emptyList(),
        timeoutMs: Long = 120_000L
    ): ExecutionResult = withContext(Dispatchers.IO) {
        val (cmd, env) = buildProotCommand(context, command, workingDir, customBinds)
        val processBuilder = ProcessBuilder(cmd)
        val pEnv = processBuilder.environment()
        for ((k, v) in env) {
            pEnv[k] = v
        }

        val process = processBuilder.start()

        val stdoutSb = StringBuilder()
        val stderrSb = StringBuilder()

        val stdoutThread = Thread {
            BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    stdoutSb.append(line).append("\n")
                }
            }
        }
        val stderrThread = Thread {
            BufferedReader(InputStreamReader(process.errorStream)).use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    stderrSb.append(line).append("\n")
                }
            }
        }

        stdoutThread.start()
        stderrThread.start()

        val finished = process.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        if (!finished) {
            process.destroyForcibly()
            stdoutThread.interrupt()
            stderrThread.interrupt()
            return@withContext ExecutionResult(
                exitCode = -1,
                stdout = stdoutSb.toString(),
                stderr = "Execution timed out after ${timeoutMs}ms"
            )
        }

        stdoutThread.join(2000)
        stderrThread.join(2000)

        ExecutionResult(
            exitCode = process.exitValue(),
            stdout = stdoutSb.toString().trimEnd(),
            stderr = stderrSb.toString().trimEnd()
        )
    }

    fun executeStreaming(
        context: Context,
        command: String,
        workingDir: String = "/root",
        customBinds: List<Pair<String, String>> = emptyList()
    ): Flow<String> = flow {
        val (cmd, env) = buildProotCommand(context, command, workingDir, customBinds)
        val processBuilder = ProcessBuilder(cmd)
        val pEnv = processBuilder.environment()
        for ((k, v) in env) {
            pEnv[k] = v
        }
        processBuilder.redirectErrorStream(true)

        val process = processBuilder.start()
        BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                emit(line!!)
            }
        }
        process.waitFor()
    }.flowOn(Dispatchers.IO)
}
