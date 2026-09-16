package com.example.ttar.core

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.Files
import java.util.ArrayDeque
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.Future

// ---------- 可调参数 ----------

/** 超过此大小走主线程流式读，避免占用窗口内存。 */
const val BIGFILE_THRESHOLD = 8L shl 20   // 8 MB

/** 流式读的块大小。 */
const val CHUNK_SIZE = 1 shl 20            // 1 MB

/** 并行读盘的线程数。 */
const val DEFAULT_WORKERS = 8

/** 窗口大小系数：window = workers * WINDOW_FACTOR。 */
const val WINDOW_FACTOR = 2

// ---------- USTAR 常量 ----------

private const val BLOCK = 512
private const val MODE_FILE = 420      // 0o644
private const val MODE_DIR = 493       // 0o755
private const val MODE_SYMLINK = 511   // 0o777

private const val TF_REG = 0x30   // '0'
private const val TF_DIR = 0x35   // '5'
private const val TF_LNK = 0x32   // '2'
private const val TF_PAX = 0x78   // 'x'

// ---------- 数据结构 ----------

data class Item(
    val path: File,
    val arcname: String,
    val isDir: Boolean,
    val isSymlink: Boolean,
    val linkTarget: String?,
    val size: Long,
    val mode: Int,
    val mtime: Long
)

data class PackStats(
    val files: Int,
    val dirs: Int,
    val symlinks: Int,
    val rawBytes: Long,
    val written: Long,
    val paxCount: Int,
    val bigFiles: Int,
    val totalSec: Double
)

/**
 * 打包未压缩 tar。
 *
 * 调用方只需提供源路径和输出路径，进度通过回调上报。
 * 与 UI/权限/文件选择器完全解耦。
 */
class TarPacker {

