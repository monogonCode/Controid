package com.controid.files

import android.os.Environment
import android.os.StatFs
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructStat
import com.controid.protocol.ProtocolConstants
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.BufferUnderflowException
import java.nio.ByteBuffer
import java.nio.ByteOrder

class RemoteFileSystemHandler(
    root: File = defaultRootDir()
) {
    private val rootDir: File = runCatching { root.canonicalFile }.getOrElse { root.absoluteFile }
    private val rootPrefix: String =
        if (rootDir.path.endsWith(File.separatorChar)) rootDir.path else rootDir.path + File.separatorChar

    fun handleRpc(request: ByteArray): ByteArray {
        if (request.isEmpty()) return errorResponse(OsConstants.EINVAL)
        return try {
            val buf = ByteBuffer.wrap(request).order(ByteOrder.BIG_ENDIAN)
            when (buf.get()) {
                ProtocolConstants.FS_OP_STATFS -> handleStatFs(buf)
                ProtocolConstants.FS_OP_GETATTR -> handleGetAttr(buf)
                ProtocolConstants.FS_OP_READDIR -> handleReadDir(buf)
                ProtocolConstants.FS_OP_READ -> handleRead(buf)
                ProtocolConstants.FS_OP_WRITE -> handleWrite(buf)
                ProtocolConstants.FS_OP_CREATE -> handleCreate(buf)
                ProtocolConstants.FS_OP_TRUNCATE -> handleTruncate(buf)
                ProtocolConstants.FS_OP_MKDIR -> handleMkdir(buf)
                ProtocolConstants.FS_OP_UNLINK -> handleUnlink(buf)
                ProtocolConstants.FS_OP_RMDIR -> handleRmdir(buf)
                ProtocolConstants.FS_OP_RENAME -> handleRename(buf)
                else -> errorResponse(OsConstants.ENOSYS)
            }
        } catch (e: PosixErrnoException) {
            errorResponse(e.errno)
        } catch (e: ErrnoException) {
            errorResponse(e.errno)
        } catch (_: BufferUnderflowException) {
            errorResponse(OsConstants.EINVAL)
        } catch (_: IllegalArgumentException) {
            errorResponse(OsConstants.EINVAL)
        } catch (_: SecurityException) {
            errorResponse(OsConstants.EACCES)
        } catch (_: FileNotFoundException) {
            errorResponse(OsConstants.ENOENT)
        } catch (_: IOException) {
            errorResponse(OsConstants.EIO)
        } catch (_: Throwable) {
            errorResponse(OsConstants.EIO)
        }
    }

    private fun handleStatFs(buf: ByteBuffer): ByteArray {
        val target = resolvePath(readString(buf))
        if (!target.exists()) throw PosixErrnoException(OsConstants.ENOENT)

        val outBsize = STATFS_BLOCK_SIZE
        val (blocks, bfree, bavail) = try {
            val vfs = Os.statvfs(target.absolutePath)
            val unit = when {
                vfs.f_frsize > 0L -> vfs.f_frsize
                vfs.f_bsize > 0L -> vfs.f_bsize
                else -> outBsize
            }
            if (unit == outBsize) {
                Triple(vfs.f_blocks, vfs.f_bfree, vfs.f_bavail)
            } else {
                Triple(
                    (vfs.f_blocks * unit) / outBsize,
                    (vfs.f_bfree * unit) / outBsize,
                    (vfs.f_bavail * unit) / outBsize
                )
            }
        } catch (_: Throwable) {
            val sf = StatFs(target.absolutePath)
            Triple(
                sf.totalBytes / outBsize,
                sf.freeBytes / outBsize,
                sf.availableBytes / outBsize
            )
        }

        return ByteBuffer.allocate(36)
            .order(ByteOrder.BIG_ENDIAN)
            .putInt(0)
            .putLong(outBsize)
            .putLong(blocks)
            .putLong(bfree)
            .putLong(bavail)
            .array()
    }

    private fun handleGetAttr(buf: ByteBuffer): ByteArray {
        val target = resolvePath(readString(buf))
        val attr = statAttr(target)
        return ByteBuffer.allocate(4 + ATTR_WIRE_SIZE)
            .order(ByteOrder.BIG_ENDIAN)
            .putInt(0)
            .also { putAttr(it, attr) }
            .array()
    }

    private fun handleReadDir(buf: ByteBuffer): ByteArray {
        val target = resolvePath(readString(buf))
        val st = lstatOrNull(target)
        if (st == null && !target.exists()) throw PosixErrnoException(OsConstants.ENOENT)
        val isDir = if (st != null) {
            if (OsConstants.S_ISLNK(st.st_mode)) target.isDirectory else OsConstants.S_ISDIR(st.st_mode)
        } else {
            target.isDirectory
        }
        if (!isDir) throw PosixErrnoException(OsConstants.ENOTDIR)

        val children = target.listFiles() ?: run {
            if (!target.exists()) throw PosixErrnoException(OsConstants.ENOENT)
            if (!target.isDirectory) throw PosixErrnoException(OsConstants.ENOTDIR)
            throw PosixErrnoException(OsConstants.EACCES)
        }

        val entries = ArrayList<Pair<ByteArray, FileAttr>>(children.size)
        var bodyBytes = 0
        for (child in children) {
            val name = child.name
            if (name == "." || name == "..") continue
            val nameBytes = name.toByteArray(Charsets.UTF_8)
            if (nameBytes.size > 0xFFFF) continue
            val attr = runCatching { statAttr(child) }.getOrNull() ?: continue
            entries.add(nameBytes to attr)
            bodyBytes += 2 + nameBytes.size + ATTR_WIRE_SIZE
        }

        val out = ByteBuffer.allocate(8 + bodyBytes).order(ByteOrder.BIG_ENDIAN)
        out.putInt(0)
        out.putInt(entries.size)
        for ((nameBytes, attr) in entries) {
            out.putShort((nameBytes.size and 0xFFFF).toShort())
            out.put(nameBytes)
            putAttr(out, attr)
        }
        return out.array()
    }

    private fun handleRead(buf: ByteBuffer): ByteArray {
        val target = resolvePath(readString(buf))
        if (buf.remaining() < 12) throw PosixErrnoException(OsConstants.EINVAL)
        val offset = buf.long
        val reqLen = buf.int.toLong() and 0xFFFF_FFFFL
        if (offset < 0L) throw PosixErrnoException(OsConstants.EINVAL)

        assertNoEscapingSymlink(target)
        if (!target.exists()) throw PosixErrnoException(OsConstants.ENOENT)
        if (target.isDirectory) throw PosixErrnoException(OsConstants.EISDIR)

        val cap = reqLen.coerceAtMost(MAX_READ_BYTES.toLong()).toInt()
        if (cap == 0) {
            return ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putInt(0).putInt(0).array()
        }

        val data = ByteArray(cap)
        var totalRead = 0
        try {
            RandomAccessFile(target, "r").use { raf ->
                if (offset < raf.length()) {
                    raf.seek(offset)
                    while (totalRead < cap) {
                        val read = raf.read(data, totalRead, cap - totalRead)
                        if (read <= 0) break
                        totalRead += read
                    }
                }
            }
        } catch (e: FileNotFoundException) {
            throw PosixErrnoException(if (target.exists()) OsConstants.EACCES else OsConstants.ENOENT)
        }

        return ByteBuffer.allocate(8 + totalRead)
            .order(ByteOrder.BIG_ENDIAN)
            .putInt(0)
            .putInt(totalRead)
            .also { if (totalRead > 0) it.put(data, 0, totalRead) }
            .array()
    }

    private fun handleWrite(buf: ByteBuffer): ByteArray {
        val target = resolvePath(readString(buf))
        if (buf.remaining() < 12) throw PosixErrnoException(OsConstants.EINVAL)
        val offset = buf.long
        val dataLenLong = buf.int.toLong() and 0xFFFF_FFFFL
        if (offset < 0L || dataLenLong > buf.remaining().toLong() || dataLenLong > Int.MAX_VALUE) {
            throw PosixErrnoException(OsConstants.EINVAL)
        }
        val dataLen = dataLenLong.toInt()

        assertNoEscapingSymlink(target)
        if (!target.exists()) throw PosixErrnoException(OsConstants.ENOENT)
        if (target.isDirectory) throw PosixErrnoException(OsConstants.EISDIR)

        try {
            RandomAccessFile(target, "rw").use { raf ->
                raf.seek(offset)
                if (dataLen > 0) {
                    raf.write(buf.array(), buf.arrayOffset() + buf.position(), dataLen)
                }
            }
        } catch (_: FileNotFoundException) {
            throw PosixErrnoException(if (target.exists()) OsConstants.EACCES else OsConstants.ENOENT)
        }

        return ByteBuffer.allocate(8)
            .order(ByteOrder.BIG_ENDIAN)
            .putInt(0)
            .putInt(dataLen)
            .array()
    }

    private fun handleCreate(buf: ByteBuffer): ByteArray {
        val target = resolvePath(readString(buf))
        if (buf.remaining() < 4) throw PosixErrnoException(OsConstants.EINVAL)
        buf.int // mode (unused on Android external storage)

        assertNoEscapingSymlink(target)
        if (target == rootDir) throw PosixErrnoException(OsConstants.EISDIR)
        val parent = target.parentFile ?: throw PosixErrnoException(OsConstants.ENOENT)
        if (!parent.exists()) throw PosixErrnoException(OsConstants.ENOENT)
        if (!parent.isDirectory) throw PosixErrnoException(OsConstants.ENOTDIR)

        if (target.isDirectory) throw PosixErrnoException(OsConstants.EISDIR)
        if (!target.exists()) {
            if (!target.createNewFile() && !target.exists()) {
                throw PosixErrnoException(OsConstants.EIO)
            }
        }
        return okResponse()
    }

    private fun handleTruncate(buf: ByteBuffer): ByteArray {
        val target = resolvePath(readString(buf))
        if (buf.remaining() < 8) throw PosixErrnoException(OsConstants.EINVAL)
        val size = buf.long
        if (size < 0L) throw PosixErrnoException(OsConstants.EINVAL)

        assertNoEscapingSymlink(target)
        if (!target.exists()) throw PosixErrnoException(OsConstants.ENOENT)
        if (target.isDirectory) throw PosixErrnoException(OsConstants.EISDIR)

        try {
            RandomAccessFile(target, "rw").use { it.setLength(size) }
        } catch (_: FileNotFoundException) {
            throw PosixErrnoException(if (target.exists()) OsConstants.EACCES else OsConstants.ENOENT)
        }
        return okResponse()
    }

    private fun handleMkdir(buf: ByteBuffer): ByteArray {
        val target = resolvePath(readString(buf))
        if (buf.remaining() < 4) throw PosixErrnoException(OsConstants.EINVAL)
        buf.int // mode

        if (existsAny(target)) throw PosixErrnoException(OsConstants.EEXIST)
        val parent = target.parentFile ?: throw PosixErrnoException(OsConstants.ENOENT)
        if (!parent.exists()) throw PosixErrnoException(OsConstants.ENOENT)
        if (!parent.isDirectory) throw PosixErrnoException(OsConstants.ENOTDIR)

        if (!target.mkdir()) {
            throw PosixErrnoException(if (existsAny(target)) OsConstants.EEXIST else OsConstants.EACCES)
        }
        return okResponse()
    }

    private fun handleUnlink(buf: ByteBuffer): ByteArray {
        val target = resolvePath(readString(buf))
        if (target == rootDir) throw PosixErrnoException(OsConstants.EISDIR)

        val st = lstatOrNull(target)
        if (st == null && !target.exists()) throw PosixErrnoException(OsConstants.ENOENT)
        val isDir = if (st != null) OsConstants.S_ISDIR(st.st_mode) else target.isDirectory
        if (isDir) throw PosixErrnoException(OsConstants.EISDIR)

        if (!target.delete()) {
            throw PosixErrnoException(if (existsAny(target)) OsConstants.EACCES else OsConstants.ENOENT)
        }
        return okResponse()
    }

    private fun handleRmdir(buf: ByteBuffer): ByteArray {
        val target = resolvePath(readString(buf))
        if (target == rootDir) throw PosixErrnoException(OsConstants.EACCES)

        val st = lstatOrNull(target)
        if (st == null && !target.exists()) throw PosixErrnoException(OsConstants.ENOENT)
        val isDir = if (st != null) OsConstants.S_ISDIR(st.st_mode) else target.isDirectory
        if (!isDir) throw PosixErrnoException(OsConstants.ENOTDIR)

        val children = target.list()
        if (children != null && children.isNotEmpty()) throw PosixErrnoException(OsConstants.ENOTEMPTY)

        if (!target.delete()) {
            val remaining = target.list()
            if (remaining != null && remaining.isNotEmpty()) throw PosixErrnoException(OsConstants.ENOTEMPTY)
            throw PosixErrnoException(if (existsAny(target)) OsConstants.EACCES else OsConstants.ENOENT)
        }
        return okResponse()
    }

    private fun handleRename(buf: ByteBuffer): ByteArray {
        val oldFile = resolvePath(readString(buf))
        val newFile = resolvePath(readString(buf))
        if (buf.remaining() < 4) throw PosixErrnoException(OsConstants.EINVAL)
        val flags = buf.int

        if (oldFile == rootDir || newFile == rootDir) throw PosixErrnoException(OsConstants.EACCES)
        if (!existsAny(oldFile)) throw PosixErrnoException(OsConstants.ENOENT)

        val newParent = newFile.parentFile ?: throw PosixErrnoException(OsConstants.ENOENT)
        if (!newParent.exists()) throw PosixErrnoException(OsConstants.ENOENT)
        if (!newParent.isDirectory) throw PosixErrnoException(OsConstants.ENOTDIR)

        val noReplace = (flags and RENAME_NOREPLACE) != 0
        if (noReplace && existsAny(newFile)) throw PosixErrnoException(OsConstants.EEXIST)

        if (oldFile == newFile) return okResponse()

        Os.rename(oldFile.path, newFile.path)
        return okResponse()
    }

    private fun statAttr(target: File): FileAttr {
        val lstat = try {
            Os.lstat(target.path)
        } catch (e: ErrnoException) {
            if (e.errno == OsConstants.ENOENT || e.errno == OsConstants.ENOTDIR) {
                throw e
            }
            null
        }

        if (lstat != null) {
            val resolved = if (OsConstants.S_ISLNK(lstat.st_mode)) {
                val targetCanBeFollowed = runCatching { isInsideRoot(target.canonicalFile) }.getOrDefault(false)
                if (targetCanBeFollowed) {
                    try {
                        Os.stat(target.path)
                    } catch (_: ErrnoException) {
                        lstat
                    }
                } else {
                    lstat
                }
            } else {
                lstat
            }

            val isDir = OsConstants.S_ISDIR(resolved.st_mode)
            val isLink = OsConstants.S_ISLNK(resolved.st_mode)
            val mode = when {
                isDir -> OsConstants.S_IFDIR or DIR_PERMS
                isLink -> OsConstants.S_IFLNK or LINK_PERMS
                else -> {
                    val perms = if (target.canWrite()) FILE_RW_PERMS else FILE_RO_PERMS
                    OsConstants.S_IFREG or perms
                }
            }
            return FileAttr(
                mode = mode,
                size = resolved.st_size.coerceAtLeast(0L),
                atimeSec = resolved.st_atime,
                mtimeSec = resolved.st_mtime
            )
        }

        if (!target.exists()) throw PosixErrnoException(OsConstants.ENOENT)
        val isDir = target.isDirectory
        val mode = if (isDir) {
            OsConstants.S_IFDIR or DIR_PERMS
        } else {
            OsConstants.S_IFREG or (if (target.canWrite()) FILE_RW_PERMS else FILE_RO_PERMS)
        }
        val mtimeSec = (target.lastModified() / 1000L).coerceAtLeast(0L)
        val size = if (isDir) 0L else target.length().coerceAtLeast(0L)
        return FileAttr(mode = mode, size = size, atimeSec = mtimeSec, mtimeSec = mtimeSec)
    }

    private fun resolvePath(rawPath: String): File {
        if (rawPath.indexOf('\u0000') >= 0) throw PosixErrnoException(OsConstants.EINVAL)
        val stack = ArrayList<String>()
        for (segment in rawPath.split('/')) {
            when (segment) {
                "", "." -> continue
                ".." -> {
                    if (stack.isEmpty()) throw PosixErrnoException(OsConstants.EACCES)
                    stack.removeAt(stack.size - 1)
                }
                else -> stack.add(segment)
            }
        }
        val candidate = if (stack.isEmpty()) rootDir else File(rootDir, stack.joinToString(File.separator))
        val canonical = runCatching { candidate.canonicalFile }.getOrNull()
            ?: throw PosixErrnoException(OsConstants.EACCES)
        if (!isInsideRoot(canonical)) throw PosixErrnoException(OsConstants.EACCES)
        return candidate
    }

    private fun assertNoEscapingSymlink(target: File) {
        val st = lstatOrNull(target) ?: return
        if (!OsConstants.S_ISLNK(st.st_mode)) return
        val linkDest = runCatching { Os.readlink(target.path) }.getOrNull()
            ?: throw PosixErrnoException(OsConstants.EACCES)
        val destFile = File(linkDest).let {
            if (it.isAbsolute) it else File(target.parentFile ?: rootDir, linkDest)
        }
        val canonical = runCatching { destFile.canonicalFile }.getOrNull()
            ?: throw PosixErrnoException(OsConstants.EACCES)
        if (!isInsideRoot(canonical)) throw PosixErrnoException(OsConstants.EACCES)
    }

    private fun isInsideRoot(canonical: File): Boolean =
        canonical == rootDir || canonical.path.startsWith(rootPrefix)

    private fun lstatOrNull(target: File): StructStat? =
        try {
            Os.lstat(target.path)
        } catch (_: ErrnoException) {
            null
        }

    private fun existsAny(target: File): Boolean =
        lstatOrNull(target) != null || target.exists()

    private fun readString(buf: ByteBuffer): String {
        if (buf.remaining() < 2) throw PosixErrnoException(OsConstants.EINVAL)
        val len = buf.short.toInt() and 0xFFFF
        if (buf.remaining() < len) throw PosixErrnoException(OsConstants.EINVAL)
        val bytes = ByteArray(len)
        buf.get(bytes)
        return String(bytes, Charsets.UTF_8)
    }

    private fun putAttr(buf: ByteBuffer, attr: FileAttr) {
        buf.putInt(attr.mode)
        buf.putLong(attr.size)
        buf.putLong(attr.atimeSec)
        buf.putLong(attr.mtimeSec)
    }

    private fun okResponse(): ByteArray =
        ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(0).array()

    private data class FileAttr(
        val mode: Int,
        val size: Long,
        val atimeSec: Long,
        val mtimeSec: Long
    )

    private class PosixErrnoException(val errno: Int) : RuntimeException()

    companion object {
        private const val STATFS_BLOCK_SIZE = 4096L
        private const val MAX_READ_BYTES = 131_072
        private const val ATTR_WIRE_SIZE = 28
        private const val RENAME_NOREPLACE = 1

        private const val DIR_PERMS = 0b111_101_101 // 0o755
        private const val FILE_RW_PERMS = 0b110_100_100 // 0o644
        private const val FILE_RO_PERMS = 0b100_100_100 // 0o444
        private const val LINK_PERMS = 0b111_111_111 // 0o777

        fun defaultRootDir(): File {
            val emulated = File("/storage/emulated/0")
            if (emulated.exists() && emulated.canRead()) return emulated
            return runCatching { Environment.getExternalStorageDirectory() }.getOrElse { emulated }
        }

        fun errorResponse(errno: Int): ByteArray =
            ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(errno).array()
    }
}
