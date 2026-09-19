package com.example.ttar

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.SystemClock
import android.provider.DocumentsContract
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.*
import java.io.File

class MainActivity : BaseActivity() {

    private lateinit var sourceInput: EditText
    private lateinit var outputInput: EditText
    private lateinit var statusView: TextView
    private lateinit var permLine: TextView
    private lateinit var btnScan: TextView
    private lateinit var btnPack: TextView

    private var lastScannedSource: String? = null
    private var cachedItems: List<Item>? = null

    @Volatile private var phase = "idle"
    @Volatile private var scanCount = 0
    @Volatile private var doneCount = 0
    @Volatile private var totalCount = 0
    @Volatile private var finalLine = ""
    @Volatile private var t0 = 0L
    private var timerJob: Job? = null
    private var busy = false

    private val pickSource = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri?.let {
            uriToRealPath(it)?.let { p ->
                sourceInput.setText(p)
                invalidateCache()
                setStatus("已选源：$p")
            } ?: setStatus("无法解析路径，请手动输入")
        }
    }

    private val pickOutput = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/x-tar")
    ) { uri ->
        uri?.let {
            uriToRealPath(it)?.let { p -> outputInput.setText(p); setStatus("已选输出：$p") }
                ?: setStatus("无法解析路径，请手动输入")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val ctx = this
        val opts = Options.load(ctx)

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(if (isDark()) Color.BLACK else Color.WHITE)
        }

        val topBar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(16))
        }
        topBar.addView(TextView(ctx).apply {
            text = "ttar"; textSize = 20f; setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        topBar.addView(TextView(ctx).apply {
            text = "设置"; textSize = 16f
            setPadding(dp(12), dp(8), 0, dp(8))
            setOnClickListener {
                startActivity(Intent(ctx, SettingsActivity::class.java))
            }
        })
        root.addView(topBar)
        root.addView(divider())

        permLine = TextView(ctx).apply {
            textSize = 14f; setPadding(dp(20), dp(16), dp(20), dp(8))
        }
        root.addView(permLine)
        root.addView(TextView(ctx).apply {
            text = "授予所有文件访问权限"; textSize = 14f
            setPadding(dp(20), dp(4), dp(20), dp(16))
            setOnClickListener { requestAllFilesAccess() }
        })
        root.addView(divider())

        root.addView(sectionLabel("源目录"))
        sourceInput = pathInput(opts.defaultSource)
        sourceInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) {
                if (s?.toString()?.trim() != lastScannedSource) invalidateCache()
            }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        root.addView(inputRow(sourceInput, "选择") { pickSource.launch(null) })

        root.addView(sectionLabel("输出 tar"))
        outputInput = pathInput("/storage/emulated/0/${opts.defaultOutputName}")
        root.addView(inputRow(outputInput, "选择") {
            pickOutput.launch(opts.defaultOutputName)
        })

        val btnRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
        }
        btnScan = TextView(ctx).apply {
            text = "开始扫描"; textSize = 17f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(14), dp(12), dp(14))
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            ).apply { marginEnd = dp(10) }
            setOnClickListener { startScan() }
        }
        btnPack = TextView(ctx).apply {
            text = "开始打包"; textSize = 17f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(14), dp(12), dp(14))
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            ).apply { marginStart = dp(10) }
            setOnClickListener { startPack() }
        }
        btnRow.addView(btnScan)
        btnRow.addView(btnPack)
        root.addView(btnRow)

        root.addView(divider())

        statusView = TextView(ctx).apply {
            textSize = 14f
            setPadding(dp(20), dp(16), dp(20), dp(32))
            setTextIsSelectable(true)
        }
        root.addView(statusView)

        setContentView(ScrollView(ctx).apply { addView(root) })
        updatePermissionStatus()
        setStatus("就绪")
    }

    override fun onPause() {
        super.onPause()
        timerJob?.cancel(); timerJob = null
    }

    override fun onResume() {
        super.onResume()
        updatePermissionStatus()
        if (busy) startTimerLoop()
    }

    private fun isDark() = (resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun divider(): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(1))
        setBackgroundColor(if (isDark()) Color.DKGRAY else Color.LTGRAY)
    }

    private fun sectionLabel(t: String): TextView = TextView(this).apply {
        text = t; textSize = 13f
        setPadding(dp(20), dp(20), dp(20), dp(6))
    }

    private fun pathInput(v: String): EditText = EditText(this).apply {
        setText(v); textSize = 15f
        inputType = InputType.TYPE_CLASS_TEXT
        setBackgroundColor(Color.TRANSPARENT)
        setPadding(dp(20), dp(8), dp(20), dp(8))
        layoutParams = LinearLayout.LayoutParams(0,
            LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
    }

    private fun inputRow(edit: EditText, btnText: String, onClick: () -> Unit): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(edit)
        row.addView(TextView(this).apply {
            text = btnText; textSize = 15f
            setPadding(dp(12), dp(12), dp(20), dp(12))
            setOnClickListener { onClick() }
        })
        return row
    }

    private fun setStatus(s: String) { statusView.text = s }

    private fun invalidateCache() {
        lastScannedSource = null
        cachedItems = null
    }

    private fun setButtonsEnabled(enabled: Boolean) {
        btnScan.isEnabled = enabled
        btnPack.isEnabled = enabled
        val alpha = if (enabled) 1f else 0.4f
        btnScan.alpha = alpha
        btnPack.alpha = alpha
    }

    private fun hasAllFilesAccess() =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            Environment.isExternalStorageManager() else true

    private fun updatePermissionStatus() {
        permLine.text = if (hasAllFilesAccess()) "权限：已授予" else "权限：未授予"
    }

    private fun requestAllFilesAccess() {
        if (hasAllFilesAccess()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val i = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
            i.data = Uri.parse("package:$packageName")
            try { startActivity(i) } catch (e: Exception) {
                startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        }
    }

    private fun uriToRealPath(uri: Uri): String? {
        if (uri.scheme == "file") return uri.path
        if (uri.scheme == "content") {
            try {
                if (DocumentsContract.isTreeUri(uri))
                    return docIdToPath(DocumentsContract.getTreeDocumentId(uri))
                if (DocumentsContract.isDocumentUri(this, uri))
                    return docIdToPath(DocumentsContract.getDocumentId(uri))
            } catch (e: Exception) { return null }
        }
        return null
    }

    private fun docIdToPath(docId: String): String? {
        if (docId.startsWith("primary:")) {
            val rel = docId.substring("primary:".length)
            return File(Environment.getExternalStorageDirectory(), rel).absolutePath
        }
        val idx = docId.indexOf(':')
        if (idx > 0) return "/storage/${docId.substring(0, idx)}/${docId.substring(idx + 1)}"
        return null
    }

    private fun fmtMb(bytes: Long) = "%.0f MB".format(bytes / 1024.0 / 1024.0)

    private fun fmtSize(bytes: Long): String =
        if (bytes < 1024L * 1024L * 1024L)
            "%.2f MB".format(bytes / 1024.0 / 1024.0)
        else
            "%.2f GB".format(bytes / 1024.0 / 1024.0 / 1024.0)

    private fun startTimerLoop() {
        timerJob?.cancel()
        timerJob = lifecycleScope.launch(Dispatchers.Main) {
            while (isActive) {
                val elapsed = (SystemClock.elapsedRealtime() - t0) / 1000.0
                val text = when (phase) {
                    "scan" -> "扫描中  $scanCount 项    ${"%.1f".format(elapsed)}s"
                    "pack" -> {
                        val pct = if (totalCount > 0) doneCount * 100 / totalCount else 0
                        "打包中  $doneCount / $totalCount  ($pct%)    ${"%.1f".format(elapsed)}s"
                    }
                    "done", "fail" -> finalLine
                    else -> "准备中…    ${"%.1f".format(elapsed)}s"
                }
                statusView.text = text
                delay(100)
            }
        }
    }

    private fun startScan() {
        if (busy) return
        if (!hasAllFilesAccess()) { setStatus("请先授予所有文件访问权限"); return }
        val src = sourceInput.text.toString().trim()
        if (src.isEmpty()) { setStatus("请填写源目录"); return }
        if (!File(src).exists()) { setStatus("源不存在：$src"); return }

        val opts = Options.load(this)
        busy = true
        setButtonsEnabled(false)
        phase = "scan"; scanCount = 0; finalLine = ""
        t0 = SystemClock.elapsedRealtime()
        startTimerLoop()

        lifecycleScope.launch {
            try {
                val items = withContext(Dispatchers.IO) {
                    TarPacker().scanOnly(src, opts) { n ->
                        phase = "scan"; scanCount = n
                    }
                }
                cachedItems = items
                lastScannedSource = src
                val wall = (SystemClock.elapsedRealtime() - t0) / 1000.0
                finalLine = "扫描完成  ${items.size} 项    ${"%.2f".format(wall)}s\n$src"
                phase = "done"
                timerJob?.cancel()
                statusView.text = finalLine
            } catch (e: Exception) {
                finalLine = "扫描失败：${e.message}"
                phase = "fail"
                timerJob?.cancel()
                statusView.text = finalLine
            } finally {
                busy = false
                setButtonsEnabled(true)
            }
        }
    }

    private fun startPack() {
        if (busy) return
        if (!hasAllFilesAccess()) { setStatus("请先授予所有文件访问权限"); return }
        val src = sourceInput.text.toString().trim()
        val out = outputInput.text.toString().trim()
        if (src.isEmpty() || out.isEmpty()) { setStatus("请填写源目录和输出路径"); return }
        if (!File(src).exists()) { setStatus("源不存在：$src"); return }

        val opts = Options.load(this)
        val preScanned = if (src == lastScannedSource) cachedItems else null

        busy = true
        setButtonsEnabled(false)
        phase = if (preScanned != null) "pack" else "scan"
        scanCount = 0; doneCount = 0; totalCount = 0; finalLine = ""
        t0 = SystemClock.elapsedRealtime()
        startTimerLoop()

        lifecycleScope.launch {
            try {
                val stats = withContext(Dispatchers.IO) {
                    TarPacker().pack(
                        src, out, opts,
                        preScannedItems = preScanned,
                        onScanProgress = { n -> phase = "scan"; scanCount = n },
                        onProgress = { d, t ->
                            phase = "pack"; doneCount = d; totalCount = t
                        }
                    )
                }
                val wall = (SystemClock.elapsedRealtime() - t0) / 1000.0
                val opt = stats.usedOptions
                val paramLine = "线程 ${opt.workers}  阈值 ${opt.bigfileThreshold / 1024 / 1024}MB  " +
                        "块 ${opt.chunkSize / 1024 / 1024}MB  窗口 ${opt.windowFactor}"
                val tuneLine = stats.tuneReason?.let { "\n自动调参：$it" } ?: ""
                val memLine = "内存：峰值 ${fmtMb(stats.heapPeak)} / 上限 ${fmtMb(stats.heapMax)}"
                finalLine = "完成  ${stats.files} 项  " +
                        "${fmtSize(stats.rawBytes)} → ${fmtSize(stats.written)}  " +
                        "${"%.2f".format(wall)}s\n$paramLine\n$memLine$tuneLine\n$out"
                phase = "done"
                timerJob?.cancel()
                statusView.text = finalLine
            } catch (e: Exception) {
                finalLine = "失败：${e.message}"
                phase = "fail"
                timerJob?.cancel()
                statusView.text = finalLine
            } finally {
                busy = false
                setButtonsEnabled(true)
            }
        }
    }
}
