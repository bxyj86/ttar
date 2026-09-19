// ttar.kt
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.ArrayDeque
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.Future
import kotlin.system.exitProcess

const val DEFAULT_WORKERS = 8
const val DEFAULT_WINDOW = 16
const val BIGFILE_THRESHOLD = 8L shl 20   // 8 MB 以上走流式
const val CHUNK_SIZE = 1 shl 20            // 流式读块大小 1 MB
const val PROGRESS_INTERVAL_MS = 100L      // 进度回调最小间隔

/** USTAR size 字段上限：11 位八进制 = 8^11 - 1 字节（约 8 GiB）
 *  超过此值的文件必须在 PAX 扩展头里记录真实 size，
 *  否则 header 里的八进制字符串会被截断，导致 tar 结构损坏。 */
const val USTAR_SIZE_MAX = 8_589_934_591L

const val MODE_FILE = 420      // 0o644
const val MODE_DIR = 493       // 0o755
const val MODE_SYMLINK = 511   // 0o777

const val TF_REG = 0x30
const val TF_DIR = 0x35
const val TF_LNK = 0x32
const val TF_PAX = 0x78

// tar 结束块，全局只分配一次
private val TAR_END = ByteArray(1024)
private val ZEROS_512 = ByteArray(512)

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

data class Stats(
    val scanSec: Double, val packSec: Double, val totalSec: Double,
    val files: Int, val rawBytes: Long, val written: Long,
    val workers: Int, val window: Int, val pax: Int,
    val bigFiles: Int, val symlinks: Int,
    val threshold: Long, val chunkSize: Int,
    val tuneReason: String?
)

data class TunedParams(
    val workers: Int,
    val threshold: Long,
    val chunkSize: Int,
    val window: Int,
    val reason: String?
)

fun fmtTime(sec: Double): String {
    if (sec < 60) return "%.2f 秒".format(sec)
    val m = (sec / 60).toInt()
    val s = sec - m * 60
    if (m < 60) return "$m 分 %.2f 秒".format(s)
    val h = m / 60
    val mm = m % 60
    return "$h 时 $mm 分 %.2f 秒".format(s)
}

fun fmtSize(b: Long): String = when {
    b < 1024L -> "$b B"
    b < 1024L * 1024L -> "%.1f KB".format(b / 1024.0)
    b < 1024L * 1024L * 1024L -> "%.1f MB".format(b / 1024.0 / 1024.0)
    else -> "%.2f GB".format(b / 1024.0 / 1024.0 / 1024.0)
}

fun basename(p: String): String {
    var s = p.trimEnd('/', '\\')
    if (s.isEmpty()) s = p
    val idx = maxOf(s.lastIndexOf('/'), s.lastIndexOf('\\'))
    return if (idx >= 0) s.substring(idx + 1) else s
}

fun printUsage() {
    System.err.println(
        """
用法: java -jar ttar.jar <paths...> [-o <output.tar>] [-j workers] [-w window]

参数:
  <paths...>        要打包的文件或目录（可多个）
  -o, --output      输出 tar 路径；省略则自动推断
  -j, --workers     读取线程数（省略则自动调参）
  -w, --window      预取窗口（省略则自动调参）
  -h, --help        显示帮助

自动调参:
  未指定 -j 或 -w 时，扫描完成后根据文件大小分布和可用堆自动选择参数。
  指定了 -j 或 -w 中任意一个，则全部使用手动值。

输出路径推断规则:
  1. 给了 -o/--output，用它
  2. 最后一个参数以 .tar 结尾（且参数数 > 1），当作输出
  3. 都没有，输出到 ./<第一个输入的 basename>.tar

示例:
  java -jar ttar.jar ./文件夹名/
  java -jar ttar.jar ./文件夹名/ ./其他/
  java -jar ttar.jar ./文件夹名/ 文件夹名.tar
  java -jar ttar.jar ./文件夹名/ -o /tmp/文件夹名.tar
  java -jar ttar.jar ./文件夹名/ -j 16 -w 16
        """.trimIndent()
    )
}

// ---------- 自动调参 ----------

