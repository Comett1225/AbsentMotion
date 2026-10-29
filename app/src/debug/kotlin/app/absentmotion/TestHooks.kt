package app.absentmotion

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.lifecycleScope
import app.absentmotion.ai.AiMedia
import app.absentmotion.ai.BackgroundRemoval
import app.absentmotion.export.ExportKind
import app.absentmotion.export.ExportSettings
import app.absentmotion.export.Exporter
import app.absentmotion.export.FolderExport
import app.absentmotion.export.FrameInterpolation
import app.absentmotion.export.defaultSettings
import app.absentmotion.export.fixedFormat
import app.absentmotion.library.CrashLogs
import app.absentmotion.media.DebugFrameInterpolation
import app.absentmotion.model.Scene
import app.absentmotion.model.fontNames
import app.absentmotion.ui.editor.addPicked
import app.absentmotion.ui.home.Updates
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

internal object TestHooks {
    fun onboarding(intent: Intent): String? = intent.getStringExtra("onboarding")

    private fun keepShared(activity: MainActivity, intent: Intent) {
        val clip = intent.clipData ?: return
        val dir = File(activity.getExternalFilesDir(null), "shared").apply { mkdirs() }
        for (i in 0 until clip.itemCount) {
            val uri = clip.getItemAt(i).uri ?: continue
            val name = runCatching {
                activity.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
            }.getOrNull() ?: "shared-$i"
            runCatching { activity.contentResolver.openInputStream(uri)?.use { input -> File(dir, File(name).name).outputStream().use { input.copyTo(it) } } }
                .onSuccess { Log.i("AMTest", "kept shared $name") }
                .onFailure { Log.e("AMTest", "cannot keep $uri", it) }
        }
    }

    fun newIntent(activity: MainActivity, intent: Intent) {
        keepShared(activity, intent)
        if (intent.hasExtra("xml") || intent.hasExtra("aaccheck") || intent.hasExtra("devicecheck") || intent.hasExtra("capcheck") || intent.hasExtra("aicheck") || intent.hasExtra("enhancecheck") || intent.hasExtra("aismoothcheck") || intent.hasExtra("aismoothtrim") || intent.hasExtra("aidownload") || intent.hasExtra("hqinstall") || intent.hasExtra("hqremove")) {
            activity.setIntent(intent)
            start(activity)
            return
        }
        val state = activity.editor ?: return
        val frame = intent.getIntExtra("frame", state.frame).coerceIn(0, state.scene.lastFrame)
        if (intent.getBooleanExtra("losegpu", false)) {
            activity.window.decorView.postDelayed({ activity.engine.simulateGpuLoss = true; state.frame = frame }, 2000)
        } else if (intent.hasExtra("frame")) state.frame = frame
    }

    private fun holdDecoder(activity: MainActivity, path: String, after: Long, ms: Long) {
        activity.lifecycleScope.launch(Dispatchers.IO) {
            val extractor = android.media.MediaExtractor()
            var codec: android.media.MediaCodec? = null
            try {
                kotlinx.coroutines.delay(after)
                extractor.setDataSource(path)
                val format = (0 until extractor.trackCount).map { extractor.getTrackFormat(it) }.first { it.getString(android.media.MediaFormat.KEY_MIME)!!.startsWith("video/") }
                codec = android.media.MediaCodec.createDecoderByType(format.getString(android.media.MediaFormat.KEY_MIME)!!)
                codec.configure(format, null, null, 0)
                codec.start()
                Log.i("AMTest", "holding a real-time ${codec.name} for $ms ms")
                kotlinx.coroutines.delay(ms)
            } catch (e: Exception) {
                Log.e("AMTest", "cannot hold a decoder", e)
            } finally {
                codec?.let { runCatching { it.stop() }; it.release() }
                extractor.release()
                Log.i("AMTest", "decoder released")
            }
        }
    }

