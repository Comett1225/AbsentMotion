package app.absentmotion.ui.home

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.TimeUnit

internal const val POSIX_SWAP = """#!/bin/sh
pid="${'$'}1"; staged="${'$'}2"; target="${'$'}3"; kind="${'$'}4"; result="${'$'}5"; cleanup="${'$'}6"
echo "started" > "${'$'}result"
others() {
    if [ -d /proc/self ]; then
        for d in /proc/[0-9]*; do
            p="${'$'}{d#/proc/}"
            [ "${'$'}p" = "${'$'}${'$'}" ] && continue
            case "${'$'}(readlink "${'$'}d/exe" 2>/dev/null)" in "${'$'}1"/*) echo "${'$'}p"; continue ;; esac
            if tr '\000' '\n' < "${'$'}d/environ" 2>/dev/null | grep -Fqx "APPIMAGE=${'$'}1"; then echo "${'$'}p"; fi
        done
    else
        ps -A -ww -o pid= -o command= | awk -v t="${'$'}1" -v me="${'$'}${'$'}" '${'$'}1 != me { c = substr(${'$'}0, index(${'$'}0, ${'$'}2)); if (index(c, t "/") == 1 || c == t || index(c, t " ") == 1) print ${'$'}1 }'
    fi
}
closeall() {
    for p in ${'$'}(others "${'$'}1"); do kill "${'$'}p" 2>/dev/null; done
    n=0
    while [ -n "${'$'}(others "${'$'}1")" ] && [ "${'$'}n" -lt 50 ]; do n=${'$'}((n + 1)); sleep 0.1; done
    for p in ${'$'}(others "${'$'}1"); do kill -9 "${'$'}p" 2>/dev/null; done
}
n=0
while kill -0 "${'$'}pid" 2>/dev/null; do
    n=${'$'}((n + 1))
    if [ "${'$'}n" -gt 6000 ]; then echo "fail the app did not close" > "${'$'}result"; exit 1; fi
    sleep 0.1
done
closeall "${'$'}target"
backup="${'$'}target.previous"
rm -rf "${'$'}backup"
launch() {
    case "${'$'}kind" in
        mac) open "${'$'}1" ;;
        tarball) nohup "${'$'}1/bin/absentmotion.sh" >/dev/null 2>&1 & ;;
        appimage) nohup "${'$'}1" >/dev/null 2>&1 & ;;
    esac
}
if [ "${'$'}kind" = mac ]; then xattr -dr com.apple.quarantine "${'$'}staged" 2>/dev/null; fi
if err=${'$'}(mv "${'$'}target" "${'$'}backup" 2>&1) && err=${'$'}(mv "${'$'}staged" "${'$'}target" 2>&1); then
    echo "ok" > "${'$'}result"
    if [ "${'$'}kind" = tarball ]; then "${'$'}target/install.sh" >/dev/null 2>&1; fi
    rm -rf "${'$'}backup"
    if [ -n "${'$'}cleanup" ]; then rm -rf "${'$'}cleanup"; fi
    launch "${'$'}target"
else
    if [ ! -e "${'$'}target" ]; then mv "${'$'}backup" "${'$'}target"; fi
    if [ -n "${'$'}cleanup" ]; then rm -rf "${'$'}cleanup"; fi
    case "${'$'}err" in
        *"not permitted"*) echo "fail the system did not allow the app to be replaced" > "${'$'}result" ;;
        *) echo "fail the new version could not be put in place" > "${'$'}result" ;;
    esac
    launch "${'$'}target"
fi
"""

