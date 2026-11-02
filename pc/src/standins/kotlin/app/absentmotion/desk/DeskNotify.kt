package app.absentmotion.desk

import java.awt.Taskbar

object DeskNotify {
    internal fun macCommand(title: String, text: String): List<String> =
        listOf("osascript", "-e", "on run argv", "-e", "display notification (item 1 of argv) with title (item 2 of argv)", "-e", "end run", text, title)

    internal fun linuxCommand(title: String, text: String): List<String> = listOf("notify-send", "--app-name=AbsentMotion", title, text)

    fun show(title: String, text: String) {
        Thread {
            runCatching {
                when {
                    DeskOs.mac -> ProcessBuilder(macCommand(title, text)).redirectErrorStream(true).start().waitFor()
                    DeskOs.windows -> tray(title, text)
                    else -> if (runCatching { ProcessBuilder(linuxCommand(title, text)).start().waitFor() == 0 }.getOrDefault(false).not()) tray(title, text)
                }
            }
        }.apply { isDaemon = true }.start()
    }

    private fun tray(title: String, text: String) {
        if (!java.awt.SystemTray.isSupported()) return
        val tray = java.awt.SystemTray.getSystemTray()
        val image = java.awt.image.BufferedImage(16, 16, java.awt.image.BufferedImage.TYPE_INT_ARGB).apply {
            createGraphics().apply { color = java.awt.Color(0xFF, 0x8A, 0x00); fillRoundRect(0, 0, 16, 16, 6, 6); dispose() }
        }
        val icon = java.awt.TrayIcon(image, "AbsentMotion")
        tray.add(icon)
        icon.displayMessage(title, text, java.awt.TrayIcon.MessageType.INFO)
        Thread.sleep(8000)
        tray.remove(icon)
    }
}

object DeskTaskbar {
    private val taskbar: Taskbar? = runCatching { Taskbar.getTaskbar().takeIf { Taskbar.isTaskbarSupported() } }.getOrNull()

    fun progress(percent: Int?) {
        val bar = taskbar ?: return
        runCatching {
            if (bar.isSupported(Taskbar.Feature.PROGRESS_VALUE)) bar.setProgressValue(percent ?: -1)
            if (bar.isSupported(Taskbar.Feature.ICON_BADGE_TEXT)) bar.setIconBadge(percent?.let { "$it%" })
            val window = DeskFiles.window
            if (window != null && bar.isSupported(Taskbar.Feature.PROGRESS_STATE_WINDOW)) {
                bar.setWindowProgressState(window, if (percent == null) Taskbar.State.OFF else Taskbar.State.NORMAL)
                if (percent != null && bar.isSupported(Taskbar.Feature.PROGRESS_VALUE_WINDOW)) bar.setWindowProgressValue(window, percent)
            }
        }
    }

    fun attention() {
        val bar = taskbar ?: return
        runCatching { if (bar.isSupported(Taskbar.Feature.USER_ATTENTION)) bar.requestUserAttention(true, false) }
    }
}
