package tn.loukious.facebookappadsremover.core

import android.util.Log
import tn.loukious.facebookappadsremover.ModuleMain
import io.github.libxposed.api.XposedModule

/**
 * Dual logging: logcat (readable via `adb logcat -s FacebookAppAdsRemover FBAR.Discovery`)
 * plus the framework's module log. The libxposed log implementation is
 * framework-dependent (some builds route it to a file we cannot reach from
 * the host), so logcat is the primary channel for on-device validation.
 */
object L {

    @JvmStatic
    var module: ModuleMain? = null

    fun i(tag: String, msg: String) {
        Log.i(tag, msg)
        module?.log(Log.INFO, tag, msg)
    }

    fun w(tag: String, msg: String, tr: Throwable? = null) {
        Log.w(tag, msg, tr)
        module?.let { if (tr != null) it.log(Log.WARN, tag, msg, tr) else it.log(Log.WARN, tag, msg) }
    }

    fun e(tag: String, msg: String, tr: Throwable? = null) {
        Log.e(tag, msg, tr)
        module?.let { if (tr != null) it.log(Log.ERROR, tag, msg, tr) else it.log(Log.ERROR, tag, msg) }
    }
}
