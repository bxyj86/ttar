// ttar.kt
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.nio.file.Files
import java.util.ArrayDeque
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.Future
import kotlin.system.exitProcess

const val DEFAULT_WORKERS = 8
const val DEFAULT_WINDOW = 16
const val BIGFILE_THRESHOLD = 8L shl 20   // 8 MB 以上走流式
const val CHUNK_SIZE = 1 shl 20            // 流式读块大小 1 MB

const val MODE_FILE = 420      // 0o644
const val MODE_DIR = 493       // 0o755
const val MODE_SYMLINK = 511   // 0o777

const val TF_REG = 0x30   // '0' 普通文件
const val TF_DIR = 0x35   // '5' 目录
const val TF_LNK = 0x32   // '2' 符号链接
const val TF_PAX = 0x78   // 'x' PAX 扩展头

data class Item(
    val path: File,
    val arcname: String,
    val isDir: Boolean,
    val isSymlink: Boolean,
    val linkTarget: String?,   // 仅 symlink 有值
    val size: Long,
    val mode: Int,
    val mtime: Long
)

data class Stats(
    val scanSec: Double, val packSec: Double, val totalSec: Double,
    val files: Int, val rawBytes: Long, val written: Long,
    val workers: Int, val window: Int, val pax: Int,
    val bigFiles: Int, val symlinks: Int
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

    // typeflag：显式传入优先，其次根据 linkname / isDir 推断
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

    // linkname 字段：offset 157，长度 100
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
 * 构造 PAX 扩展头。pathValue / linkValue 各自可为 null，
 * 只有非 null 的项才会写入。
 */
fun makePaxEntry(pathValue: String?, linkValue: String?, mtime: Long): ByteArray {
    var content = ByteArray(0)
    if (pathValue != null) content += paxLine("path", pathValue)
    if (linkValue != null) content += paxLine("linkpath", linkValue)
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

// ---------- 扫描 ----------
fun walkDir(root: File, arcRoot: String, items: MutableList<Item>) {
    val children = root.listFiles() ?: return
    for (child in children) {
        val arc = "$arcRoot/${child.name}"
        val nio = child.toPath()

        // 关键：先判 symlink，避免 isDirectory / isFile 跟随链接
        if (Files.isSymbolicLink(nio)) {
            val target = try {
                Files.readSymbolicLink(nio).toString()
            } catch (e: Exception) { "" }
            items.add(Item(child, arc, isDir = false, isSymlink = true,
                          linkTarget = target, size = 0,
                          mode = MODE_SYMLINK, mtime = child.lastModified() / 1000))
        } else if (child.isDirectory) {
            items.add(Item(child, arc, isDir = true, isSymlink = false,
                          linkTarget = null, size = 0,
                          mode = MODE_DIR, mtime = child.lastModified() / 1000))
            walkDir(child, arc, items)   // 只在真实目录里递归
        } else if (child.isFile) {
            items.add(Item(child, arc, isDir = false, isSymlink = false,
                          linkTarget = null, size = child.length(),
                          mode = MODE_FILE, mtime = child.lastModified() / 1000))
        }
    }
}

fun scan(paths: List<String>, arcnames: List<String>): List<Item> {
    val items = mutableListOf<Item>()
    for ((src, arcbase) in paths.zip(arcnames)) {
        val f = File(src).absoluteFile
        val nio = f.toPath()

        val isLink = Files.isSymbolicLink(nio)
        if (!f.exists() && !isLink) throw FileNotFoundException(src)

        when {
            isLink -> {
                val target = try {
                    Files.readSymbolicLink(nio).toString()
                } catch (e: Exception) { "" }
                items.add(Item(f, arcbase, isDir = false, isSymlink = true,
                              linkTarget = target, size = 0,
                              mode = MODE_SYMLINK, mtime = f.lastModified() / 1000))
            }
            f.isDirectory -> {
                items.add(Item(f, arcbase, isDir = true, isSymlink = false,
                              linkTarget = null, size = 0,
                              mode = MODE_DIR, mtime = f.lastModified() / 1000))
                walkDir(f, arcbase, items)
            }
            f.isFile -> {
                items.add(Item(f, arcbase, isDir = false, isSymlink = false,
                              linkTarget = null, size = f.length(),
                              mode = MODE_FILE, mtime = f.lastModified() / 1000))
            }
        }
    }
    return items
}

// ---------- 主流程 ----------
fun packFast(
    paths: List<String>, outputPath: String, arcnames: List<String>? = null,
    workers: Int = DEFAULT_WORKERS, window: Int = 0
): Stats {
    val t0total = System.nanoTime()
    val arcs = arcnames ?: paths.map { File(it).name }
    if (paths.size != arcs.size) throw IllegalArgumentException("paths 与 arcnames 数量不一致")
    val win = if (window > 0) window else maxOf(workers * 2, 8)

    // ---- 扫描 ----
    val t0scan = System.nanoTime()
    System.err.println("扫描文件…")
    val items = scan(paths, arcs)
    val total = items.size
    val scanSec = (System.nanoTime() - t0scan) / 1e9

    val bigCount = items.count { !it.isDir && !it.isSymlink && it.size > BIGFILE_THRESHOLD }
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

    val chunkBuf = ByteArray(CHUNK_SIZE)
    val zeros = ByteArray(512)

    val ex = Executors.newFixedThreadPool(workers)
    try {
        BufferedOutputStream(FileOutputStream(outputPath), 1 shl 20).use { out ->
            val itemsIt = items.iterator()
            // future == null 表示：目录 / symlink / 大文件（主线程自己处理）
            val queue = ArrayDeque<Pair<Item, Future<ByteArray>?>>()

            fun submit(item: Item): Future<ByteArray>? {
                if (item.isDir || item.isSymlink) return null
                if (item.size > BIGFILE_THRESHOLD) return null
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
                        // size = 0，linkname 放目标路径，typeflag = '2'
                        val hdr = makeHeader(arcname, 0, item.mode, item.mtime,
                                             isDir = false, linkname = target)
                        out.write(hdr); written += hdr.size
                    }

                    else -> {
                        if (needsPaxName(arcname)) {
                            val pax = makePaxEntry(arcname, null, item.mtime)
                            out.write(pax); written += pax.size; paxCount++
                        }
                        val hdr = makeHeader(arcname, item.size, item.mode, item.mtime,
                                             isDir = false)
                        out.write(hdr); written += hdr.size

                        if (fut != null) {
                            // 小文件：用预读好的字节
                            val data = fut.get()
                            out.write(data); written += data.size
                            val pad = (512 - (data.size % 512)) % 512
                            if (pad > 0) { out.write(zeros, 0, pad); written += pad }
                        } else {
                            // 大文件：主线程分块流式读 + 写
                            FileInputStream(item.path).use { s ->
                                while (true) {
                                    val n = s.read(chunkBuf)
                                    if (n < 0) break
                                    out.write(chunkBuf, 0, n)
                                    written += n
                                }
                            }
                            val pad = (512 - (item.size % 512).toInt()) % 512
                            if (pad > 0) { out.write(zeros, 0, pad); written += pad }
                        }
                        totalBytes += item.size
                    }
                }

                done++
                if (done % 500 == 0 || done == total) {
                    val dt = (System.nanoTime() - t0pack) / 1e9
                    val speed = if (dt > 0) done / dt else 0.0
                    System.err.print("\r进度 $done/$total  ${"%.0f".format(speed)} 项/秒")
                }

                if (itemsIt.hasNext()) {
                    val nxt = itemsIt.next()
                    queue.addLast(nxt to submit(nxt))
                }
            }

            out.write(ByteArray(1024))
            written += 1024
        }
    } finally {
        ex.shutdown()
    }

    System.err.println()
    val packSec = (System.nanoTime() - t0pack) / 1e9

    return Stats(
        scanSec, packSec, (System.nanoTime() - t0total) / 1e9,
        total, totalBytes, written, workers, win, paxCount, bigCount, symCount
    )
}

