package com.example.ttar

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.Files
import java.util.ArrayDeque
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.Future

private const val BLOCK = 512
/** USTAR size 字段上限：11 位八进制 = 8^11 - 1 字节 */
private const val USTAR_SIZE_MAX = 8_589_934_591L
private const val MODE_FILE = 420
private const val MODE_DIR = 493
private const val MODE_SYMLINK = 511

private const val TF_REG = 0x30
private const val TF_DIR = 0x35
private const val TF_LNK = 0x32
private const val TF_PAX = 0x78

private const val PROGRESS_INTERVAL_MS = 100L

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
    val totalSec: Double,
    val usedOptions: Options,
    val tuneReason: String?,
    val heapStart: Long,
    val heapPeak: Long,
    val heapEnd: Long,
    val heapMax: Long
)

class TarPacker {

    /** 只做扫描，返回条目列表。供 UI 缓存后复用。 */
    fun scanOnly(
        sourcePath: String,
        options: Options = Options(),
        onScanProgress: (found: Int) -> Unit = {}
    ): List<Item> {
        val source = File(sourcePath)
        if (!source.exists()) throw IllegalArgumentException("源不存在: $sourcePath")
        val rootName = source.name.ifEmpty { "root" }
        val items = mutableListOf<Item>()
        scan(source, rootName, items, options, onScanProgress)
        return items
    }

