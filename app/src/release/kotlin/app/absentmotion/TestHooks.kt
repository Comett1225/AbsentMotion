package app.absentmotion

import android.content.Intent

internal object TestHooks {
    fun onboarding(intent: Intent): String? = null

    fun newIntent(activity: MainActivity, intent: Intent) {}

    fun start(activity: MainActivity) {}
}
