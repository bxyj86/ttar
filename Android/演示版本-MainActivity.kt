// MainActivity.kt 里
lifecycleScope.launch {
    val stats = withContext(Dispatchers.IO) {
        com.example.ttar.core.TarPacker().pack(srcPath, outPath) { done, total ->
            runOnUiThread {
                statusView.text = "打包中 $done / $total"
            }
        }
    }
    statusView.text = "完成 ✓ 文件 ${stats.files}，耗时 ${stats.totalSec} 秒"
}
