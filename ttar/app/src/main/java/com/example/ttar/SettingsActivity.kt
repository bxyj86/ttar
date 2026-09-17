package com.example.ttar

import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {

    private var workers = 16
    private var bigfileMb = 16
    private var chunkMb = 16
    private var windowFactor = 8
    private var keepSymlinks = true
    private var skipHidden = false
    private var autoTune = false
    private var defaultSource = ""
    private var defaultOutput = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val ctx = this
        val dark = isDark()
        val line = if (dark) Color.DKGRAY else Color.LTGRAY
        val bg = if (dark) Color.BLACK else Color.WHITE

        val cur = Options.load(ctx)
        workers = cur.workers
        bigfileMb = (cur.bigfileThreshold / 1024 / 1024).toInt()
        chunkMb = cur.chunkSize / 1024 / 1024
        windowFactor = cur.windowFactor
        keepSymlinks = cur.keepSymlinks
        skipHidden = cur.skipHidden
        autoTune = cur.autoTune
        defaultSource = cur.defaultSource
        defaultOutput = cur.defaultOutputName

        fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
        fun divider(): View = View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(1))
            setBackgroundColor(line)
        }

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
        }

        // 顶部栏
        val topBar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(16))
        }
        topBar.addView(TextView(ctx).apply {
            text = "返回"
            textSize = 16f
            setPadding(0, dp(8), dp(12), dp(8))
            setOnClickListener { finish() }
        })
        topBar.addView(TextView(ctx).apply {
            text = "设置"
            textSize = 20f
            setTypeface(null, Typeface.BOLD)
            setPadding(dp(8), 0, 0, 0)
        })
        root.addView(topBar)
        root.addView(divider())

        // 数值行
        fun numRow(label: String, getter: () -> Int, setter: (Int) -> Unit,
                   min: Int, max: Int) {
            val valueView = TextView(ctx).apply {
                text = getter().toString()
                textSize = 15f
            }
            root.addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(20), dp(16), dp(20), dp(16))
                isClickable = true
                setOnClickListener {
                    promptNumber(label, getter(), min, max) { v ->
                        setter(v)
                        valueView.text = v.toString()
                    }
                }
                addView(TextView(ctx).apply {
                    text = label
                    textSize = 15f
                    layoutParams = LinearLayout.LayoutParams(0,
                        LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })
                addView(valueView)
            })
            root.addView(divider())
        }

        numRow("线程数", { workers }, { workers = it },
               Options.WORKERS_MIN, Options.WORKERS_MAX)
        numRow("大文件阈值 (MB)", { bigfileMb }, { bigfileMb = it },
               Options.BIGFILE_MB_MIN, Options.BIGFILE_MB_MAX)
        numRow("流式块大小 (MB)", { chunkMb }, { chunkMb = it },
               Options.CHUNK_MB_MIN, Options.CHUNK_MB_MAX)
        numRow("窗口倍数", { windowFactor }, { windowFactor = it },
               Options.WINDOW_MIN, Options.WINDOW_MAX)

        // 开关行
        fun switchRow(label: String, initial: Boolean, onChange: (Boolean) -> Unit) {
            root.addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(20), dp(12), dp(20), dp(12))
                addView(TextView(ctx).apply {
                    text = label
                    textSize = 15f
                    layoutParams = LinearLayout.LayoutParams(0,
                        LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })
                addView(Switch(ctx).apply {
                    isChecked = initial
                    setOnCheckedChangeListener { _, v -> onChange(v) }
                })
            })
            root.addView(divider())
        }

        switchRow("自动调参（忽略上方参数）", autoTune) { autoTune = it }
        switchRow("保留符号链接", keepSymlinks) { keepSymlinks = it }
        switchRow("跳过隐藏文件", skipHidden) { skipHidden = it }

        // 文本行
        fun textRow(label: String, value: String, onChange: (String) -> Unit) {
            root.addView(TextView(ctx).apply {
                text = label
                textSize = 13f
                setPadding(dp(20), dp(16), dp(20), dp(4))
            })
            root.addView(EditText(ctx).apply {
                setText(value)
                textSize = 15f
                inputType = InputType.TYPE_CLASS_TEXT
                setBackgroundColor(Color.TRANSPARENT)
                setPadding(dp(20), dp(8), dp(20), dp(8))
                addTextChangedListener(object : android.text.TextWatcher {
                    override fun afterTextChanged(s: android.text.Editable?) {
                        onChange(s?.toString() ?: "")
                    }
                    override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                    override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                })
            })
            root.addView(divider())
        }

        textRow("默认源目录", defaultSource) { defaultSource = it }
        textRow("默认输出文件名", defaultOutput) { defaultOutput = it }

        // 底部按钮
        root.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(20), dp(24), dp(20), dp(24))

            addView(TextView(ctx).apply {
                text = "保存"
                textSize = 16f
                setTypeface(null, Typeface.BOLD)
                setPadding(dp(16), dp(12), dp(24), dp(12))
                layoutParams = LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener {
                    try {
                        val o = Options(
                            workers = workers,
                            bigfileThreshold = bigfileMb.toLong() * 1024 * 1024,
                            chunkSize = chunkMb * 1024 * 1024,
                            windowFactor = windowFactor,
                            keepSymlinks = keepSymlinks,
                            autoTune = autoTune,
                            skipHidden = skipHidden,
                            defaultSource = defaultSource.trim(),
                            defaultOutputName = defaultOutput.trim().ifEmpty { "output.tar" }
                        )
                        Options.save(ctx, o)
                        Toast.makeText(ctx, "已保存", Toast.LENGTH_SHORT).show()
                        finish()
                    } catch (e: Exception) {
                        Toast.makeText(ctx, "无效：${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            })

            addView(TextView(ctx).apply {
                text = "恢复默认"
                textSize = 16f
                setPadding(dp(24), dp(12), dp(16), dp(12))
                setOnClickListener {
                    val d = Options()
                    workers = d.workers
                    bigfileMb = (d.bigfileThreshold / 1024 / 1024).toInt()
                    chunkMb = d.chunkSize / 1024 / 1024
                    windowFactor = d.windowFactor
                    keepSymlinks = d.keepSymlinks
                    autoTune = d.autoTune
                    skipHidden = d.skipHidden
                    defaultSource = d.defaultSource
                    defaultOutput = d.defaultOutputName
                    Toast.makeText(ctx, "已恢复，点保存生效", Toast.LENGTH_SHORT).show()
                    recreate()
                }
            })
        })

        // 内存估算提示
        root.addView(TextView(ctx).apply {
            val peakMb = workers.toLong() * windowFactor *
                    bigfileMb.coerceAtMost(16)
            text = "内存峰值估算  ≈ $peakMb MB"
            textSize = 12f
            setPadding(dp(20), 0, dp(20), dp(8))
        })

        // ─── 关于 ───
        root.addView(divider())
        root.addView(TextView(ctx).apply {
            text = "关于"
            textSize = 13f
            setPadding(dp(20), dp(20), dp(20), dp(8))
        })

        val rt = Runtime.getRuntime()
        val heapMax = rt.maxMemory()
        val heapUsed = rt.totalMemory() - rt.freeMemory()
        val heapAvail = heapMax - heapUsed
        val budget = minOf(heapAvail * 60 / 100, 256L * 1024 * 1024)

        fun infoRow(label: String, value: String) {
            root.addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(dp(20), dp(5), dp(20), dp(5))
                addView(TextView(ctx).apply {
                    text = label
                    textSize = 13f
                    layoutParams = LinearLayout.LayoutParams(0,
                        LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })
                addView(TextView(ctx).apply {
                    text = value
                    textSize = 13f
                })
            })
        }

        fun fmtMb(b: Long): String = "%.0f MB".format(b / 1024.0 / 1024.0)

        val pkgInfo = try {
            ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        } catch (e: Exception) { null }
        val versionName = pkgInfo?.versionName ?: "?"
        val versionCode = pkgInfo?.let {
            if (android.os.Build.VERSION.SDK_INT >= 28)
                it.longVersionCode
            else
                @Suppress("DEPRECATION") it.versionCode.toLong()
        } ?: 0L

        val hardCap = 256L * 1024 * 1024
        val capped = budget >= hardCap
        val budgetLabel = if (capped)
            "${fmtMb(budget)}  (已达上限)"
        else
            "${fmtMb(budget)}  (可用堆 60%)"

        infoRow("版本", "$versionName ($versionCode)")
        infoRow("堆上限", fmtMb(heapMax))
        infoRow("当前已用", fmtMb(heapUsed))
        infoRow("可用", fmtMb(heapAvail))
        infoRow("窗口预算", budgetLabel)

        root.addView(TextView(ctx).apply {
            text = "配置文件：\n${Options.configFile(ctx).absolutePath}"
            textSize = 12f
            setPadding(dp(20), dp(12), dp(20), dp(24))
        })

        setContentView(ScrollView(ctx).apply { addView(root) })
    }

    private fun isDark(): Boolean {
        val mode = resources.configuration.uiMode and
                android.content.res.Configuration.UI_MODE_NIGHT_MASK
        return mode == android.content.res.Configuration.UI_MODE_NIGHT_YES
    }

    private fun promptNumber(label: String, cur: Int, min: Int, max: Int, onOk: (Int) -> Unit) {
        val d = resources.displayMetrics.density.toInt()
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(cur.toString())
            setPadding(d * 20, d * 12, d * 20, d * 12)
        }
        AlertDialog.Builder(this)
            .setTitle("$label（$min ~ $max）")
            .setView(input)
            .setPositiveButton("确定") { _, _ ->
                val v = input.text.toString().toIntOrNull() ?: return@setPositiveButton
                onOk(v.coerceIn(min, max))
            }
            .setNegativeButton("取消", null)
            .show()
    }
}