    fun pack(
        sourcePath: String,
        outputPath: String,
        options: Options = Options(),
        preScannedItems: List<Item>? = null,
        onScanProgress: (found: Int) -> Unit = {},
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }
    ): PackStats {
        val t0 = System.currentTimeMillis()

        val source = File(sourcePath)
        if (!source.exists()) throw IllegalArgumentException("源不存在: $sourcePath")

        val output = File(outputPath)
        output.parentFile?.mkdirs()

        // ---- 扫描（或复用外部传入的结果）----
        val items: List<Item> = if (preScannedItems != null) {
            preScannedItems
        } else {
            val rootName = source.name.ifEmpty { "root" }
            val tmp = mutableListOf<Item>()
            scan(source, rootName, tmp, options, onScanProgress)
            tmp
        }

        // ---- 内存采样 ----
        fun usedHeap(): Long {
            val rt = Runtime.getRuntime()
            return rt.totalMemory() - rt.freeMemory()
        }
        val heapMax = Runtime.getRuntime().maxMemory()
        val heapStart = usedHeap()
        var heapPeak = heapStart

        // ---- 自动调参 ----
        var tuneReason: String? = null
        val effective: Options = if (options.autoTune) {
            val r = AutoTuner.tune(items, options)
            tuneReason = r.reason
            r.options
        } else {
            options
        }

        val total = items.size
        val dirCount = items.count { it.isDir }
        val symCount = items.count { it.isSymlink }
        val bigCount = items.count {
            !it.isDir && !it.isSymlink && it.size > effective.bigfileThreshold
        }

        // ---- 打包 ----
        var done = 0
        var totalBytes = 0L
        var written = 0L
        var paxCount = 0
        var lastReport = 0L

        fun reportProgress(force: Boolean = false) {
            val now = System.currentTimeMillis()
            if (force || now - lastReport >= PROGRESS_INTERVAL_MS) {
                val used = usedHeap()
                if (used > heapPeak) heapPeak = used
                onProgress(done, total)
                lastReport = now
            }
        }

        val ex = Executors.newFixedThreadPool(effective.workers)
        val chunkBuf = ByteArray(effective.chunkSize)
        val zeros = ByteArray(BLOCK)
        val win = effective.workers * effective.windowFactor

        try {
            BufferedOutputStream(FileOutputStream(output), 1 shl 20).use { bos ->
                val itemsIt = items.iterator()
                val queue = ArrayDeque<Pair<Item, Future<ByteArray>?>>()

                // 队列中已提交任务的字节数上限
                // 取可用堆的 70%，最少 64 MB，最多 320 MB
                // 略高于 AutoTuner 的 60%，作为运行时硬边界
                val rt = Runtime.getRuntime()
                val availHeap = rt.maxMemory() - (rt.totalMemory() - rt.freeMemory())
                val pendingByteLimit = (availHeap * 70 / 100)
                    .coerceIn(64L * 1024 * 1024, 320L * 1024 * 1024)
                var pendingBytes = 0L

                fun submit(item: Item): Future<ByteArray>? {
                    if (item.isDir || item.isSymlink) return null
                    if (item.size > effective.bigfileThreshold) return null
                    // 队列数据超预算 → 不预读，改走主线程流式
                    if (pendingBytes + item.size > pendingByteLimit) return null
                    pendingBytes += item.size
                    return ex.submit(Callable { item.path.readBytes() })
                }

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
                        item.isDir -> {
                            val arc = arcname.trimEnd('/') + "/"
                            if (needsPaxName(arc)) {
                                val pax = makePaxEntry(arc, null, item.mtime)
                                bos.write(pax); written += pax.size; paxCount++
                            }
                            val hdr = makeHeader(arc, 0, item.mode, item.mtime, isDir = true)
                            bos.write(hdr); written += hdr.size
                        }
                        item.isSymlink -> {
                            if (effective.keepSymlinks) {
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
                        }
                        else -> {
                            val needPaxSize = item.size > USTAR_SIZE_MAX
                            val needPaxPath = needsPaxName(arcname)
                            if (needPaxSize || needPaxPath) {
                                val pax = makePaxEntry(
                                    if (needPaxPath) arcname else null,
                                    null, item.mtime,
                                    if (needPaxSize) item.size else null
                                )
                                bos.write(pax); written += pax.size; paxCount++
                            }
                            val hdrSize = if (needPaxSize) 0L else item.size
                            val hdr = makeHeader(arcname, hdrSize, item.mode, item.mtime, isDir = false)
                            bos.write(hdr); written += hdr.size

                            if (fut != null) {
                                val data = fut.get()
                                pendingBytes -= item.size
                                bos.write(data); written += data.size
                                val pad = (BLOCK - (data.size % BLOCK)) % BLOCK
                                if (pad > 0) { bos.write(zeros, 0, pad); written += pad }
                            } else {
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
                    reportProgress(done == total)

                    if (itemsIt.hasNext()) {
                        val nxt = itemsIt.next()
                        queue.addLast(nxt to submit(nxt))
                    }
                }

                bos.write(ByteArray(1024))
                written += 1024
            }
        } finally {
            ex.shutdown()
        }

        val totalSec = (System.currentTimeMillis() - t0) / 1000.0
        val heapEnd = usedHeap()
        if (heapEnd > heapPeak) heapPeak = heapEnd
        return PackStats(
            files = total - dirCount,
            dirs = dirCount,
            symlinks = symCount,
            rawBytes = totalBytes,
            written = written,
            paxCount = paxCount,
            bigFiles = bigCount,
            totalSec = totalSec,
            usedOptions = effective,
            tuneReason = tuneReason,
            heapStart = heapStart,
            heapPeak = heapPeak,
            heapEnd = heapEnd,
            heapMax = heapMax
        )
    }

    private fun scan(
        f: File, arcRoot: String,
        items: MutableList<Item>,
        options: Options,
        onScanProgress: (Int) -> Unit
    ) {
        val nio = f.toPath()
        val isLink = Files.isSymbolicLink(nio)

        when {
            isLink -> {
                val target = try { Files.readSymbolicLink(nio).toString() } catch (e: Exception) { "" }
                items.add(Item(f, arcRoot, isDir = false, isSymlink = true,
                               linkTarget = target, size = 0,
                               mode = MODE_SYMLINK, mtime = f.lastModified() / 1000))
            }
            f.isDirectory -> {
                items.add(Item(f, arcRoot, isDir = true, isSymlink = false,
                               linkTarget = null, size = 0,
                               mode = MODE_DIR, mtime = f.lastModified() / 1000))
                f.listFiles()?.forEach { child ->
                    if (options.skipHidden && child.name.startsWith(".")) return@forEach
                    scan(child, "$arcRoot/${child.name}", items, options, onScanProgress)
                }
            }
            f.isFile -> {
                if (options.skipHidden && f.name.startsWith(".")) return
                items.add(Item(f, arcRoot, isDir = false, isSymlink = false,
                               linkTarget = null, size = f.length(),
                               mode = MODE_FILE, mtime = f.lastModified() / 1000))
            }
        }

        if (items.size % 200 == 0) onScanProgress(items.size)
    }

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
        writeOctal(h, 100, 7, mode.toLong() and 0xFFF)
        writeOctal(h, 108, 7, 0)
        writeOctal(h, 116, 7, 0)
        writeOctal(h, 124, 11, size)
        writeOctal(h, 136, 11, mtime)
        for (i in 148 until 156) h[i] = ' '.code.toByte()
        h[156] = when {
            typeflag != null -> typeflag.toByte()
            linkname != null -> TF_LNK.toByte()
            isDir -> TF_DIR.toByte()
            else -> TF_REG.toByte()
        }
        h[257] = 'u'.code.toByte(); h[258] = 's'.code.toByte()
        h[259] = 't'.code.toByte(); h[260] = 'a'.code.toByte()
        h[261] = 'r'.code.toByte(); h[262] = 0
        h[263] = '0'.code.toByte(); h[264] = '0'.code.toByte()
        if (prefix.isNotEmpty()) System.arraycopy(prefix, 0, h, 345, prefix.size)
        if (linkname != null) {
            val lb = linkname.toByteArray(Charsets.UTF_8)
            System.arraycopy(lb, 0, h, 157, minOf(lb.size, 100))
        }
        var cksum = 0
        for (b in h) cksum += b.toInt() and 0xFF
        val ck = "%06o".format(cksum).toByteArray(Charsets.US_ASCII)
        System.arraycopy(ck, 0, h, 148, 6)
        h[154] = 0; h[155] = ' '.code.toByte()
        return h
    }

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

    private fun makePaxEntry(pathValue: String?, linkValue: String?, mtime: Long,
                             sizeValue: Long? = null): ByteArray {
        var content = ByteArray(0)
        if (pathValue != null) content += paxLine("path", pathValue)
        if (linkValue != null) content += paxLine("linkpath", linkValue)
        if (sizeValue != null) content += paxLine("size", sizeValue.toString())
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
