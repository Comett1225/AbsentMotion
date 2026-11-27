package app.absentmotion.platform

import android.content.Context
import android.hardware.input.InputManager
import android.os.Build
import android.view.InputDevice

object Peripherals {
    @Volatile var keyboard = false
        private set
    @Volatile var mouse = false
        private set

    fun landscapeFree(multiWindow: Boolean, tablet: Boolean, keyboard: Boolean, mouse: Boolean): Boolean =
        multiWindow || tablet || keyboard && mouse

    fun refresh(context: Context) {
        val manager = context.getSystemService(InputManager::class.java) ?: return
        var typing = false
        var pointing = false
        for (id in manager.inputDeviceIds) {
            val device = manager.getInputDevice(id) ?: continue
            if (device.isVirtual || Build.VERSION.SDK_INT >= 29 && !device.isExternal) continue
            if (device.keyboardType == InputDevice.KEYBOARD_TYPE_ALPHABETIC) typing = true
            if (device.supportsSource(InputDevice.SOURCE_MOUSE) || device.supportsSource(InputDevice.SOURCE_TOUCHPAD)) pointing = true
        }
        keyboard = typing
        mouse = pointing
    }
}