    private suspend fun checkClipSmooth(activity: MainActivity, state: app.absentmotion.editor.EditorState, method: String, level: String?, blur: String?) {
        try {
            val layer = state.scene.layers.first { it.mediaFill?.video == true }
            val media = checkNotNull(state.scene.media(layer.mediaFill!!.uri))
            val smoothing = app.absentmotion.export.Smoothing(FrameInterpolation.entries.first { it.name.equals(method, true) },
                app.absentmotion.export.SmoothLevel.entries.firstOrNull { it.name.equals(level, true) } ?: app.absentmotion.export.SmoothLevel.Medium,
                app.absentmotion.export.MotionBlur.entries.firstOrNull { it.name.equals(blur, true) } ?: app.absentmotion.export.MotionBlur.Off)
            val started = SystemClock.elapsedRealtime()
            val out = app.absentmotion.ai.ClipSmooth.run(activity.applicationContext, activity.engine, layer, media, state.scene.fps, smoothing) { _, _ -> }
            val copy = File(activity.getExternalFilesDir(null), "clipsmooth.mp4")
            val ms = SystemClock.elapsedRealtime() - started
            out.file.copyTo(copy, overwrite = true)
            File(activity.getExternalFilesDir(null), "clipsmooth.txt").writeText("$ms")
            Log.i("AMTest", "clipsmooth ${copy.path} ${copy.length()} bytes in $ms ms")
        } catch (e: Throwable) {
            Log.e("AMTest", "clipsmooth failed", e)
        }
    }

    private suspend fun downloadFonts(activity: MainActivity, scene: Scene) {
        activity.engine.fonts?.let { library -> scene.fontNames().forEach { library.resolve(it, download = true) } }
    }

    private fun crash(activity: MainActivity, kind: String) {
        Log.i("AMTest", "crash test: $kind")
        when (kind) {
            "java" -> throw IllegalStateException(kind)
            "memory" -> activity.lifecycleScope.launch { CrashLogs.during("exporting video") { throw OutOfMemoryError(kind) } }
            "native" -> android.system.Os.kill(android.system.Os.getpid(), android.system.OsConstants.SIGABRT)
            "anr" -> Thread.sleep(60_000)
        }
    }

