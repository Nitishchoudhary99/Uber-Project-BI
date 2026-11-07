package dev.pockethost.server

import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Runs a bundled executable and keeps it alive: restarts with backoff when it
 * dies, and stops it gracefully (SIGTERM, then SIGKILL) when asked.
 */
class ManagedProcess(
    private val name: String,
    private val command: List<String>,
    private val env: Map<String, String>,
    private val workDir: File,
    private val onLine: (String) -> Unit = {},
    private val onStart: () -> Unit = {},
    private val onExit: (Int) -> Unit = {},
) {
    @Volatile private var wanted = false
    @Volatile private var process: Process? = null
    @Volatile private var pid = -1
    private var supervisor: Thread? = null

    fun start() {
        if (wanted) return
        val exe = File(command.first())
        if (!exe.exists()) {
            ServerState.log(name, "engine not found: ${exe.name} (this APK was built without the native engines)")
            ServerState.set(name, SvcStatus.CRASHED, "engine missing from this build")
            return
        }
        wanted = true
        supervisor = thread(name = "ph-$name", isDaemon = true) { supervise() }
    }

    private fun supervise() {
        var backoff = 2_000L
        while (wanted) {
            val began = System.currentTimeMillis()
            val code = runOnce()
            if (!wanted) break
            onExit(code)
            ServerState.log(name, "exited with code $code, restarting in ${backoff / 1000}s")
            ServerState.set(name, SvcStatus.CRASHED, "exited ($code), restarting")
            try { Thread.sleep(backoff) } catch (_: InterruptedException) { break }
            backoff = if (System.currentTimeMillis() - began > 60_000) 2_000 else (backoff * 2).coerceAtMost(60_000)
        }
    }

    private fun runOnce(): Int {
        // `echo $$; exec ...` tells us the real PID so we can send SIGTERM;
        // java.lang.Process.destroy() on Android is an immediate SIGKILL.
        val script = "echo \$\$; exec " + command.joinToString(" ") { shQuote(it) } + " 2>&1"
        val pb = ProcessBuilder("/system/bin/sh", "-c", script).directory(workDir).redirectErrorStream(true)
        pb.environment().putAll(env)
        val p = try {
            pb.start()
        } catch (e: IOException) {
            ServerState.log(name, "failed to start: ${e.message}")
            return -1
        }
        process = p
        val reader = p.inputStream.bufferedReader()
        pid = reader.readLine()?.trim()?.toIntOrNull() ?: -1
        ServerState.log(name, "started (pid $pid)")
        onStart()
        try {
            reader.forEachLine { line ->
                ServerState.log(name, line)
                runCatching { onLine(line) }
            }
        } catch (_: IOException) {
        }
        return p.waitFor().also { process = null; pid = -1 }
    }

    fun stop() {
        wanted = false
        val p = process
        val id = pid
        if (p != null) {
            if (id > 0) runCatching { android.os.Process.sendSignal(id, 15) }
            if (!p.waitFor(6, TimeUnit.SECONDS)) p.destroy()
        }
        supervisor?.interrupt()
        ServerState.log(name, "stopped")
    }

    companion object {
        fun shQuote(s: String) = "'" + s.replace("'", "'\\''") + "'"

        /** Kills leftovers of [exePath] (e.g. Ollama model runners) from a previous crash. */
        fun killOrphans(exePath: String) {
            val me = android.os.Process.myPid()
            File("/proc").listFiles { f -> f.name.all(Char::isDigit) }?.forEach { dir ->
                val id = dir.name.toInt()
                if (id == me) return@forEach
                val cmd = runCatching { File(dir, "cmdline").readText() }.getOrNull() ?: return@forEach
                if (cmd.startsWith(exePath)) runCatching { android.os.Process.killProcess(id) }
            }
        }
    }
}