internal const val DEB_INSTALL = """#!/bin/sh
pid="${'$'}1"; package="${'$'}2"; result="${'$'}3"; launcher="${'$'}4"
echo "started" > "${'$'}result"
others() {
    if [ -d /proc/self ]; then
        for d in /proc/[0-9]*; do
            p="${'$'}{d#/proc/}"
            [ "${'$'}p" = "${'$'}${'$'}" ] && continue
            case "${'$'}(readlink "${'$'}d/exe" 2>/dev/null)" in "${'$'}1"/*) echo "${'$'}p"; continue ;; esac
            if tr '\000' '\n' < "${'$'}d/environ" 2>/dev/null | grep -Fqx "APPIMAGE=${'$'}1"; then echo "${'$'}p"; fi
        done
    else
        ps -A -ww -o pid= -o command= | awk -v t="${'$'}1" -v me="${'$'}${'$'}" '${'$'}1 != me { c = substr(${'$'}0, index(${'$'}0, ${'$'}2)); if (index(c, t "/") == 1 || c == t || index(c, t " ") == 1) print ${'$'}1 }'
    fi
}
closeall() {
    for p in ${'$'}(others "${'$'}1"); do kill "${'$'}p" 2>/dev/null; done
    n=0
    while [ -n "${'$'}(others "${'$'}1")" ] && [ "${'$'}n" -lt 50 ]; do n=${'$'}((n + 1)); sleep 0.1; done
    for p in ${'$'}(others "${'$'}1"); do kill -9 "${'$'}p" 2>/dev/null; done
}
n=0
while kill -0 "${'$'}pid" 2>/dev/null; do
    n=${'$'}((n + 1))
    if [ "${'$'}n" -gt 6000 ]; then echo "fail the app did not close" > "${'$'}result"; exit 1; fi
    sleep 0.1
done
closeall "/opt/absentmotion"
if pkexec apt-get -o DPkg::Lock::Timeout=120 install -y "${'$'}package" >/dev/null 2>&1; then
    echo "ok" > "${'$'}result"
else
    echo "fail the package could not be installed" > "${'$'}result"
fi
nohup "${'$'}launcher" >/dev/null 2>&1 &
"""

internal fun windowsScript(pid: Long, msi: String, launcher: String, result: String, log: String): String {
    fun quoted(text: String) = "'" + text.replace("'", "''") + "'"
    return """${'$'}ErrorActionPreference = 'SilentlyContinue'
${'$'}pidToWait = $pid
${'$'}msi = ${quoted(msi)}
${'$'}launcher = ${quoted(launcher)}
${'$'}result = ${quoted(result)}
${'$'}log = ${quoted(log)}
Set-Content -LiteralPath ${'$'}result -Value 'started'
${'$'}expected = [IO.Path]::GetFileNameWithoutExtension(${'$'}launcher)
function Alive { ${'$'}p = Get-Process -Id ${'$'}pidToWait; return (${'$'}null -ne ${'$'}p -and ${'$'}p.ProcessName -eq ${'$'}expected) }
if (Alive) {
    Wait-Process -Id ${'$'}pidToWait -Timeout 600
    if (Alive) { Set-Content -LiteralPath ${'$'}result -Value 'fail the app did not close'; exit 1 }
}
${'$'}folder = (Split-Path -Parent ${'$'}launcher).TrimEnd('\') + '\'
function Leftovers { Get-Process | Where-Object { ${'$'}_.Path -and ${'$'}_.Path.StartsWith(${'$'}folder, [StringComparison]::OrdinalIgnoreCase) } }
Leftovers | ForEach-Object { ${'$'}_.CloseMainWindow() | Out-Null }
for (${'$'}i = 0; ${'$'}i -lt 50 -and (Leftovers); ${'$'}i++) { Start-Sleep -Milliseconds 100 }
Leftovers | Stop-Process -Force
${'$'}run = Start-Process -FilePath 'msiexec.exe' -ArgumentList @('/i', ('"' + ${'$'}msi + '"'), ('INSTALLDIR="' + ${'$'}folder + '"'), '/passive', '/norestart', '/L*v', ('"' + ${'$'}log + '"')) -Wait -PassThru
Set-Content -LiteralPath ${'$'}result -Value ('exit ' + ${'$'}run.ExitCode)
if (-not (Test-Path -LiteralPath ${'$'}launcher)) {
    ${'$'}entry = Get-ChildItem 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Uninstall', 'HKLM:\Software\Microsoft\Windows\CurrentVersion\Uninstall' | Get-ItemProperty | Where-Object { ${'$'}_.DisplayName -eq 'AbsentMotion' -and ${'$'}_.InstallLocation } | Select-Object -First 1
    if (${'$'}entry) { ${'$'}launcher = Join-Path ${'$'}entry.InstallLocation (Split-Path -Leaf ${'$'}launcher) }
}
if (Test-Path -LiteralPath ${'$'}launcher) { Start-Process -FilePath ${'$'}launcher } else { Set-Content -LiteralPath ${'$'}result -Value 'fail the app was installed but could not be found to open' }
"""
}