// ---------- main ----------
fun main(args: Array<String>) {
    if (args.isEmpty()) {
        System.err.println("用法: java -jar ttar.jar <paths...> -o <output.tar> [-j workers] [-w window]")
        exitProcess(1)
    }
    val paths = mutableListOf<String>()
    var outputPath: String? = null
    var workers = DEFAULT_WORKERS
    var window = 0

    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "-o", "--output" -> { i++; outputPath = args.getOrNull(i) }
            "-j", "--workers" -> { i++; workers = args.getOrNull(i)?.toIntOrNull() ?: DEFAULT_WORKERS }
            "-w", "--window" -> { i++; window = args.getOrNull(i)?.toIntOrNull() ?: 0 }
            else -> paths.add(args[i])
        }
        i++
    }

    if (paths.isEmpty() || outputPath == null) {
        System.err.println("必须指定输入路径和 -o 输出路径")
        exitProcess(1)
    }

    val stat = packFast(paths, outputPath, workers = workers, window = window)

    println("=".repeat(44))
    println("打包完成")
    println("=".repeat(44))
    println("文件/目录项数 : ${stat.files}")
    println("线程数 / 窗口 : ${stat.workers} / ${stat.window}")
    println("PAX 扩展头数  : ${stat.pax}")
    println("大文件数      : ${stat.bigFiles}（> 8 MB，流式处理）")
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