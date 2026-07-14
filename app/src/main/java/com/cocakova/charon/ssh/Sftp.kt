package com.cocakova.charon.ssh

import java.io.InputStream
import java.io.OutputStream

/**
 * One entry in the remote filesystem — what the cargo hold's browser renders.
 * [path] is absolute; [mtime] is epoch millis.
 */
data class RemoteEntry(
    val name: String,
    val path: String,
    val isDir: Boolean,
    val size: Long,
    val mtime: Long,
    val isLink: Boolean = false,
)

/**
 * The engine-agnostic SFTP seam, same philosophy as [SshEngine]: just wide enough
 * for a single-pane browser and streamed transfers with resume (offset opens),
 * nothing more. All methods block and throw on failure — call off-main; the
 * repository layer owns retries and progress.
 */
interface SftpChannel {
    /** The login's home directory (the browser's starting shore). */
    fun home(): String

    fun list(dir: String): List<RemoteEntry>

    fun stat(path: String): RemoteEntry?

    /** Stream a remote file from [offset] — offset > 0 resumes an interrupted pull. */
    fun openRead(path: String, offset: Long = 0): InputStream

    /**
     * Stream into a remote file from [offset]. Offset 0 creates/truncates;
     * offset > 0 appends at that position — the resume path for pushes.
     */
    fun openWrite(path: String, offset: Long = 0): OutputStream

    fun mkdir(path: String)

    fun delete(path: String, isDir: Boolean)

    fun rename(from: String, to: String)

    fun close()
}