internal fun windowsCommand(script: String): List<String> =
    listOf("powershell.exe", "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden", "-ExecutionPolicy", "Bypass", "-EncodedCommand",
        java.util.Base64.getEncoder().encodeToString(script.toByteArray(Charsets.UTF_16LE)))

internal class PendingInstall(val command: List<String>, val result: File? = null, private val leftovers: List<File> = emptyList()) {
    fun discard() = leftovers.forEach { runCatching { UpdateInstall.clear(it) } }
}

internal object UpdateInstall {
    private var helper: Process? = null
    private var pending: PendingInstall? = null
    val armed: Boolean get() = pending != null

    fun supports(file: File, site: InstallSite? = installSite()): Boolean {
        val name = file.name.lowercase()
        return when (site?.kind) {
            InstallKind.Msi -> name.endsWith(".msi")
            InstallKind.Setup -> name.endsWith(".ampack")
            InstallKind.MacApp -> name.endsWith("-update.zip")
            InstallKind.Tarball -> name.endsWith(".tar.gz")
            InstallKind.AppImage -> name.endsWith(".appimage")
            InstallKind.Deb -> name.endsWith(".deb") && File("/usr/bin/pkexec").canExecute()
            else -> false
        }
    }

    fun prepare(file: File, release: Release, site: InstallSite, work: File, result: File, pid: Long = ProcessHandle.current().pid()): PendingInstall {
        result.delete()
        work.mkdirs()
        return when (site.kind) {
            InstallKind.Msi -> PendingInstall(windowsCommand(windowsScript(pid, file.path, site.target.path, result.path, File(work, INSTALL_LOG).path)), result)
            InstallKind.Setup -> {
                val setup = File(work, SETUP_EXE)
                File(site.target.parentFile, SETUP_EXE).copyTo(setup, overwrite = true)
                run(listOf(setup.path, "--probe"))
                PendingInstall(listOf(setup.path, "--update", "--payload", file.path, "--wait-pid", pid.toString(), "--dir", site.target.parentFile.path,
                    "--result", result.path, "--log", File(work, INSTALL_LOG).path), result, listOf(setup))
            }
            InstallKind.MacApp -> {
                val staging = File(site.target.parentFile, ".AbsentMotion-update")
                clear(staging)
                if (!staging.mkdirs()) throw IOException("The update folder could not be made next to the app")
                try {
                    run(listOf("/usr/bin/ditto", "-x", "-k", file.path, staging.path))
                    val app = staging.listFiles().orEmpty().singleOrNull { it.name.endsWith(".app") } ?: throw IOException("The update has no single app inside")
                    run(listOf("/usr/bin/codesign", "--verify", "--deep", "--strict", app.path))
                    val wanted = plistValue(File(site.target, "Contents/Info.plist"), "CFBundleIdentifier")
                    if (plistValue(File(app, "Contents/Info.plist"), "CFBundleIdentifier") != wanted) throw IOException("The update is another app")
                    if (plistValue(File(app, "Contents/Info.plist"), "CFBundleShortVersionString") != release.version) throw IOException("The update is another version")
                    swap(work, pid, app, site.target, "mac", result, staging, listOf(staging))
                } catch (e: Exception) {
                    runCatching { clear(staging) }
                    throw e
                }
            }
            InstallKind.Tarball -> {
                val staging = File(site.target.parentFile, ".absentmotion-update")
                clear(staging)
                if (!staging.mkdirs()) throw IOException("The update folder could not be made")
                try {
                    run(listOf("tar", "-xzf", file.path, "-C", staging.path, "--no-same-owner"))
                    val app = File(staging, "AbsentMotion")
                    if (!File(app, "bin/AbsentMotion").canExecute() || !File(app, "install.sh").isFile) throw IOException("The update is not an AbsentMotion folder")
                    swap(work, pid, app, site.target, "tarball", result, staging, listOf(staging))
                } catch (e: Exception) {
                    runCatching { clear(staging) }
                    throw e
                }
            }
            InstallKind.AppImage -> {
                val staged = File(site.target.parentFile, site.target.name + ".new")
                try {
                    Files.deleteIfExists(staged.toPath())
                    file.copyTo(staged, overwrite = true)
                    if (!staged.setExecutable(true, false)) throw IOException("The update could not be made executable")
                    swap(work, pid, staged, site.target, "appimage", result, null, listOf(staged))
                } catch (e: Exception) {
                    runCatching { Files.deleteIfExists(staged.toPath()) }
                    throw e
                }
            }
            InstallKind.Deb -> {
                val script = File(work, "update-apply.sh").apply { writeText(DEB_INSTALL); setExecutable(true) }
                PendingInstall(listOf("/bin/sh", script.path, pid.toString(), file.path, result.path, File(site.target, "bin/AbsentMotion").path), result)
            }
        }
    }

