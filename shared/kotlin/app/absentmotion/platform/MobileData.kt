package app.absentmotion.platform

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.absentmotion.R
import app.absentmotion.i18n.AppText
import java.io.IOException
import java.util.concurrent.CountDownLatch

class MobileDataRefused : IOException(AppText.get(R.string.mobile_data_refused))

object MobileData {
    const val KEY = "askMobileData"

    class Question {
        internal val answered = CountDownLatch(1)
        @Volatile internal var yes = false
        internal var waiters = 0
    }

    @Volatile private var context: Context? = null
    @Volatile private var allowed = false

    var question by mutableStateOf<Question?>(null)
        private set

    fun install(context: Context) {
        this.context = context.applicationContext
    }

    private fun cellular(context: Context): Boolean = runCatching {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) && !caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
            !caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }.getOrDefault(false)

    fun check(quiet: Boolean = false) {
        val app = context ?: return
        if (allowed || !app.getSharedPreferences("settings", Context.MODE_PRIVATE).getBoolean(KEY, true) || !cellular(app)) return
        if (quiet || Looper.myLooper() == Looper.getMainLooper()) throw MobileDataRefused()
        val asked = synchronized(this) {
            (question ?: Question().also { question = it }).also { it.waiters++ }
        }
        try {
            asked.answered.await()
            if (asked.yes) allowed = true else throw MobileDataRefused()
        } finally {
            synchronized(this) {
                if (--asked.waiters == 0 && question === asked) question = null
            }
        }
    }

    fun answer(yes: Boolean) {
        val asked = synchronized(this) { question.also { question = null } } ?: return
        asked.yes = yes
        asked.answered.countDown()
    }
}