fun autoTune(items: List<Item>): TunedParams {
    val files = items.filter { !it.isDir && !it.isSymlink }
    if (files.isEmpty()) {
        return TunedParams(DEFAULT_WORKERS, BIGFILE_THRESHOLD, CHUNK_SIZE,
                          DEFAULT_WINDOW, "无文件，使用默认参数")
    }

    val sizes = files.map { it.size }.sorted()
    val n = sizes.size
    val median = sizes[n / 2]
    val maxSize = sizes[n - 1]
    val bigCount = files.count { it.size > 8L shl 20 }
    val bigRatio = bigCount.toDouble() / n

    val rt = Runtime.getRuntime()
    val availHeap = rt.maxMemory() - (rt.totalMemory() - rt.freeMemory())
    val heapMb = availHeap / 1024 / 1024

    // 1. 线程数：大文件为主 → 低并发（主循环串行），小文件为主 → 高并发
    val workers = when {
        bigRatio > 0.5 -> 6
        else -> 16
    }

    // 2. 阈值：堆建议值 vs 最大文件的 1/4，取小
    //    保证最大的那个文件一定走流式，不占窗口槽位
    val heapCandidate = when {
        heapMb < 200 -> 4
        heapMb < 400 -> 8
        heapMb < 800 -> 16
        else -> 32
    }
    val maxSizeMb = (maxSize / 1024 / 1024).toInt().coerceAtLeast(1)
    val thresholdCeiling = maxOf(4, maxSizeMb / 4)
    val bigfileMb = minOf(heapCandidate, thresholdCeiling)
    val threshold = bigfileMb.toLong() * 1024 * 1024

    // 3. 块大小：最大文件越大，块越大
    val chunkMb = when {
        maxSize < 64L shl 20 -> 4
        maxSize < 512L shl 20 -> 16
        else -> 32
    }

    // 4. 窗口 = 线程数（实测最优，再大反而变慢）
    val window = workers

    val reason = "文件 $n，中位 ${fmtSize(median)}，最大 ${fmtSize(maxSize)}，" +
                 "大文件 $bigCount (${"%.1f".format(bigRatio * 100)}%)，" +
                 "可用堆 ${heapMb} MB"

    return TunedParams(workers, threshold, chunkMb * 1024 * 1024, window, reason)
}

// ---------- USTAR / PAX ----------
fun splitName(nb: ByteArray): Pair<ByteArray, ByteArray>? {
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

fun writeOctal(dst: ByteArray, offset: Int, width: Int, value: Long) {
    val s = value.toString(8).padStart(width, '0')
    val b = s.toByteArray(Charsets.US_ASCII)
    System.arraycopy(b, 0, dst, offset, width)
    dst[offset + width] = 0
}

fun makeHeader(
    name: String, size: Long, mode: Int, mtime: Long,
    isDir: Boolean = false,
    linkname: String? = null,
    typeflag: Int? = null
): ByteArray {
    var nb = name.toByteArray(Charsets.UTF_8)
    var prefix = ByteArray(0)
    if (nb.size > 100) {
        val split = splitName(nb)
        if (split != null) {
            prefix = split.first
            nb = split.second
        } else {
            nb = nb.copyOfRange(0, 100)
        }
    }
    val h = ByteArray(512)
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
        val n = minOf(lb.size, 100)
        System.arraycopy(lb, 0, h, 157, n)
    }

    var cksum = 0
    for (b in h) cksum += b.toInt() and 0xFF
    val ck = "%06o".format(cksum).toByteArray(Charsets.US_ASCII)
    System.arraycopy(ck, 0, h, 148, 6)
    h[154] = 0
    h[155] = ' '.code.toByte()
    return h
}