    fun arm(install: PendingInstall): String? {
        disarm()
        pending = install
        return rearm()
    }

    fun rearm(): String? {
        val install = pending ?: return null
        if (helper?.isAlive == true) return null
        install.result?.let { runCatching { it.delete() } }
        val process = try {
            ProcessBuilder(detached(install.command)).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
        } catch (e: IOException) {
            return startFailure(e.message.orEmpty())
        }
        helper = process
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < until) {
            if (install.result?.let { runCatching { it.readText() }.getOrNull() }?.isNotBlank() == true) return null
            if (!process.isAlive) return failureOf(install.result?.let { runCatching { it.readText() }.getOrNull() }.orEmpty()) ?: "the update helper was blocked"
            Thread.sleep(100)
        }
        return null
    }

    fun disarm() {
        helper?.let { if (it.isAlive) it.destroy() }
        helper = null
    }

    private fun detached(command: List<String>): List<String> = when {
        File("/usr/bin/setsid").canExecute() -> listOf("/usr/bin/setsid", "/usr/bin/nohup") + command
        File("/usr/bin/nohup").canExecute() -> listOf("/usr/bin/nohup") + command
        else -> command
    }

    fun startFailure(message: String): String {
        val code = Regex("error=(\\d+)").find(message)?.groupValues?.get(1)?.toIntOrNull()
        return if (code in setOf(2, 5, 225, 226, 1260, 4551)) "the update helper was blocked" else "the helper did not start"
    }

    fun cancel() {
        disarm()
        pending?.discard()
        pending = null
    }

    fun lastFailure(result: File): String? {
        val text = runCatching { result.readText() }.getOrNull() ?: return null
        result.delete()
        return failureOf(text)
    }

    fun failureOf(raw: String): String? {
        val text = raw.lines().map { it.trim() }.lastOrNull { it.isNotEmpty() }.orEmpty()
        return when {
            text.isEmpty() || text == "ok" || text.startsWith("ok ") -> null
            text == "started" || text.startsWith("stage") -> "the updater was stopped"
            text.startsWith("fail") -> text.removePrefix("fail").trim().ifEmpty { "unknown" }
            text.startsWith("exit") -> text.removePrefix("exit").trim().takeIf { it !in setOf("0", "3010") }?.let { "installer code " + it.ifEmpty { "unknown" } }
            else -> "unknown"
        }
    }

    private fun swap(work: File, pid: Long, staged: File, target: File, kind: String, result: File, cleanup: File?, leftovers: List<File>): PendingInstall {
        val script = File(work, "update-apply.sh").apply { writeText(POSIX_SWAP); setExecutable(true) }
        return PendingInstall(listOf("/bin/sh", script.path, pid.toString(), staged.path, target.path, kind, result.path, cleanup?.path.orEmpty()), result, leftovers)
    }

    fun clear(dir: File) {
        val path = dir.toPath()
        if (Files.isSymbolicLink(path)) Files.delete(path) else if (dir.exists()) run(listOf("/bin/rm", "-rf", dir.path))
    }

    private fun plistValue(plist: File, key: String): String = run(listOf("/usr/libexec/PlistBuddy", "-c", "Print :$key", plist.path)).trim()

    private fun run(command: List<String>): String {
        val log = File.createTempFile("am-update", ".log")
        try {
            val process = ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log).start()
            if (!process.waitFor(300, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                throw IOException("${command.first()} took too long")
            }
            val output = log.readText()
            if (process.exitValue() != 0) throw IOException("${File(command.first()).name} failed: ${output.take(200)}")
            return output
        } finally {
            log.delete()
        }
    }
}
