package com.taskman.root.core

import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicLong

class ShellResult(val lines: List<String>, val exitCode: Int, val timedOut: Boolean = false) {
    val ok: Boolean get() = exitCode == 0 && !timedOut
    val text: String get() = lines.joinToString("\n")
}

private class ReadResult(val finished: Boolean, val rc: Int)

/**
 * One long-lived shell process (`su` for root, `sh` for the limited mode).
 *
 * Commands are written to its stdin and wrapped so that
 *  - stdin of the command is /dev/null (a command can never swallow our next command),
 *  - stderr is either merged into stdout or dropped,
 *  - an unique end marker with the exit code is printed afterwards.
 * All calls are serialized. Output is read on a worker thread: when a command exceeds its timeout
 * the session is killed and abandoned immediately (the worker thread ends by itself once the
 * pipe closes), and it is re-opened on the next call.
 */
class ShellSession(private val command: String, private val initScript: String = "") {

    private var proc: Process? = null
    private var writer: BufferedWriter? = null
    private var reader: BufferedReader? = null
    private val seq = AtomicLong()
    private val lock = Any()

    fun isAlive(): Boolean = synchronized(lock) { aliveLocked() }

    private fun aliveLocked(): Boolean {
        val p = proc ?: return false
        return try {
            p.exitValue()
            false
        } catch (e: IllegalThreadStateException) {
            true
        }
    }

    /** Starts the process and loads [initScript]. Idempotent. A first `su` start may wait for the user's grant. */
    fun open(timeoutMs: Long = 60_000): Boolean = synchronized(lock) { openLocked(timeoutMs) }

    fun exec(cmd: String, timeoutMs: Long = 20_000, mergeStderr: Boolean = true): ShellResult =
        synchronized(lock) {
            if (!openLocked(maxOf(timeoutMs, 20_000L))) {
                ShellResult(emptyList(), -1)
            } else {
                execLocked(cmd, timeoutMs, mergeStderr)
            }
        }

    fun close() = synchronized(lock) { closeLocked(false) }

    private fun openLocked(timeoutMs: Long): Boolean {
        if (aliveLocked()) return true
        closeLocked(false)
        try {
            val p = ProcessBuilder(command).start()
            proc = p
            writer = BufferedWriter(OutputStreamWriter(p.outputStream, Charsets.UTF_8))
            reader = BufferedReader(InputStreamReader(p.inputStream, Charsets.UTF_8))
            val t = Thread({ drain(p) }, "shell-stderr")
            t.isDaemon = true
            t.start()
        } catch (e: IOException) {
            closeLocked(false)
            return false
        }
        if (initScript.isNotEmpty()) {
            val r = execLocked(initScript, timeoutMs, true)
            if (!r.ok) {
                closeLocked(false)
                return false
            }
        }
        return true
    }

    private fun drain(p: Process) {
        try {
            val buf = ByteArray(1024)
            val s = p.errorStream
            while (s.read(buf) >= 0) {
                // discard
            }
        } catch (e: IOException) {
            // process gone
        }
    }

    private fun execLocked(cmd: String, timeoutMs: Long, mergeStderr: Boolean): ShellResult {
        val w = writer ?: return ShellResult(emptyList(), -1)
        val r = reader ?: return ShellResult(emptyList(), -1)
        val marker = "__TM_END_${System.nanoTime()}_${seq.incrementAndGet()}__"
        val redirect = if (mergeStderr) "2>&1" else "2>/dev/null"
        val out = ArrayList<String>(64)

        val future = READERS.submit(Callable { readUntil(r, marker, out) })
        var res = ReadResult(false, -1)
        var timedOut = false
        try {
            w.write("{ $cmd\n} </dev/null $redirect\n")
            w.write("__tm_rc=\$?\n")
            w.write("echo\n")
            w.write("echo \"$marker \$__tm_rc\"\n")
            w.flush()
            res = future.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            timedOut = true
        } catch (e: ExecutionException) {
            // reader failed: treated as "not finished"
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            timedOut = true
        } catch (e: IOException) {
            // writing failed: the shell is gone
        }

        if (timedOut) {
            future.cancel(true)
            closeLocked(true)
            return ShellResult(emptyList(), -1, true)
        }
        if (!res.finished) {
            closeLocked(false)
            return ShellResult(ArrayList(out), -1)
        }
        // we always append one blank line before the marker; drop it again
        if (out.isNotEmpty() && out[out.size - 1].isEmpty()) out.removeAt(out.size - 1)
        return ShellResult(out, res.rc)
    }

    private fun readUntil(r: BufferedReader, marker: String, out: MutableList<String>): ReadResult {
        try {
            while (true) {
                val line = r.readLine() ?: return ReadResult(false, -1)
                if (line.startsWith(marker)) {
                    return ReadResult(true, line.substring(marker.length).trim().toIntOrNull() ?: -1)
                }
                out.add(line)
            }
        } catch (e: IOException) {
            return ReadResult(false, -1)
        }
    }

    /** [abandonReader]: a read may still be blocked on the pipe, so the reader must not be closed (it would block). */
    private fun closeLocked(abandonReader: Boolean) {
        try {
            writer?.write("exit\n")
            writer?.flush()
        } catch (e: IOException) {
            // ignore
        }
        try { writer?.close() } catch (e: IOException) { /* ignore */ }
        if (!abandonReader) {
            try { reader?.close() } catch (e: IOException) { /* ignore */ }
        }
        try { proc?.destroy() } catch (e: Exception) { /* ignore */ }
        proc = null
        writer = null
        reader = null
    }

    companion object {
        private val READERS = Executors.newCachedThreadPool(ThreadFactory { r ->
            val t = Thread(r, "shell-reader")
            t.isDaemon = true
            t
        })

        /** Single-quotes a string for safe use inside a shell command. */
        fun quote(s: String): String = "'" + s.replace("'", "'\\''") + "'"
    }
}