    fun start(activity: MainActivity) {
        val intent = activity.intent
        keepShared(activity, intent)
        intent.getStringExtra("failengine")?.let { app.absentmotion.engine.EngineDebug.failStart = it }
        app.absentmotion.engine.EngineDebug.forceGl = intent.getBooleanExtra("gl", false)
        app.absentmotion.export.ExportDebug.refuseHardware = intent.getBooleanExtra("refusehw", false)
        app.absentmotion.export.ExportDebug.failPlayers = intent.getIntExtra("failplayers", 0)
        intent.getStringExtra("holddecoder")?.let { holdDecoder(activity, it, intent.getLongExtra("holdafter", 0L), intent.getLongExtra("holdms", 20_000L)) }
        val engine = activity.engine
        val scope = activity.lifecycleScope
        DebugFrameInterpolation.divisor = intent.getIntExtra("interpDivisor", 4)
        DebugFrameInterpolation.levels = intent.getIntExtra("interpLevels", 5)
        DebugFrameInterpolation.iterations = intent.getIntExtra("interpIterations", 6)
        DebugFrameInterpolation.warps = intent.getIntExtra("interpWarps", 1)
        DebugFrameInterpolation.alpha = intent.getFloatExtra("interpAlpha", 0.15f)
        DebugFrameInterpolation.fuse = intent.getIntExtra("interpFuse", 4)
        intent.getStringExtra("fakeversion")?.let { Updates.installedOverride = it }
        intent.getStringExtra("updatefixture")?.let { Updates.source = UpdateFixture(it) }
        if (intent.getBooleanExtra("updatecheck", false)) Updates.check(activity, activity.settings, manual = true)
        intent.getFloatExtra("tutorialfont", 0f).takeIf { it > 0f }?.let { app.absentmotion.ui.tutorial.Tutorial.fontScale = it }
        intent.getStringExtra("crash")?.let { kind -> activity.window.decorView.postDelayed({ crash(activity, kind) }, 2000) }
        if (intent.getBooleanExtra("audiolog", false)) engine.audio?.log = true
        if (intent.hasExtra("previewCopies")) activity.settings.previewCopies = intent.getBooleanExtra("previewCopies", true)
        if (intent.getBooleanExtra("effectcheck", false)) scope.launch {
            engine.effectCheck(activity.getExternalFilesDir(null)!!)
            Log.i("AMEffects", "effectcheck.json written")
        }
        if (intent.hasExtra("aaccheck")) {
            val duration = intent.getLongExtra("aaccheck", 32000L).coerceIn(1L, 10_000_000L)
            scope.launch {
                try {
                    withContext(Dispatchers.IO) { app.absentmotion.export.exportAacForTest(File(activity.getExternalFilesDir(null), "aaccheck.m4a"), duration) }
                    Log.i("AMAac", "done")
                } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (e: Exception) { Log.e("AMAac", "failed", e) }
            }
        }
        if (intent.getBooleanExtra("capcheck", false)) scope.launch {
            try {
                val caps = app.absentmotion.export.DeviceCapabilities.of(activity)
                val report = caps.runCheckNow(engine)
                report.toJson().toString().chunked(3000).forEach { Log.i("AMCaps", it) }
                for (codec in app.absentmotion.export.Codec.entries) for (side in listOf(1080, 2160)) {
                    Log.i("AMCaps", "check ${codec.name} ${side}p30: ${report.check(ExportSettings(side, 30, codec), side, side * 16 / 9)}")
                }
                Log.i("AMCaps", "done probe=${report.probe?.pixelsPerSecond}")
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { Log.e("AMCaps", "failed", e) }
        }
        if (intent.getBooleanExtra("devicecheck", false)) scope.launch {
            try { checkDevice(activity) }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { Log.e("AMDevice", "failed", e) }
        }
        app.absentmotion.ai.AiNet.cpuOnly = intent.getBooleanExtra("aicpu", false)
        intent.getStringExtra("aicheck")?.let { mode ->
            scope.launch {
                try { checkAi(activity, mode != "cpu") }
                catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (e: Throwable) { Log.e("AMAi", "failed", e) }
            }
        }
        intent.getStringExtra("enhancecheck")?.let { path ->
            scope.launch {
                try { checkEnhance(activity, path, (intent.getStringExtra("modes") ?: "upscale2").split(',')) }
                catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (e: Throwable) { Log.e("AMAi", "enhance failed", e) }
            }
        }
        intent.getStringExtra("aismoothcheck")?.let { path ->
            scope.launch {
                try { checkSmooth(activity, path, (intent.getStringExtra("levels") ?: "Low").split(',')) }
                catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (e: Throwable) { Log.e("AMAi", "aismooth failed", e) }
            }
        }
        intent.getStringExtra("aismoothtrim")?.let { path ->
            scope.launch {
                try { checkSmoothTrim(activity, path, intent.getIntExtra("from", 1000), intent.getIntExtra("to", 3000)) }
                catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (e: Throwable) { Log.e("AMAi", "smoothtrim failed", e) }
            }
        }
        intent.getStringExtra("videocutcheck")?.let { path ->
            scope.launch {
                try { checkVideoCut(activity, path, intent.getIntExtra("side", 720)) }
                catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (e: Throwable) { Log.e("AMAi", "videocut failed", e) }
            }
        }
        intent.getStringExtra("aidownload")?.let { name ->
            scope.launch {
                try { checkDownload(activity, name, intent.getLongExtra("stopafter", 0L)) }
                catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (e: Throwable) { Log.e("AMAi", "download failed", e) }
            }
        }
        intent.getStringExtra("bgremove")?.let { asset ->
            val runs = intent.getIntExtra("runs", 3).coerceAtLeast(1)
            scope.launch(Dispatchers.IO) { checkBackgroundRemoval(activity, asset, runs) }
        }
        if (intent.hasExtra("hqinstall") || intent.hasExtra("hqremove")) scope.launch(Dispatchers.IO) {
            intent.getStringExtra("hqinstall")?.let { installCutout(activity, File(it)) }
            app.absentmotion.ai.BestCutout.sideOverride = intent.getIntExtra("hqside", 0).takeIf { it > 0 }
            intent.getStringExtra("hqremove")?.let { checkBestCutout(activity, File(it)) }
        }
        intent.getStringExtra("batch")?.let { list ->
            scope.launch {
                for (line in File(list).readLines().filter { it.isNotBlank() }) {
                    val (path, frame, name) = line.trim().split(' ')
                    val scene = activity.migration.migrate(Scene.parse(File(path).readText())).scene
                    downloadFonts(activity, scene)
                    val ok = engine.stillPng(scene.toXml(), scene.frameStart(frame.toInt()), scene.width, scene.height, File(activity.getExternalFilesDir(null), "$name.png"))
                    Log.i("AMTest", "still $name ok=$ok render=${"%.1f".format(java.util.Locale.US, engine.lastStillMs)}ms")
                }
                Log.i("AMTest", "batch done")
            }
        }
        val path = intent.getStringExtra("xml") ?: return
        val parsed = runCatching { Scene.parse(File(path).readText()) }.getOrElse { Log.e("AMTest", "cannot read $path", it); return }
        val id = "test-" + File(path).nameWithoutExtension
        activity.store.save(id, parsed)
        scope.launch {
            downloadFonts(activity, parsed)
            val state = activity.openNow(id) ?: return@launch
            val scene = state.scene
            state.frame = intent.getIntExtra("frame", 0).coerceIn(0, scene.lastFrame)
            if (intent.getBooleanExtra("openexport", false)) state.exportOpen = true
            intent.getStringExtra("clipsmooth")?.let { method -> launch { checkClipSmooth(activity, state, method, intent.getStringExtra("smoothlevel"), intent.getStringExtra("smoothblur")) } }
            val stillName = intent.getStringExtra("still")
            intent.getStringExtra("export")?.let { exportForTest(activity, it, scene, state.frame) }
            intent.getStringExtra("verify")?.let { file ->
                launch {
                    val kind = if (file.endsWith(".mp4") || file.endsWith(".mkv")) ExportKind.Video else ExportKind.Audio
                    val expect = app.absentmotion.export.expectationFor(kind, scene, defaultSettings(kind, scene), app.absentmotion.audio.OfflineMix.audible(activity.applicationContext, scene).isNotEmpty())!!
                    val v = app.absentmotion.export.verifyExport(activity.applicationContext, Uri.fromFile(File(file)), expect)
                    Log.i("AMExport", "verify " + app.absentmotion.export.verificationSummary(v))
                }
            }
            try {
                engine.awaitMedia()
                try { engine.awaitVideoFrames(scene, state.timeMs) } catch (e: java.io.IOException) { Log.i("AMTest", "video unavailable: ${e.message}") }
                Log.i("AMTest", "media ready $path")
                stillName?.let { name ->
                    val ok = engine.stillPng(scene.toXml(), state.timeMs, scene.width, scene.height, File(activity.getExternalFilesDir(null), "$name.png"))
                    Log.i("AMTest", "still $name ok=$ok")
                }
                intent.getStringExtra("previewstill")?.let { name ->
                    val before = state.frame
                    for (accurate in listOf(false, true)) {
                        val pixels = engine.capturePreviewFrame(scene, state.timeMs, scene.width, scene.height, accurate) { true }
                        if (pixels != null) File(activity.getExternalFilesDir(null), "$name-${if (accurate) "full" else "fast"}.rgba").writeBytes(pixels)
                        Log.i("AMTest", "previewstill $name accurate=$accurate ok=${pixels != null} frame=${state.frame} before=$before")
                    }
                }
                intent.getStringExtra("hit")?.split(';')?.forEach { point ->
                    val xy = point.split(',').mapNotNull { it.trim().toFloatOrNull() }
                    if (xy.size == 2) Log.i("AMTest", "hit $point ${engine.hitTest(xy[0], xy[1], state.timeMs)}")
                }
                intent.getStringExtra("bounds")?.split(';')?.mapNotNull { it.trim().toLongOrNull() }?.forEach { id ->
                    val box = engine.layerBounds(id, state.timeMs)
                    val knots = engine.layerOutline(id, state.timeMs)?.let { svg -> app.absentmotion.model.parseSvgPath(svg).flatMap { c -> c.knots.map { it.p } } }.orEmpty()
                    val outline = if (knots.isEmpty()) "none" else listOf(knots.minOf { it.x }, knots.minOf { it.y }, knots.maxOf { it.x }, knots.maxOf { it.y }).joinToString(",")
                    Log.i("AMTest", "bounds $id ${box?.joinToString(",") ?: "none"} outline $outline")
                }
                intent.getIntExtra("bench", 0).takeIf { it > 0 }?.let { runBench(activity, state, it, intent.getBooleanExtra("proxies", false)) }
                intent.getStringExtra("gifimport")?.let { importGifForTest(activity, state, it, intent.getIntExtra("gifcancel", 0)) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: IllegalStateException) {
                if (e.message != "engine closed") throw e
                Log.i("AMTest", "test capture stopped with the engine")
            }
        }
    }

    private suspend fun importGifForTest(activity: MainActivity, state: app.absentmotion.editor.EditorState, path: String, cancelMs: Int) = kotlinx.coroutines.coroutineScope {
        val started = SystemClock.elapsedRealtime()
        val before = state.scene.layers.size
        val job = launch { state.addPicked(activity, Uri.fromFile(File(path))) }
        var cancelled = started
        if (cancelMs > 0) {
            kotlinx.coroutines.delay(cancelMs.toLong())
            cancelled = SystemClock.elapsedRealtime()
            job.cancel()
        }
        job.join()
        val files = File(activity.getExternalFilesDir(null), "ai").listFiles().orEmpty().filter { it.lastModified() >= System.currentTimeMillis() - 600_000 }.map { "${it.name}:${it.length()}" }
        Log.i("AMTest", "gifimport cancelled=${job.isCancelled} ms=${SystemClock.elapsedRealtime() - started} stopMs=${if (cancelMs > 0) SystemClock.elapsedRealtime() - cancelled else 0} layers=$before->${state.scene.layers.size} files=$files")
    }

    private suspend fun checkBackgroundRemoval(activity: MainActivity, asset: String, runs: Int) {
        try {
            val input = File(asset).takeIf { it.isFile } ?: File(activity.cacheDir, "bgremove-" + asset.substringAfterLast('/')).also { file ->
                activity.assets.open(asset).use { source -> file.outputStream().use { source.copyTo(it) } }
            }
            val times = mutableListOf<Long>()
            var output: AiMedia? = null
            repeat(runs) {
                output?.file?.delete()
                val start = SystemClock.elapsedRealtime()
                output = BackgroundRemoval.run(activity.applicationContext, Uri.fromFile(input).toString(), null, activity.engine.maxPictureSize()) { _, _ -> }
                times += SystemClock.elapsedRealtime() - start
            }
            val result = File(activity.getExternalFilesDir(null), "bgremove.png")
            checkNotNull(output).file.let { it.copyTo(result, overwrite = true); it.delete() }
            val source = checkNotNull(BitmapFactory.decodeFile(input.path))
            val cut = checkNotNull(BitmapFactory.decodeFile(result.path))
            val pixels = IntArray(cut.width * cut.height).also { cut.getPixels(it, 0, cut.width, 0, 0, cut.width, cut.height) }
            val clear = pixels.count { it ushr 24 <= 8 }
            val solid = pixels.count { it ushr 24 >= 247 }
            val ok = cut.width == source.width && cut.height == source.height && clear > 0 && solid > 0
            Log.i("AMTest", "bgremove $asset ${source.width}x${source.height} -> ${cut.width}x${cut.height} transparent=$clear opaque=$solid ms=$times ok=$ok")
        } catch (e: Throwable) {
            Log.e("AMTest", "bgremove failed", e)
        }
    }

    private fun installCutout(activity: MainActivity, source: File) {
        try {
            val model = app.absentmotion.ai.AiModel.Cutout
            val files = source.listFiles().orEmpty()
            val picks = mapOf(
                app.absentmotion.ai.CUTOUT_PARAM_768 to files.first { it.name.endsWith(".param") && "768" in it.name },
                app.absentmotion.ai.CUTOUT_PARAM_1024 to files.first { it.name.endsWith(".param") && "1024" in it.name },
                model.bin to files.first { it.name.endsWith(".bin") },
            )
            model.dir(activity).mkdirs()
            for ((name, file) in picks) file.copyTo(model.file(activity, name), overwrite = true)
            model.verify(activity)
            Log.i("AMTest", "hqinstall ok ${model.dir(activity)} installed=${model.installed(activity)}")
        } catch (e: Throwable) {
            Log.e("AMTest", "hqinstall failed", e)
        }
    }

    private fun peakKb(): Long = File("/proc/self/status").readLines().firstOrNull { it.startsWith("VmHWM:") }?.filter(Char::isDigit)?.toLongOrNull() ?: -1L

    private suspend fun checkBestCutout(activity: MainActivity, source: File) {
        val inputs = if (source.isDirectory) source.listFiles().orEmpty().filter { it.isFile }.sortedBy { it.name } else listOf(source)
        val out = File(activity.getExternalFilesDir(null), "hqremove").apply { mkdirs() }
        for (input in inputs) {
            try {
                runCatching { File("/proc/self/clear_refs").writeText("5") }
                val before = peakKb()
                val start = SystemClock.elapsedRealtime()
                val media = app.absentmotion.ai.BestCutout.run(activity.applicationContext, Uri.fromFile(input).toString(), null, activity.engine.maxPictureSize()) { _, _ -> }
                val ms = SystemClock.elapsedRealtime() - start
                val result = File(out, input.nameWithoutExtension + ".png")
                media.file.copyTo(result, overwrite = true)
                media.file.delete()
                val size = BitmapFactory.Options().apply { inJustDecodeBounds = true }.also { BitmapFactory.decodeFile(result.path, it) }
                Log.i("AMTest", "hqremove ${input.name} -> ${size.outWidth}x${size.outHeight} ms=$ms peakKb=${peakKb()} startKb=$before out=${result.path}")
            } catch (e: Throwable) {
                Log.e("AMTest", "hqremove ${input.name} failed", e)
            }
        }
        Log.i("AMTest", "hqremove done")
    }

    private fun exportForTest(activity: MainActivity, kindName: String, scene: Scene, frame: Int) {
        val intent = activity.intent
        val engine = activity.engine
        val kind = mapOf("video" to ExportKind.Video, "gif" to ExportKind.Gif, "frame" to ExportKind.Frame, "frameas" to ExportKind.FrameAs, "sequence" to ExportKind.Sequence,
            "xml" to ExportKind.Xml, "package" to ExportKind.Package, "audio" to ExportKind.Audio)[kindName] ?: run { Log.e("AMExport", "unknown kind $kindName"); return }
        var s = defaultSettings(kind, scene)
        intent.takeIf { it.hasExtra("short") }?.let { s = s.copy(shortSide = it.getIntExtra("short", s.shortSide)) }
        intent.takeIf { it.hasExtra("fps") }?.let { s = s.copy(fps = it.getIntExtra("fps", s.fps)) }
        intent.getStringExtra("codec")?.let { name -> app.absentmotion.export.Codec.entries.firstOrNull { it.name.equals(name, true) }?.let { s = s.copy(codec = it) } }
        intent.takeIf { it.hasExtra("quality") }?.let { s = s.copy(quality = it.getFloatExtra("quality", s.quality)) }
        intent.getStringExtra("format")?.let { name -> app.absentmotion.export.ImageKind.entries.firstOrNull { it.name.equals(name, true) || it.ext == name }?.let { s = s.copy(image = it) } }
        intent.takeIf { it.hasExtra("iquality") }?.let { s = s.copy(imageQuality = it.getIntExtra("iquality", s.imageQuality)) }
        intent.getStringExtra("interp")?.let { name -> FrameInterpolation.entries.firstOrNull { it.name.equals(name, true) }?.let { s = s.copy(interpolation = it) } }
        intent.getStringExtra("interpLevel")?.let { name -> app.absentmotion.export.SmoothLevel.entries.firstOrNull { it.name.equals(name, true) }?.let { s = s.copy(interpolationLevel = it) } }
        intent.getStringExtra("blur")?.let { name -> app.absentmotion.export.MotionBlur.entries.firstOrNull { it.name.equals(name, true) }?.let { s = s.copy(motionBlur = it) } }
        app.absentmotion.export.ExportDebug.forceBuffers = intent.getBooleanExtra("buffers", false)
        app.absentmotion.export.ExportDebug.fakeFreeBytes = intent.getLongExtra("fakefree", -1L)
        app.absentmotion.export.ThermalMonitor.of(activity.applicationContext).forced =
            app.absentmotion.export.ThermalLevel.entries.firstOrNull { it.name.equals(intent.getStringExtra("thermal"), true) }
        intent.getStringExtra("container")?.let { name -> app.absentmotion.export.Container.entries.firstOrNull { it.name.equals(name, true) }?.let { s = s.copy(container = it) } }
        intent.getStringExtra("aformat")?.let { name -> app.absentmotion.export.AudioFormat.entries.firstOrNull { it.name.equals(name, true) }?.let { s = s.copy(audioFormat = it) } }
        var a = s.audio
        intent.getStringExtra("acodec")?.let { name -> app.absentmotion.export.AudioCodec.entries.firstOrNull { it.name.equals(name, true) }?.let { a = a.copy(codec = it) } }
        intent.takeIf { it.hasExtra("abitrate") }?.let { a = a.copy(bitrateKbps = it.getIntExtra("abitrate", a.bitrateKbps)) }
        intent.takeIf { it.hasExtra("arate") }?.let { a = a.copy(sampleRate = it.getIntExtra("arate", a.sampleRate)) }
        intent.takeIf { it.hasExtra("channels") }?.let { a = a.copy(channels = it.getIntExtra("channels", a.channels)) }
        s = s.copy(audio = a)
        val dir = activity.getExternalFilesDir(null)!!
        val ext = mapOf(ExportKind.Video to s.fixedFormat().container.ext, ExportKind.Audio to s.audioFormat.ext, ExportKind.Gif to "gif", ExportKind.Frame to "png", ExportKind.FrameAs to s.image.ext, ExportKind.Xml to "xml", ExportKind.Package to "absproj")
        val name = if (kind == ExportKind.Sequence) "export.seq" else "export"
        val job = activity.lifecycleScope.launch {
            try {
                if (kind == ExportKind.Video) {
                    val probe = app.absentmotion.export.DeviceCapabilities.of(activity.applicationContext).load()?.probe
                    Log.i("AMExport", "estimate ms=${app.absentmotion.export.ExportTimes(activity.applicationContext).estimateMs(s.fixedFormat(), scene, probe)}")
                }
                if (kind == ExportKind.Gif) Log.i("AMExport", "estimate ms=" + app.absentmotion.export.gifTimeMs(
                    app.absentmotion.export.frameCount(scene, s.fps), app.absentmotion.export.measureGif(engine, scene, s),
                    app.absentmotion.export.ExportTimes(activity.applicationContext).estimateMs(s, scene)))
                val started = SystemClock.elapsedRealtime()
                val mediaStore = intent.getBooleanExtra("mediastore", false)
                val result = Exporter(activity.applicationContext, engine, if (mediaStore) app.absentmotion.export.MediaStoreExport(activity.applicationContext) else FolderExport(dir))
                    .export(kind, scene, s, frame, name) { }
                result.verification?.let { Log.i("AMExport", "verify " + app.absentmotion.export.verificationSummary(it)) }
                if (mediaStore) Log.i("AMExport", "done " + result.uris.joinToString(" "))
                else Log.i("AMExport", "done " + File(dir, if (kind == ExportKind.Sequence) name else "$name.${ext[kind]}").path + " ms=" + (SystemClock.elapsedRealtime() - started))
            } catch (e: Throwable) {
                Log.e("AMExport", "failed", e)
            }
        }
        intent.getIntExtra("cancelafter", 0).takeIf { it > 0 }?.let { ms -> activity.lifecycleScope.launch { kotlinx.coroutines.delay(ms.toLong()); job.cancel() } }
    }
}
