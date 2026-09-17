package com.example.ttar

/**
 * 自动调参。
 *
 * 原则：
 *   1. 阈值不能太低——越低越多中等文件走流式，主线程被拖慢
 *   2. 窗口不能太小——读线程会空转
 *   3. 优先保证不 OOM，其次才是榨性能
 */
object AutoTuner {

    data class Result(
        val options: Options,
        val reason: String
    )

    fun tune(
        items: List<Item>,
        baseOptions: Options,
        availHeap: Long? = null
    ): Result {
        val files = items.filter { !it.isDir && !it.isSymlink }
        if (files.isEmpty()) return Result(baseOptions, "无文件可调")

        val sizes = files.map { it.size }.sorted()
        val n = sizes.size
        val median = sizes[n / 2]
        val maxSize = sizes[n - 1]

        val bigCount = files.count { it.size > 8L shl 20 }       // >8 MB
        val bigRatio = bigCount.toDouble() / n

        val heap = availHeap ?: run {
            val rt = Runtime.getRuntime()
            rt.maxMemory() - (rt.totalMemory() - rt.freeMemory())
        }
        val heapMb = heap / 1024 / 1024

        // ─── 1. 大文件阈值 ───
        // 不用中位数，用堆大小 + 大文件占比
        val bigfileMb = when {
            heapMb < 200 -> 4       // 堆紧张，保守
            heapMb < 400 -> 8
            heapMb < 800 -> 16
            else -> 16
        }.let {
            // 大文件占比高时，适当提高阈值让更多中等文件走并行
            if (bigRatio > 0.3 && it < 32) it * 2 else it
        }
        val threshold = bigfileMb.toLong() * 1024 * 1024

        // ─── 2. 流式块大小 ───
        val chunkMb = when {
            maxSize < 64L shl 20  -> 4
            maxSize < 512L shl 20 -> 16
            else -> 32
        }

        // ─── 3. 线程数 ───
        val workers = when {
            heapMb < 200 -> 8
            bigRatio > 0.5 -> 8     // 大文件为主，主循环串行，线程多了没用
            else -> 16
        }

        // ─── 4. 窗口倍数 ───
        // 512 MB 上限下：可用堆约 480 MB
        // 留 60% 给窗口预读，上限 256 MB
        val budget = minOf(heap * 60 / 100, 256L * 1024 * 1024)
        // 按阈值估最坏情况：窗口里每个文件都接近阈值
        val perItem = threshold
        val maxItems = (budget / perItem).toInt()
        val windowFactor = (maxItems / workers).coerceIn(4, 16)

        val tuned = baseOptions.copy(
            workers = workers,
            bigfileThreshold = threshold,
            chunkSize = chunkMb * 1024 * 1024,
            windowFactor = windowFactor
        )

        val reason = buildString {
            append("文件 $n  ")
            append("中位 ${fmtSize(median)}  ")
            append("最大 ${fmtSize(maxSize)}  ")
            append("大文件 $bigCount (${"%.1f".format(bigRatio * 100)}%)  ")
            append("可用堆 ${heapMb} MB  预算 ${budget / 1024 / 1024} MB")
        }

        return Result(tuned, reason)
    }

    private fun fmtSize(b: Long): String = when {
        b < 1024L -> "$b B"
        b < 1024L * 1024L -> "%.1f KB".format(b / 1024.0)
        b < 1024L * 1024L * 1024L -> "%.1f MB".format(b / 1024.0 / 1024.0)
        else -> "%.2f GB".format(b / 1024.0 / 1024.0 / 1024.0)
    }
}
