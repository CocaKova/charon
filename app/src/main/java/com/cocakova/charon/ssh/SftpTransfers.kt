package com.cocakova.charon.ssh

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/**
 * The ferry's cargo ledger: every file being carried across, with live progress.
 * Transfers stream through their own SFTP channel (never the browser's), publish
 * progress on [transfers], and **resume themselves**: a mid-flight break reopens
 * both ends at the byte offset already landed — up to [MAX_RETRIES] times with a
 * short breath between — before conceding. The local side is SAF ([Uri]s), so
 * pulls land wherever the user pointed and pushes read straight from any app's
 * document.
 *
 * The FGS ([com.cocakova.charon.service.ConnectionService]) already pins the
 * process while sessions live, so a transfer survives backgrounding for free.
 */
class SftpTransfers(private val appContext: Context) {

    enum class Direction { PULL, PUSH }
    enum class State { RUNNING, DONE, FAILED }

    data class Transfer(
        val id: String,
        val name: String,
        val direction: Direction,
        val done: Long,
        val total: Long,          // -1 = unknown
        val state: State,
        val error: String? = null,
    )

    /** The ledger, newest first. Finished entries linger briefly, then clear. */
    val transfers = MutableStateFlow<List<Transfer>>(emptyList())

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Carry a remote file ashore into a SAF document. */
    fun pull(openSftp: () -> SftpChannel?, remotePath: String, size: Long, dest: Uri) {
        val name = remotePath.substringAfterLast('/')
        start(name, Direction.PULL, size) { id ->
            var offset = 0L
            retrying { attempt ->
                val sftp = openSftp() ?: error("the hold is unreachable")
                try {
                    // First attempt truncates ("wt" — plain "w" isn't guaranteed to
                    // truncate on every SAF provider); resumes append at the offset.
                    val mode = if (attempt == 0 || offset == 0L) "wt" else "wa"
                    val out = appContext.contentResolver.openOutputStream(dest, mode)
                        ?: error("cannot open the local shore")
                    sftp.openRead(remotePath, offset).use { input ->
                        out.use { o -> offset += copy(input, o, id, offset) }
                    }
                } finally {
                    sftp.close()
                }
            }
        }
    }

    /** Carry a local SAF document aboard into a remote directory. */
    fun push(openSftp: () -> SftpChannel?, src: Uri, name: String, size: Long, remoteDir: String) {
        val remotePath = remoteDir.trimEnd('/') + "/" + name
        start(name, Direction.PUSH, size) { id ->
            var offset = 0L
            retrying {
                val sftp = openSftp() ?: error("the hold is unreachable")
                try {
                    val input = appContext.contentResolver.openInputStream(src)
                        ?: error("cannot read the local file")
                    input.use { i ->
                        skipFully(i, offset) // resume: the remote already has this much
                        sftp.openWrite(remotePath, offset).use { o ->
                            offset += copy(i, o, id, offset)
                        }
                    }
                } finally {
                    sftp.close()
                }
            }
        }
    }

    // ---- machinery -------------------------------------------------------------------

    private fun start(name: String, dir: Direction, total: Long, body: suspend (String) -> Unit) {
        val id = UUID.randomUUID().toString()
        transfers.update {
            listOf(Transfer(id, name, dir, 0, total, State.RUNNING)) + it
        }
        scope.launch {
            try {
                body(id)
                setState(id) { it.copy(state = State.DONE, done = maxOf(it.done, it.total)) }
            } catch (e: Exception) {
                setState(id) { it.copy(state = State.FAILED, error = e.message ?: "the crossing failed") }
            }
            // Let the outcome be seen, then clear the ledger line.
            delay(if (transfers.value.firstOrNull { it.id == id }?.state == State.DONE) 4_000 else 20_000)
            transfers.update { list -> list.filterNot { it.id == id } }
        }
    }

    /** Run [body]; on failure retry from the current offset after a breath. */
    private suspend fun retrying(body: suspend (attempt: Int) -> Unit) {
        var lastError: Exception? = null
        repeat(MAX_RETRIES) { attempt ->
            try {
                body(attempt)
                return
            } catch (e: Exception) {
                lastError = e
                delay(1_500L * (attempt + 1))
            }
        }
        throw lastError ?: IllegalStateException("transfer failed")
    }

    /** Stream [input] → [output], publishing progress; returns bytes moved. */
    private fun copy(input: InputStream, output: OutputStream, id: String, base: Long): Long {
        val buf = ByteArray(64 * 1024)
        var moved = 0L
        var lastPublish = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            output.write(buf, 0, n)
            moved += n
            // Publish at most every 256KB — progress, not a StateFlow flood.
            if (moved - lastPublish >= 256 * 1024) {
                lastPublish = moved
                val doneNow = base + moved
                setState(id) { it.copy(done = doneNow) }
            }
        }
        output.flush()
        val doneNow = base + moved
        setState(id) { it.copy(done = doneNow) }
        return moved
    }

    private fun skipFully(input: InputStream, bytes: Long) {
        var left = bytes
        while (left > 0) {
            val skipped = input.skip(left)
            if (skipped <= 0) error("cannot resume: local file no longer readable")
            left -= skipped
        }
    }

    private fun setState(id: String, mutate: (Transfer) -> Transfer) {
        transfers.update { list -> list.map { if (it.id == id) mutate(it) else it } }
    }

    private companion object {
        const val MAX_RETRIES = 4
    }
}
