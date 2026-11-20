package com.example.procpulse.core.bridge

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.UUID

/**
 * Maintains a single persistent interactive `su` worker process so telemetry
 * reads at 250ms-1000ms never incur repeated `su` fork overhead.
 */
class PersistentRootWorker {

    private val mutex = Mutex()
    private var process: Process? = null
    private var writer: BufferedWriter? = null
    private var reader: BufferedReader? = null

    @Volatile
    var isConnected: Boolean = false
        private set

    suspend fun ensureConnected(): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (process?.isAlive == true && writer != null && reader != null) {
                return@withLock true
            }
            closeLocked()
            return@withLock try {
                val p = ProcessBuilder("su")
                    .redirectErrorStream(true)
                    .start()
                val w = BufferedWriter(OutputStreamWriter(p.outputStream))
                val r = BufferedReader(InputStreamReader(p.inputStream))
                val sentinel = "__PP_INIT_${UUID.randomUUID()}__"
                w.write("id; echo $sentinel\n")
                w.flush()
                val lines = readUntilSentinel(r, sentinel)
                if (lines.any { it.contains("uid=0") }) {
                    process = p
                    writer = w
                    reader = r
                    isConnected = true
                    true
                } else {
                    p.destroy()
                    isConnected = false
                    false
                }
            } catch (_: Throwable) {
                isConnected = false
                false
            }
        }
    }

    suspend fun exec(command: String): Result<String> = withContext(Dispatchers.IO) {
        mutex.withLock {
            val w = writer
            val r = reader
            if (process?.isAlive != true || w == null || r == null) {
                isConnected = false
                return@withLock Result.failure(IOException("Root session unavailable"))
            }
            return@withLock try {
                val sentinel = "__PP_EOF_${UUID.randomUUID()}__"
                w.write("$command\necho $sentinel\n")
                w.flush()
                val output = readUntilSentinel(r, sentinel)
                Result.success(output.joinToString("\n"))
            } catch (t: Throwable) {
                closeLocked()
                Result.failure(t)
            }
        }
    }

    suspend fun close() = withContext(Dispatchers.IO) {
        mutex.withLock {
            closeLocked()
        }
    }

    private fun closeLocked() {
        isConnected = false
        runCatching { writer?.write("exit\n"); writer?.flush() }
        runCatching { writer?.close() }
        runCatching { reader?.close() }
        runCatching { process?.destroy() }
        process = null
        writer = null
        reader = null
    }

    private fun readUntilSentinel(r: BufferedReader, sentinel: String): List<String> {
        val result = ArrayList<String>(64)
        var line = r.readLine()
        while (line != null) {
            if (line.contains(sentinel)) break
            result.add(line)
            if (result.size > 4000) break
            line = r.readLine()
        }
        return result
    }
}
