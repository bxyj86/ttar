import com.example.ttar.core.TarPacker

fun main(args: Array<String>) {
    val source = args[0]
    val output = if (args.size > 1) args[1] else "./${File(source).name}.tar"

    val stats = TarPacker().pack(source, output) { done, total ->
        System.err.print("\r进度 $done / $total")
    }
    System.err.println()

    println("完成 ✓")
    println("文件: ${stats.files}  目录: ${stats.dirs}  链接: ${stats.symlinks}")
    println("原始: ${"%.2f".format(stats.rawBytes / 1024.0 / 1024.0)} MB")
    println("tar:  ${"%.2f".format(stats.written / 1024.0 / 1024.0)} MB")
    println("耗时: ${"%.2f".format(stats.totalSec)} 秒")
}
