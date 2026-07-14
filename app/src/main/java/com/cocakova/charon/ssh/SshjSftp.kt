package com.cocakova.charon.ssh

import net.schmizz.sshj.sftp.FileMode
import net.schmizz.sshj.sftp.OpenMode
import net.schmizz.sshj.sftp.RemoteResourceInfo
import net.schmizz.sshj.sftp.SFTPClient
import java.io.InputStream
import java.io.OutputStream
import java.util.EnumSet

/** [SftpChannel] over sshj's SFTPClient — the thin adapter, no policy. */
internal class SshjSftp(private val sftp: SFTPClient) : SftpChannel {

    override fun home(): String = sftp.canonicalize(".")

    override fun list(dir: String): List<RemoteEntry> =
        sftp.ls(dir).map { it.toEntry() }

    override fun stat(path: String): RemoteEntry? = runCatching {
        val attrs = sftp.statExistence(path) ?: return null
        RemoteEntry(
            name = path.substringAfterLast('/'),
            path = path,
            isDir = attrs.type == FileMode.Type.DIRECTORY,
            size = attrs.size,
            mtime = attrs.mtime * 1000L,
            isLink = attrs.type == FileMode.Type.SYMLINK,
        )
    }.getOrNull()

    override fun openRead(path: String, offset: Long): InputStream {
        val file = sftp.open(path, EnumSet.of(OpenMode.READ))
        // Read-ahead pipelines requests over the channel round-trip — the difference
        // between watching paint dry and a real pull on a 30ms link.
        return file.ReadAheadRemoteFileInputStream(16, offset)
    }

    override fun openWrite(path: String, offset: Long): OutputStream {
        val modes = if (offset == 0L) {
            EnumSet.of(OpenMode.WRITE, OpenMode.CREAT, OpenMode.TRUNC)
        } else {
            EnumSet.of(OpenMode.WRITE, OpenMode.CREAT) // resume: keep what's landed
        }
        val file = sftp.open(path, modes)
        return file.RemoteFileOutputStream(offset, 16)
    }

    override fun mkdir(path: String) = sftp.mkdir(path)

    override fun delete(path: String, isDir: Boolean) =
        if (isDir) sftp.rmdir(path) else sftp.rm(path)

    override fun rename(from: String, to: String) = sftp.rename(from, to)

    override fun close() {
        runCatching { sftp.close() }
    }

    private fun RemoteResourceInfo.toEntry() = RemoteEntry(
        name = name,
        path = path,
        isDir = isDirectory,
        size = attributes.size,
        mtime = attributes.mtime * 1000L,
        isLink = attributes.type == FileMode.Type.SYMLINK,
    )
}
