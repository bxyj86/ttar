package com.example.ttar

import android.content.Context
import java.io.File
import java.util.Properties

data class Options(
    val workers: Int = 16,
    val bigfileThreshold: Long = 16L shl 20,
    val chunkSize: Int = 16 shl 20,
    val windowFactor: Int = 8,
    val keepSymlinks: Boolean = true,
    val skipHidden: Boolean = false,
    val defaultSource: String = "/storage/emulated/0/",
    val defaultOutputName: String = "output.tar",
    val autoTune: Boolean = false
) {
    companion object {
        private const val FILE_NAME = "ttar.conf"

        const val WORKERS_MIN = 1
        const val WORKERS_MAX = 64
        const val BIGFILE_MB_MIN = 1
        const val BIGFILE_MB_MAX = 4096
        const val CHUNK_MB_MIN = 1
        const val CHUNK_MB_MAX = 256
        const val WINDOW_MIN = 1
        const val WINDOW_MAX = 32

        fun configFile(ctx: Context): File {
            val ext = ctx.getExternalFilesDir(null) ?: ctx.filesDir
            val base = ext.parentFile ?: ext
            if (!base.exists()) base.mkdirs()
            return File(base, FILE_NAME)
        }

        fun load(ctx: Context): Options {
            val f = configFile(ctx)
            if (!f.exists()) {
                val d = Options()
                try { save(ctx, d) } catch (_: Exception) {}
                return d
            }
            return try {
                val p = Properties()
                f.inputStream().use { p.load(it) }

                fun i(k: String, def: Int, lo: Int, hi: Int) =
                    p.getProperty(k)?.toIntOrNull()?.coerceIn(lo, hi) ?: def
                fun l(k: String, def: Long, lo: Long, hi: Long) =
                    p.getProperty(k)?.toLongOrNull()?.coerceIn(lo, hi) ?: def
                fun b(k: String, def: Boolean) =
                    p.getProperty(k)?.toBoolean() ?: def

                val chunkMb: Int = when {
                    p.getProperty("chunk_mb") != null ->
                        i("chunk_mb", 16, CHUNK_MB_MIN, CHUNK_MB_MAX)
                    p.getProperty("chunk_kb") != null -> {
                        val kb = p.getProperty("chunk_kb").toIntOrNull() ?: 16384
                        (kb / 1024).coerceIn(CHUNK_MB_MIN, CHUNK_MB_MAX)
                    }
                    else -> 16
                }

                Options(
                    workers = i("workers", 16, WORKERS_MIN, WORKERS_MAX),
                    bigfileThreshold =
                        l("bigfile_mb", 16, BIGFILE_MB_MIN.toLong(),
                          BIGFILE_MB_MAX.toLong()) * 1024 * 1024,
                    chunkSize = chunkMb * 1024 * 1024,
                    windowFactor = i("window_factor", 8, WINDOW_MIN, WINDOW_MAX),
                    keepSymlinks = b("keep_symlinks", true),
                    skipHidden = b("skip_hidden", false),
                    defaultSource = p.getProperty("default_source", "/storage/emulated/0/"),
                    defaultOutputName = p.getProperty("default_output", "output.tar"),
                    autoTune = b("auto_tune", false)
                )
            } catch (e: Exception) {
                Options()
            }
        }

        fun save(ctx: Context, o: Options) {
            val f = configFile(ctx)
            f.parentFile?.mkdirs()
            val p = Properties()
            p.setProperty("workers", o.workers.toString())
            p.setProperty("bigfile_mb", (o.bigfileThreshold / 1024 / 1024).toString())
            p.setProperty("chunk_mb", (o.chunkSize / 1024 / 1024).toString())
            p.setProperty("window_factor", o.windowFactor.toString())
            p.setProperty("keep_symlinks", o.keepSymlinks.toString())
            p.setProperty("skip_hidden", o.skipHidden.toString())
            p.setProperty("default_source", o.defaultSource)
            p.setProperty("default_output", o.defaultOutputName)
            p.setProperty("auto_tune", o.autoTune.toString())
            f.outputStream().use { out ->
                out.write("# ttar configuration\n".toByteArray())
                out.write("# Edit and restart app to apply.\n".toByteArray())
                p.store(out, null)
            }
        }
    }
}