fun paxLine(key: String, value: String): ByteArray {
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

/**
 * 构造 PAX 扩展头。
 * pathValue / linkValue / sizeValue 各自可为 null，只有非 null 的项才会写入。
 * sizeValue 用于单个文件超过 USTAR_SIZE_MAX 时记录真实大小。
 */
fun makePaxEntry(pathValue: String?, linkValue: String?, mtime: Long,
                 sizeValue: Long? = null): ByteArray {
    var content = ByteArray(0)
    if (pathValue != null) content += paxLine("path", pathValue)
    if (linkValue != null) content += paxLine("linkpath", linkValue)
    if (sizeValue != null) content += paxLine("size", sizeValue.toString())
    val hdr = makeHeader(
        "PaxHeaders/entry", content.size.toLong(), MODE_FILE, mtime,
        isDir = false, linkname = null, typeflag = TF_PAX
    )
    val pad = (512 - content.size % 512) % 512
    return hdr + content + ByteArray(pad)
}

fun needsPaxName(name: String): Boolean {
    val nb = name.toByteArray(Charsets.UTF_8)
    if (nb.size <= 100) return false
    return splitName(nb) == null
}

fun needsPaxLink(target: String): Boolean =
    target.toByteArray(Charsets.UTF_8).size > 100

// ---------- 扫描（用 readAttributes 一次性拿全部属性） ----------

/** 一次系统调用拿到 size/mtime/类型，避免 File API 的多次 stat。 */
private fun readAttrs(f: File): BasicFileAttributes? =
    try {
        Files.readAttributes(
            f.toPath(),
            BasicFileAttributes::class.java,
            LinkOption.NOFOLLOW_LINKS
        )
    } catch (e: Exception) { null }

private fun mkItem(
    f: File, arc: String, attrs: BasicFileAttributes,
    isSymlink: Boolean
): Item {
    val linkTarget = if (isSymlink) {
        try { Files.readSymbolicLink(f.toPath()).toString() } catch (e: Exception) { "" }
    } else null

    return when {
        isSymlink -> Item(f, arc, false, true, linkTarget, 0,
                          MODE_SYMLINK, attrs.lastModifiedTime().toMillis() / 1000)
        attrs.isDirectory -> Item(f, arc, true, false, null, 0,
                                  MODE_DIR, attrs.lastModifiedTime().toMillis() / 1000)
        else -> Item(f, arc, false, false, null, attrs.size(),
                     MODE_FILE, attrs.lastModifiedTime().toMillis() / 1000)
    }
}

private fun walkDir(root: File, arcRoot: String, items: MutableList<Item>) {
    val children = root.listFiles() ?: return
    for (child in children) {
        val attrs = readAttrs(child) ?: continue
        val arc = "$arcRoot/${child.name}"
        val isLink = attrs.isSymbolicLink

        items.add(mkItem(child, arc, attrs, isLink))

        if (!isLink && attrs.isDirectory) {
            walkDir(child, arc, items)
        }
    }
}

fun scan(paths: List<String>, arcnames: List<String>): List<Item> {
    val items = mutableListOf<Item>()
    for ((src, arcbase) in paths.zip(arcnames)) {
        val f = File(src).absoluteFile
        val attrs = readAttrs(f)
            ?: throw FileNotFoundException(src)
        val isLink = attrs.isSymbolicLink

        items.add(mkItem(f, arcbase, attrs, isLink))

        if (!isLink && attrs.isDirectory) {
            walkDir(f, arcbase, items)
        }
    }
    return items
}

// ---------- 主流程 ----------
fun packFast(
    paths: List<String>, outputPath: String, arcnames: List<String>? = null,
    workersOverride: Int? = null, windowOverride: Int? = null
): Stats {
    val t0total = System.nanoTime()
    val arcs = arcnames ?: paths.map { basename(it) }
    if (paths.size != arcs.size) throw IllegalArgumentException("paths 与 arcnames 数量不一致")

    // ---- 扫描 ----
    val t0scan = System.nanoTime()
    System.err.println("扫描文件…")
    val items = scan(paths, arcs)
    val total = items.size
    val scanSec = (System.nanoTime() - t0scan) / 1e9

    // ---- 决定参数 ----
    val tuned: TunedParams
    if (workersOverride == null && windowOverride == null) {
        // 全自动
        tuned = autoTune(items)
        System.err.println("自动调参：线程 ${tuned.workers}  " +
                "阈值 ${tuned.threshold / 1024 / 1024}MB  " +
                "块 ${tuned.chunkSize / 1024 / 1024}MB  " +
                "窗口 ${tuned.window}")
        tuned.reason?.let { System.err.println("  $it") }
    } else {
        // 用户手动指定
        val w = workersOverride ?: DEFAULT_WORKERS
        tuned = TunedParams(
            workers = w,
            threshold = BIGFILE_THRESHOLD,
            chunkSize = CHUNK_SIZE,
            window = windowOverride ?: maxOf(w * 2, 8),
            reason = null
        )
    }

    val workers = tuned.workers
    val win = tuned.window
    val threshold = tuned.threshold
    val chunkSize = tuned.chunkSize

    val bigCount = items.count { !it.isDir && !it.isSymlink && it.size > threshold }
    val symCount = items.count { it.isSymlink }
    System.err.println("共 $total 项，扫描耗时 ${fmtTime(scanSec)}" +
        if (symCount > 0) "（含 $symCount 个符号链接）" else "")
    System.err.println("流式写盘：$workers 线程 / 窗口 $win" +
        if (bigCount > 0) "（其中 $bigCount 个大文件走流式）" else "")

    // ---- 流式读 + 写 ----
    val t0pack = System.nanoTime()
    var done = 0
    var totalBytes = 0L
    var paxCount = 0
    var written = 0L
    var lastReport = 0L

    val chunkBuf = ByteArray(chunkSize)

    val ex = Executors.newFixedThreadPool(workers)
    try {
        BufferedOutputStream(FileOutputStream(outputPath), 1 shl 20).use { out ->
            val itemsIt = items.iterator()
            val queue = ArrayDeque<Pair<Item, Future<ByteArray>?>>()

            // 队列中已提交任务的字节数上限：堆的 70%，64 MB ~ 320 MB
            val rt = Runtime.getRuntime()
            val availHeap = rt.maxMemory() - (rt.totalMemory() - rt.freeMemory())
            val pendingByteLimit = (availHeap * 70 / 100)
                .coerceIn(64L * 1024 * 1024, 320L * 1024 * 1024)
            var pendingBytes = 0L

            fun submit(item: Item): Future<ByteArray>? {
                if (item.isDir || item.isSymlink) return null
                if (item.size > threshold) return null
                if (pendingBytes + item.size > pendingByteLimit) return null
                pendingBytes += item.size
                return ex.submit(Callable { item.path.readBytes() })
            }

            fun report(force: Boolean) {
                val now = System.nanoTime() / 1_000_000
                if (force || now - lastReport >= PROGRESS_INTERVAL_MS) {
                    val dt = (System.nanoTime() - t0pack) / 1e9
                    val speed = if (dt > 0) done / dt else 0.0
                    System.err.print("\r进度 $done/$total  ${"%.0f".format(speed)} 项/秒")
                    lastReport = now
                }
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
                            out.write(pax); written += pax.size; paxCount++
                        }
                        val hdr = makeHeader(arc, 0, item.mode, item.mtime, isDir = true)
                        out.write(hdr); written += hdr.size
                    }

                    item.isSymlink -> {
                        val target = item.linkTarget ?: ""
                        val paxName = if (needsPaxName(arcname)) arcname else null
                        val paxLink = if (needsPaxLink(target)) target else null
                        if (paxName != null || paxLink != null) {
                            val pax = makePaxEntry(paxName, paxLink, item.mtime)
                            out.write(pax); written += pax.size; paxCount++
                        }
                        val hdr = makeHeader(arcname, 0, item.mode, item.mtime,
                                             isDir = false, linkname = target)
                        out.write(hdr); written += hdr.size
                    }

                    else -> {
                        // size 超 USTAR 上限时，用 PAX 记录真实 size
                        val needPaxSize = item.size > USTAR_SIZE_MAX
                        val needPaxPath = needsPaxName(arcname)
                        if (needPaxSize || needPaxPath) {
                            val pax = makePaxEntry(
                                if (needPaxPath) arcname else null,
                                null, item.mtime,
                                if (needPaxSize) item.size else null
                            )
                            out.write(pax); written += pax.size; paxCount++
                        }
                        val hdrSize = if (needPaxSize) 0L else item.size
                        val hdr = makeHeader(arcname, hdrSize, item.mode, item.mtime,
                                             isDir = false)
                        out.write(hdr); written += hdr.size

                        if (fut != null) {
                            val data = fut.get()
                            pendingBytes -= item.size
                            out.write(data); written += data.size
                            val pad = (512 - (data.size and 511)) and 511
                            if (pad > 0) { out.write(ZEROS_512, 0, pad); written += pad }
                        } else {
                            FileInputStream(item.path).use { s ->
                                while (true) {
                                    val n = s.read(chunkBuf)
                                    if (n < 0) break
                                    out.write(chunkBuf, 0, n)
                                    written += n
                                }
                            }
                            // 用 Long 计算 padding，避免超大文件 toInt() 溢出
                            val pad = ((512L - (item.size and 511L)) and 511L).toInt()
                            if (pad > 0) { out.write(ZEROS_512, 0, pad); written += pad }
                        }
                        totalBytes += item.size
                    }
                }

                done++
                report(done == total)

                if (itemsIt.hasNext()) {
                    val nxt = itemsIt.next()
                    queue.addLast(nxt to submit(nxt))
                }
            }

            out.write(TAR_END)
            written += 1024
        }
    } finally {
        ex.shutdown()
    }

    System.err.println()
    val packSec = (System.nanoTime() - t0pack) / 1e9

    return Stats(
        scanSec, packSec, (System.nanoTime() - t0total) / 1e9,
        total, totalBytes, written, workers, win, paxCount, bigCount, symCount,
        threshold, chunkSize, tuned.reason
    )
}

