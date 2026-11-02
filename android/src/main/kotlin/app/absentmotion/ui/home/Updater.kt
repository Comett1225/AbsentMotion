package app.absentmotion.ui.home

import android.app.Activity
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Parcelable
import android.os.storage.StorageManager
import android.provider.Settings
import androidx.annotation.ChecksSdkIntAtLeast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.absentmotion.platform.Context
import app.absentmotion.platform.Log
import app.absentmotion.platform.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.security.MessageDigest

internal sealed interface UpdateStep {
    data object Idle : UpdateStep
    data class Downloading(val release: Release, val done: Long, val total: Long, val waiting: Boolean = false) : UpdateStep
    data class Checking(val release: Release) : UpdateStep
    data class Permission(val release: Release, val denied: Boolean, val downloaded: Boolean = true) : UpdateStep
    data class Installing(val release: Release) : UpdateStep
    data class NotInstalled(val release: Release) : UpdateStep
    data class Failed(val release: Release, val failure: UpdateFailure) : UpdateStep
}

internal sealed interface UpdateEvent {
    data class Start(val release: Release) : UpdateEvent
    data class Ask(val release: Release) : UpdateEvent
    data class Resume(val release: Release) : UpdateEvent
    data class Progress(val done: Long) : UpdateEvent
    data object Waiting : UpdateEvent
    data class Stopped(val release: Release) : UpdateEvent
    data object Downloaded : UpdateEvent
    data object NeedsPermission : UpdateEvent
    data class SettingsClosed(val granted: Boolean) : UpdateEvent
    data object Install : UpdateEvent
    data class Answer(val status: Int) : UpdateEvent
    data class Fail(val failure: UpdateFailure) : UpdateEvent
    data object Cancel : UpdateEvent
}

internal val UpdateStep.release: Release?
    get() = when (this) {
        UpdateStep.Idle -> null
        is UpdateStep.Downloading -> release
        is UpdateStep.Checking -> release
        is UpdateStep.Permission -> release
        is UpdateStep.Installing -> release
        is UpdateStep.NotInstalled -> release
        is UpdateStep.Failed -> release
    }

internal fun installerFailure(status: Int, size: Long): UpdateFailure? = when (status) {
    PackageInstaller.STATUS_SUCCESS, PackageInstaller.STATUS_PENDING_USER_ACTION, PackageInstaller.STATUS_FAILURE_ABORTED -> null
    PackageInstaller.STATUS_FAILURE_STORAGE -> UpdateFailure.Storage(size)
    PackageInstaller.STATUS_FAILURE_CONFLICT -> UpdateFailure.Signature
    PackageInstaller.STATUS_FAILURE_INCOMPATIBLE, PackageInstaller.STATUS_FAILURE_INVALID -> UpdateFailure.WrongApp
    else -> UpdateFailure.InstallFailed
}