    /**
     * @param sourcePath 源文件或目录的绝对路径
     * @param outputPath 输出 tar 的绝对路径（会覆盖同名文件）
     * @param onProgress 进度回调 (已处理项数, 总项数)，在打包线程中调用
     */
    fun pack(
        sourcePath: String,
        outputPath: String,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }
    ): PackStats {
        val t0 = System.currentTimeMillis()

        val source = File(sourcePath)
        if (!source.exists()) throw IllegalArgumentException("源不存在: $sourcePath")

        val output = File(outputPath)
        output.parentFile?.mkdirs()

        // ---- 1. 扫描 ----
        val rootName = source.name.ifEmpty { "root" }
        val items = mutableListOf<Item>()
        scan(source, rootName, items)

        val total = items.size
        val dirCount = items.count { it.isDir }
        val symCount = items.count { it.isSymlink }
        val bigCount = items.count { !it.isDir && !it.isSymlink && it.size > BIGFILE_THRESHOLD }

        // ---- 2. 流式打包 ----
        var done = 0
        var totalBytes = 0L
        var written = 0L
        var paxCount = 0

        val ex = Executors.newFixedThreadPool(DEFAULT_WORKERS)
        val chunkBuf = ByteArray(CHUNK_SIZE)
        val zeros = ByteArray(BLOCK)
        val win = DEFAULT_WORKERS * WINDOW_FACTOR

        try {
            BufferedOutputStream(FileOutputStream(output), 1 shl 20).use { bos ->
                val itemsIt = items.iterator()
                val queue = ArrayDeque<Pair<Item, Future<ByteArray>?>>()

                fun submit(item: Item): Future<ByteArray>? {
                    if (item.isDir || item.isSymlink) return null
                    if (item.size > BIGFILE_THRESHOLD) return null
                    return ex.submit(Callable { item.path.readBytes() })
                }

                // 预填窗口
                repeat(win) {
                    if (itemsIt.hasNext()) {
                        val item = itemsIt.next()
                        queue.addLast(item to submit(item))
                    }
                }

                while (queue.isNotEmpty()) {
                    val (item, fut) = queue.removeFirst()
                    val arcname = item.arcname.replace(File.separatorChar, '/').trimStart('/')

                    when {
                        // ---- 目录 ----
                        item.isDir -> {
                            val arc = arcname.trimEnd('/') + "/"
                            if (needsPaxName(arc)) {
                                val pax = makePaxEntry(arc, null, item.mtime)
                                bos.write(pax); written += pax.size; paxCount++
                            }
                            val hdr = makeHeader(arc, 0, item.mode, item.mtime, isDir = true)
                            bos.write(hdr); written += hdr.size
                        }

                        // ---- 符号链接 ----
                        item.isSymlink -> {
                            val target = item.linkTarget ?: ""
                            val paxName = if (needsPaxName(arcname)) arcname else null
                            val paxLink = if (needsPaxLink(target)) target else null
                            if (paxName != null || paxLink != null) {
                                val pax = makePaxEntry(paxName, paxLink, item.mtime)
                                bos.write(pax); written += pax.size; paxCount++
                            }
                            val hdr = makeHeader(arcname, 0, item.mode, item.mtime,
                                                 isDir = false, linkname = target)
                            bos.write(hdr); written += hdr.size
                        }

                        // ---- 普通文件 ----
                        else -> {
                            if (needsPaxName(arcname)) {
                                val pax = makePaxEntry(arcname, null, item.mtime)
                                bos.write(pax); written += pax.size; paxCount++
                            }
                            val hdr = makeHeader(arcname, item.size, item.mode, item.mtime, isDir = false)
                            bos.write(hdr); written += hdr.size

                            if (fut != null) {
                                // 小文件：用预读好的字节
                                val data = fut.get()
                                bos.write(data); written += data.size
                                val pad = (BLOCK - (data.size % BLOCK)) % BLOCK
                                if (pad > 0) { bos.write(zeros, 0, pad); written += pad }
                            } else {
                                // 大文件：主线程分块流式读
                                FileInputStream(item.path).use { s ->
                                    while (true) {
                                        val n = s.read(chunkBuf)
                                        if (n < 0) break
                                        bos.write(chunkBuf, 0, n)
                                        written += n
                                    }
                                }
                                val pad = (BLOCK - (item.size % BLOCK).toInt()) % BLOCK
                                if (pad > 0) { bos.write(zeros, 0, pad); written += pad }
                            }
                            totalBytes += item.size
                        }
                    }

                    done++
                    if (done % 100 == 0 || done == total) onProgress(done, total)

                    if (itemsIt.hasNext()) {
                        val nxt = itemsIt.next()
                        queue.addLast(nxt to submit(nxt))
                    }
                }

                // tar 结束标记：两个 512 字节零块
                bos.write(ByteArray(1024))
                written += 1024
            }
        } finally {
            ex.shutdown()
        }

        val totalSec = (System.currentTimeMillis() - t0) / 1000.0
        return PackStats(
            files = total - dirCount,
            dirs = dirCount,
            symlinks = symCount,
            rawBytes = totalBytes,
            written = written,
            paxCount = paxCount,
            bigFiles = bigCount,
            totalSec = totalSec
        )
    }

    // ---------- 扫描 ----------

    private fun scan(f: File, arcRoot: String, items: MutableList<Item>) {
        val nio = f.toPath()
        val isLink = Files.isSymbolicLink(nio)

        when {
            isLink -> {
                val target = try {
                    Files.readSymbolicLink(nio).toString()
                } catch (e: Exception) { "" }
                items.add(Item(f, arcRoot, isDir = false, isSymlink = true,
                               linkTarget = target, size = 0,
                               mode = MODE_SYMLINK, mtime = f.lastModified() / 1000))
            }
            f.isDirectory -> {
                items.add(Item(f, arcRoot, isDir = true, isSymlink = false,
                               linkTarget = null, size = 0,
                               mode = MODE_DIR, mtime = f.lastModified() / 1000))
                f.listFiles()?.forEach { child ->
                    scan(child, "$arcRoot/${child.name}", items)
                }
            }
            f.isFile -> {
                items.add(Item(f, arcRoot, isDir = false, isSymlink = false,
                               linkTarget = null, size = f.length(),
                               mode = MODE_FILE, mtime = f.lastModified() / 1000))
            }
        }
    }

    // ---------- USTAR / PAX ----------

    /**
     * 尝试把超长路径拆成 prefix(≤155) + name(≤100)。
     * 拆不了就返回 null，调用方改用 PAX。
     */
    private fun splitName(nb: ByteArray): Pair<ByteArray, ByteArray>? {
        if (nb.size <= 100) return ByteArray(0) to nb
        val lo = maxOf(0, nb.size - 100 - 1)
        val hi = minOf(155, nb.size - 1)
        for (idx in hi downTo lo) {
            if (nb[idx] == '/'.code.toByte()) {
                val prefix = nb.copyOfRange(0, idx)
                val name = nb.copyOfRange(idx + 1, nb.size)
                if (prefix.size <= 155 && name.size <= 100) return prefix to name
            }
        }
        return null
    }

    private fun writeOctal(dst: ByteArray, offset: Int, width: Int, value: Long) {
        val s = value.toString(8).padStart(width, '0')
        val b = s.toByteArray(Charsets.US_ASCII)
        System.arraycopy(b, 0, dst, offset, width)
        dst[offset + width] = 0
    }

    /**
     * 构造 512 字节 USTAR header。
     * typeflag / linkname 由调用方指定，自动推断。
     */
    private fun makeHeader(
        name: String, size: Long, mode: Int, mtime: Long,
        isDir: Boolean = false,
        linkname: String? = null,
        typeflag: Int? = null
    ): ByteArray {
        var nb = name.toByteArray(Charsets.UTF_8)
        var prefix = ByteArray(0)
        if (nb.size > 100) {
            val split = splitName(nb)
            if (split != null) { prefix = split.first; nb = split.second }
            else nb = nb.copyOfRange(0, 100)
        }

        val h = ByteArray(BLOCK)
        System.arraycopy(nb, 0, h, 0, nb.size)

        writeOctal(h, 100, 7, mode.toLong() and 0xFFF)   // mode
        writeOctal(h, 108, 7, 0)                          // uid
        writeOctal(h, 116, 7, 0)                          // gid
        writeOctal(h, 124, 11, size)                      // size
        writeOctal(h, 136, 11, mtime)                     // mtime

        for (i in 148 until 156) h[i] = ' '.code.toByte() // checksum 占位

        h[156] = when {
            typeflag != null -> typeflag.toByte()
            linkname != null -> TF_LNK.toByte()
            isDir            -> TF_DIR.toByte()
            else             -> TF_REG.toByte()
        }

        h[257] = 'u'.code.toByte()
        h[258] = 's'.code.toByte()
        h[259] = 't'.code.toByte()
        h[260] = 'a'.code.toByte()
        h[261] = 'r'.code.toByte()
        h[262] = 0
        h[263] = '0'.code.toByte()
        h[264] = '0'.code.toByte()

        if (prefix.isNotEmpty()) System.arraycopy(prefix, 0, h, 345, prefix.size)

        if (linkname != null) {
            val lb = linkname.toByteArray(Charsets.UTF_8)
            System.arraycopy(lb, 0, h, 157, minOf(lb.size, 100))
        }

        // checksum
        var cksum = 0
        for (b in h) cksum += b.toInt() and 0xFF
        val ck = "%06o".format(cksum).toByteArray(Charsets.US_ASCII)
        System.arraycopy(ck, 0, h, 148, 6)
        h[154] = 0
        h[155] = ' '.code.toByte()

        return h
    }

    /** 构造一行 PAX 记录：`<len> key=value\n`，len 是整行字节数。 */
    private fun paxLine(key: String, value: String): ByteArray {
        val body = key.toByteArray(Charsets.US_ASCII) +
                   "=".toByteArray(Charsets.US_ASCII) +
                   value.toByteArray(Charsets.UTF_8) +
                   "\n".toByteArray(Charsets.US_ASCII)
        var n = body.size + 2
        while (true) {
            val prefix = "$n ".toByteArray(Charsets.US_ASCII)
            val total = prefix.size + body.size
            if (total == n) return prefix + body
            n = total
        }
    }

    /** 构造 PAX 扩展头（typeflag='x'），可同时带 path 和 linkpath。 */
    private fun makePaxEntry(pathValue: String?, linkValue: String?, mtime: Long): ByteArray {
        var content = ByteArray(0)
        if (pathValue != null) content += paxLine("path", pathValue)
        if (linkValue != null) content += paxLine("linkpath", linkValue)
        val hdr = makeHeader("PaxHeaders/entry", content.size.toLong(),
                             MODE_FILE, mtime, isDir = false, typeflag = TF_PAX)
        val pad = (BLOCK - content.size % BLOCK) % BLOCK
        return hdr + content + ByteArray(pad)
    }

    private fun needsPaxName(name: String): Boolean {
        val nb = name.toByteArray(Charsets.UTF_8)
        if (nb.size <= 100) return false
        return splitName(nb) == null
    }

    private fun needsPaxLink(target: String): Boolean =
        target.toByteArray(Charsets.UTF_8).size > 100
}