// ---------- main ----------
fun main(args: Array<String>) {
    if (args.isEmpty()) { printUsage(); exitProcess(1) }

    val paths = mutableListOf<String>()
    var outputPath: String? = null
    var workers: Int? = null    // null 表示自动
    var window: Int? = null     // null 表示自动

    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "-o", "--output" -> {
                i++
                val v = args.getOrNull(i)
                if (v == null) {
                    System.err.println("错误：${args[i-1]} 后面缺少参数")
                    exitProcess(2)
                }
                outputPath = v
            }
            "-j", "--workers" -> {
                i++
                val v = args.getOrNull(i)?.toIntOrNull()
                if (v == null || v < 1) {
                    System.err.println("错误：-j 需要一个正整数")
                    exitProcess(2)
                }
                workers = v
            }
            "-w", "--window" -> {
                i++
                val v = args.getOrNull(i)?.toIntOrNull()
                if (v == null || v < 1) {
                    System.err.println("错误：-w 需要一个正整数")
                    exitProcess(2)
                }
                window = v
            }
            "-h", "--help" -> { printUsage(); exitProcess(0) }
            else -> paths.add(args[i])
        }
        i++
    }

    if (paths.isEmpty()) { printUsage(); exitProcess(1) }

    // 输出路径推断
    if (outputPath == null) {
        if (paths.size > 1 && paths.last().endsWith(".tar", ignoreCase = true)) {
            outputPath = paths.removeAt(paths.size - 1)
        } else {
            outputPath = "./${basename(paths.first())}.tar"
        }
    }

    // 校验输出父目录存在
    val outParent = File(outputPath).absoluteFile.parentFile
    if (outParent != null && !outParent.exists()) {
        System.err.println("错误：输出目录不存在 - ${outParent.absolutePath}")
        exitProcess(2)
    }

    val stat = packFast(paths, outputPath,
                        workersOverride = workers,
                        windowOverride = window)

    println("=".repeat(44))
    println("打包完成")
    println("=".repeat(44))
    println("文件/目录项数 : ${stat.files}")
    println("线程数 / 窗口 : ${stat.workers} / ${stat.window}")
    println("阈值 / 块大小 : ${stat.threshold / 1024 / 1024} MB / ${stat.chunkSize / 1024 / 1024} MB")
    println("PAX 扩展头数  : ${stat.pax}")
    println("大文件数      : ${stat.bigFiles}")
    println("符号链接数    : ${stat.symlinks}")
    println("原始数据大小 : ${"%,d".format(stat.rawBytes)} 字节 (${"%.2f".format(stat.rawBytes / 1024.0 / 1024.0)} MB)")
    println("tar 大小     : ${"%,d".format(stat.written)} 字节 (${"%.2f".format(stat.written / 1024.0 / 1024.0)} MB)")
    println("-".repeat(44))
    println("扫描耗时     : ${fmtTime(stat.scanSec)}")
    println("读盘+打包耗时 : ${fmtTime(stat.packSec)}")
    println("总耗时       : ${fmtTime(stat.totalSec)}")
    println("-".repeat(44))
    if (stat.packSec > 0) {
        println("读盘吞吐     : ${"%.2f".format(stat.rawBytes / 1024.0 / 1024.0 / stat.packSec)} MB/s")
    }
    println("已写入：$outputPath")
}