internal fun reduce(step: UpdateStep, event: UpdateEvent): UpdateStep {
    val release = step.release
    return when (event) {
        is UpdateEvent.Start -> when (step) {
            UpdateStep.Idle, is UpdateStep.Failed, is UpdateStep.NotInstalled -> if (event.release.apk != null) UpdateStep.Downloading(event.release, 0, event.release.apk.size) else step
            else -> step
        }
        is UpdateEvent.Ask -> when (step) {
            UpdateStep.Idle, is UpdateStep.Failed, is UpdateStep.NotInstalled -> if (event.release.apk != null) UpdateStep.Permission(event.release, denied = false, downloaded = false) else step
            else -> step
        }
        is UpdateEvent.Resume -> if (step == UpdateStep.Idle) UpdateStep.Checking(event.release) else step
        is UpdateEvent.Progress -> if (step is UpdateStep.Downloading) step.copy(done = event.done.coerceIn(0, step.total), waiting = false) else step
        UpdateEvent.Waiting -> if (step is UpdateStep.Downloading) step.copy(waiting = true) else step
        is UpdateEvent.Stopped -> if (step == UpdateStep.Idle && event.release.apk != null) UpdateStep.Failed(event.release, UpdateFailure.Interrupted) else step
        UpdateEvent.Downloaded -> if (step is UpdateStep.Downloading) UpdateStep.Checking(step.release) else step
        UpdateEvent.NeedsPermission -> when (step) {
            is UpdateStep.Checking, is UpdateStep.NotInstalled -> UpdateStep.Permission(step.release!!, denied = false)
            else -> step
        }
        is UpdateEvent.SettingsClosed -> when {
            step !is UpdateStep.Permission -> step
            !event.granted -> step.copy(denied = true)
            step.downloaded -> UpdateStep.Installing(step.release)
            else -> step.release.apk?.let { UpdateStep.Downloading(step.release, 0, it.size) } ?: step
        }
        UpdateEvent.Install -> when (step) {
            is UpdateStep.Checking, is UpdateStep.NotInstalled -> UpdateStep.Installing(step.release!!)
            is UpdateStep.Permission -> if (step.downloaded) UpdateStep.Installing(step.release) else step
            else -> step
        }
        is UpdateEvent.Answer -> when {
            step !is UpdateStep.Installing -> step
            event.status == PackageInstaller.STATUS_PENDING_USER_ACTION -> step
            event.status == PackageInstaller.STATUS_SUCCESS -> UpdateStep.Idle
            event.status == PackageInstaller.STATUS_FAILURE_ABORTED -> UpdateStep.NotInstalled(step.release)
            else -> UpdateStep.Failed(step.release, installerFailure(event.status, step.release.apk?.size?.let(::installSpace) ?: 0) ?: UpdateFailure.InstallFailed)
        }
        is UpdateEvent.Fail -> if (release == null) step else UpdateStep.Failed(release, event.failure)
        UpdateEvent.Cancel -> UpdateStep.Idle
    }
}

internal fun begin(step: UpdateStep, release: Release, mayInstall: Boolean): UpdateStep =
    reduce(step, if (mayInstall) UpdateEvent.Start(release) else UpdateEvent.Ask(release))

internal fun needsAttention(step: UpdateStep) =
    step is UpdateStep.Permission || step is UpdateStep.NotInstalled || step is UpdateStep.Failed

internal fun flowOver(step: UpdateStep) = step == UpdateStep.Idle || (step is UpdateStep.Failed && !step.failure.retry)

internal fun CoroutineScope.launchAfter(before: Job?, block: suspend CoroutineScope.() -> Unit): Job =
    launch(start = CoroutineStart.UNDISPATCHED) {
        withContext(NonCancellable) { before?.join() }
        block()
    }

internal object Updater {
    var step by mutableStateOf<UpdateStep>(UpdateStep.Idle)
        private set
    var hidden by mutableStateOf(false)
    var confirm by mutableStateOf<Intent?>(null)
        private set
    private val scope = MainScope()
    private var job: Job? = null
    private var tail: Job? = null
    private var http: TrustedHttp? = null
    private var generation = 0
    @Volatile private var session = -1
    private var awaitingSettings = false
    private var flow: File? = null
    private const val ACTION = "app.absentmotion.UPDATE_STATUS"
    private const val TAG = "AMUpdate"

    val busy: Boolean get() = step != UpdateStep.Idle

    private fun on(event: UpdateEvent) {
        val next = reduce(step, event)
        if (next == step) return
        val moved = next::class != step::class
        step = next
        if (needsAttention(next)) hidden = false
        if (flowOver(next)) flow?.let { runCatching { it.delete() } }
        if (moved) Log.i(TAG, "update step ${next::class.simpleName}")
    }

    fun inApp(context: Context, source: UpdateSource): Boolean {
        val (signers, history) = installedSigning(context) ?: return false
        return updatesInApp(installerOf(context), signers, history, source.signers)
    }

    fun files(context: Context) = updateFiles(context.cacheDir, context.noBackupFilesDir)

    fun reveal(): Boolean {
        if (!busy) return false
        hidden = false
        return true
    }

    fun start(context: Context, release: Release) {
        release.apk ?: return
        if (job?.isActive == true || step is UpdateStep.Installing || step is UpdateStep.Checking || step is UpdateStep.Permission) {
            hidden = false
            return
        }
        val mayInstall = context.packageManager.canRequestPackageInstalls()
        on(if (mayInstall) UpdateEvent.Start(release) else UpdateEvent.Ask(release))
        hidden = false
        proceed(context.applicationContext)
    }

    private fun proceed(app: Context) {
        when (val now = step) {
            is UpdateStep.Downloading -> download(app, now.release)
            is UpdateStep.Installing -> commit(app, now.release)
            else -> Unit
        }
    }

    private fun download(app: Context, release: Release) {
        val apk = release.apk ?: return
        if (step !is UpdateStep.Downloading) return
        hidden = false
        val client = TrustedHttp(Updates.source)
        http = client
        val mine = ++generation
        val files = files(app)
        flow = files.flow
        runCatching { writeFlow(files, System.currentTimeMillis(), release) }
        job = scope.launchAfter(tail) {
            try {
                val file = withContext(Dispatchers.IO) {
                    if (online(app) == false) throw UpdateProblem(UpdateFailure.Offline)
                    val wait: suspend (Long) -> Unit = { ms ->
                        scope.launch { if (generation == mine) on(UpdateEvent.Waiting) }
                        delay(ms)
                    }
                    patiently(apk.size, { files.part.length() }, wait) {
                        ensureActive()
                        val sha = checksumFor(client, apk)
                        claimSpace(spaceFor(files, release, sha), { freeBytes(app) }, { usableBytes(app) }) { allocate(app, it) }
                        var shown = -1L
                        val stride = (apk.size / 200).coerceAtLeast(64L * 1024)
                        downloadApk(client, release, sha, files, { ensureActive() }) { done ->
                            if (done == apk.size || done - shown >= stride || shown < 0) {
                                shown = done
                                scope.launch { if (generation == mine) on(UpdateEvent.Progress(done)) }
                            }
                        }
                    }
                }
                if (generation != mine) return@launchAfter
                on(UpdateEvent.Downloaded)
                verifyAndInstall(app, release, file)
            } catch (e: CancellationException) {
                withContext(NonCancellable + Dispatchers.IO) { if (generation != mine || step == UpdateStep.Idle) files.part.delete() }
            } catch (e: Exception) {
                Log.i(TAG, "update download stopped: $e (${e.cause})")
                val failure = failureOf(e, apk.size).let { if (it == UpdateFailure.Connection && online(app) == false) UpdateFailure.Offline else it }
                if (generation == mine) on(UpdateEvent.Fail(failure))
            } finally {
                if (generation == mine) http = null
            }
        }
        tail = job
    }

    private suspend fun verifyAndInstall(app: Context, release: Release, file: File) {
        val problem = withContext(Dispatchers.IO) {
            val installed = installedApp(app, Updates.installed(app)) ?: return@withContext UpdateFailure.Signature
            judgeApk(apkFacts(app, file), installed, release.version)
        }
        if (problem != null) {
            withContext(Dispatchers.IO) { files(app).clear() }
            on(UpdateEvent.Fail(problem))
            return
        }
        install(app)
    }

    fun install(context: Context) {
        val current = step
        if (current !is UpdateStep.Checking && current !is UpdateStep.NotInstalled && current !is UpdateStep.Permission) return
        if (!context.packageManager.canRequestPackageInstalls()) {
            on(UpdateEvent.NeedsPermission)
            return
        }
        on(UpdateEvent.Install)
        commit(context.applicationContext, current.release!!)
    }

    fun openSettings(activity: Activity) {
        val current = step as? UpdateStep.Permission ?: return
        val files = files(activity)
        val stamp = if (current.downloaded) runCatching { files.stamp.readText() }.getOrNull() else null
        if (current.downloaded && (stamp == null || releaseOfStamp(stamp)?.version != current.release.version)) {
            on(UpdateEvent.Fail(UpdateFailure.Removed))
            return
        }
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + activity.packageName))
        try {
            val now = System.currentTimeMillis()
            if (stamp != null) writeResume(files, now, stamp) else writeAsk(files, now, current.release)
            awaitingSettings = true
            activity.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            awaitingSettings = false
            files.resume.delete()
            files.ask.delete()
            on(UpdateEvent.Fail(UpdateFailure.NoSettings))
        } catch (e: IOException) {
            awaitingSettings = false
            on(UpdateEvent.Fail(UpdateFailure.Save))
        }
    }

    fun resumed(context: Context) {
        val asked = awaitingSettings
        awaitingSettings = false
        if (asked) files(context).run { resume.delete(); ask.delete() }
        if (step !is UpdateStep.Permission) return
        val granted = context.packageManager.canRequestPackageInstalls()
        if (!asked && !granted) return
        on(UpdateEvent.SettingsClosed(granted))
        proceed(context.applicationContext)
    }

    fun atLaunch(context: Context) {
        if (busy) return
        val app = context.applicationContext
        val files = files(app)
        flow = files.flow
        scope.launch {
            val now = System.currentTimeMillis()
            val (resume, asked, stopped) = withContext(Dispatchers.IO) {
                tidy(app, files, now)
                val resume = readResume(files, now)
                val asked = readAsk(files, now)?.takeIf { isNewer(it.version, Updates.installed(app)) == true }
                val stopped = if (resume != null || asked != null) null else readFlow(files, now)?.let { release ->
                    release.takeIf { isNewer(it.version, Updates.installed(app)) == true && keptFor(files, it) } ?: run { files.flow.delete(); null }
                }
                Triple(resume, asked, stopped)
            }
            if (busy) return@launch
            if (resume == null) {
                if (asked != null) restoreAsk(app, asked)
                else if (stopped != null) on(UpdateEvent.Stopped(stopped))
                return@launch
            }
            val release = resume.release
            on(UpdateEvent.Resume(release))
            val checking = step
            if (checking !is UpdateStep.Checking) return@launch
            val ready = withContext(Dispatchers.IO) { cachedApkReady(files, resume).also { if (!it) files.apk.delete() } }
            if (step != checking) return@launch
            if (!ready) {
                on(UpdateEvent.Fail(UpdateFailure.Removed))
                return@launch
            }
            if (app.packageManager.canRequestPackageInstalls()) verifyAndInstall(app, release, files.apk)
            else {
                on(UpdateEvent.NeedsPermission)
                on(UpdateEvent.SettingsClosed(false))
            }
        }
    }

    private fun restoreAsk(app: Context, release: Release) {
        on(UpdateEvent.Ask(release))
        if (step !is UpdateStep.Permission) return
        on(UpdateEvent.SettingsClosed(app.packageManager.canRequestPackageInstalls()))
        proceed(app)
    }

    private fun tidy(app: Context, files: UpdateFiles, now: Long) {
        val installer = app.packageManager.packageInstaller
        if (session < 0) installer.mySessions.forEach { runCatching { installer.abandonSession(it.sessionId) } }
        val version = files.version()
        val resuming = files.resume.isFile
        if (!resuming && (version == null || isNewer(version, Updates.installed(app)) != true || files.stale(now))) files.dir.listFiles()?.forEach { it.delete() }
    }

    private fun commit(app: Context, release: Release) {
        val apk = release.apk ?: return
        val mine = ++generation
        job = scope.launch {
            try {
                val id = withContext(Dispatchers.IO) { writeSession(app, files(app).apk, apk.size, apk.sha256 ?: stampSha(files(app))) }
                if (generation != mine || step !is UpdateStep.Installing) {
                    abandon(app, id)
                    if (session == id) session = -1
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.i(TAG, "update session failed: $e")
                if (generation == mine) on(UpdateEvent.Fail(if (e is UpdateProblem) e.failure else failureOf(e, installSpace(apk.size)).takeIf { it is UpdateFailure.Storage } ?: UpdateFailure.InstallFailed))
            }
        }
    }

    private fun stampSha(files: UpdateFiles): String? = runCatching { files.stamp.readLines().getOrNull(2) }.getOrNull()?.takeIf(::isSha256)

    private fun writeSession(app: Context, file: File, size: Long, sha256: String?): Int {
        if (!file.isFile) throw UpdateProblem(UpdateFailure.Removed)
        if (sha256 == null || file.length() != size) throw UpdateProblem(UpdateFailure.Checksum)
        claimSpace(installSpace(size), { freeBytes(app) }, { usableBytes(app) }) { allocate(app, it) }
        val installer = app.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(app.packageName)
            setSize(size)
            setInstallReason(PackageManager.INSTALL_REASON_USER)
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
        }
        val id = installer.createSession(params)
        session = id
        try {
            installer.openSession(id).use { session ->
                val digest = MessageDigest.getInstance("SHA-256")
                file.inputStream().use { input ->
                    session.openWrite("base.apk", 0, size).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            digest.update(buffer, 0, count)
                            output.write(buffer, 0, count)
                        }
                        session.fsync(output)
                    }
                }
                if (hex(digest.digest()) != sha256) throw UpdateProblem(UpdateFailure.Checksum)
                val intent = Intent(app, UpdateReceiver::class.java).setAction(ACTION).setPackage(app.packageName)
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                session.commit(PendingIntent.getBroadcast(app, id, intent, flags).intentSender)
            }
        } catch (e: Exception) {
            runCatching { installer.abandonSession(id) }
            if (session == id) session = -1
            throw e
        }
        return id
    }

    private fun abandon(app: Context, id: Int) {
        if (id >= 0) runCatching { app.packageManager.packageInstaller.abandonSession(id) }
    }

    fun answer(context: Context, id: Int, status: Int, prompt: Intent?) {
        Log.i(TAG, "installer answered $status for session $id")
        if (id != session || step !is UpdateStep.Installing) {
            if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) abandon(context, id)
            return
        }
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            if (prompt == null) {
                abandon(context, id)
                session = -1
                on(UpdateEvent.Fail(UpdateFailure.NoInstaller))
            } else confirm = prompt
            return
        }
        session = -1
        confirm = null
        on(UpdateEvent.Answer(status))
    }

    fun confirmShown(context: Context, failed: Boolean) {
        confirm = null
        if (!failed) return
        abandon(context, session)
        session = -1
        on(UpdateEvent.Fail(UpdateFailure.NoInstaller))
    }

    fun cancel(context: Context) {
        val wasDownloading = step is UpdateStep.Downloading
        generation++
        job?.cancel()
        http?.abort()
        http = null
        abandon(context, session)
        session = -1
        confirm = null
        awaitingSettings = false
        Updates.answer = null
        flow = files(context.applicationContext).flow
        on(UpdateEvent.Cancel)
        if (wasDownloading) {
            val files = files(context.applicationContext)
            tail = scope.launchAfter(tail) { withContext(Dispatchers.IO) { files.part.delete() } }
        }
    }

    fun metered(context: Context): Boolean? = runCatching {
        context.applicationContext.getSystemService(ConnectivityManager::class.java).isActiveNetworkMetered
    }.getOrNull()

    private fun online(app: Context): Boolean? = runCatching {
        val connectivity = app.getSystemService(ConnectivityManager::class.java)
        connectivity.getNetworkCapabilities(connectivity.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }.getOrNull()

    private fun freeBytes(app: Context): Long = runCatching {
        val storage = app.getSystemService(StorageManager::class.java)
        storage.getAllocatableBytes(storage.getUuidForPath(app.cacheDir))
    }.getOrElse { usableBytes(app) }

    private fun usableBytes(app: Context): Long = app.cacheDir.usableSpace.takeIf { it > 0 } ?: -1L

    private fun allocate(app: Context, bytes: Long) {
        val storage = app.getSystemService(StorageManager::class.java) ?: return
        storage.allocateBytes(storage.getUuidForPath(app.cacheDir), bytes)
    }
}

class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val id = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
        val prompt = if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) promptOf(intent) else null
        Updater.answer(context.applicationContext, id, status, prompt)
    }

    private fun promptOf(intent: Intent): Intent? =
        if (typedExtras()) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java) else ofType(Intent::class.java, legacyExtra(intent))

    @Suppress("DEPRECATION")
    private fun legacyExtra(intent: Intent): Parcelable? = intent.getParcelableExtra(Intent.EXTRA_INTENT)

    @ChecksSdkIntAtLeast(api = TYPED_EXTRAS_SDK)
    private fun typedExtras() = typedExtrasWork(Build.VERSION.SDK_INT)
}

internal const val TYPED_EXTRAS_SDK = 34

internal fun typedExtrasWork(sdk: Int) = sdk >= TYPED_EXTRAS_SDK

internal fun <T : Any> ofType(kind: Class<T>, value: Any?): T? = if (kind.isInstance(value)) kind.cast(value) else null
