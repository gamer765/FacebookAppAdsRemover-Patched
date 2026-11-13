package tn.loukious.facebookappadsremover.hooks

import android.app.Application
import android.os.Build
import android.view.WindowManager
import tn.loukious.facebookappadsremover.core.L
import tn.loukious.facebookappadsremover.core.Settings
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedInterface.Hooker
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * Privacy hooks — ports of the two original-mod privacy features, both fully
 * reversed from libnc.so.c on 2026-09-01:
 *
 *  1. Allow screenshots & screen recording (mod: privacy.disable_flag_secure)
 *
 *     Mod evidence — POfkQkVp4fGmEBm6kmt3.run (libnc.so.c 233165–233380):
 *
 *         Activity a = POfkQkVp4fGmEBm6kmt3.T7PucPwlExzHkx17YkC;   // field
 *         a.getWindow().clearFlags(0x2000 /* WindowManager.FLAG_SECURE */);
 *         UyWiSSEKVNjiXdQxS0ju.T7PucPwlExzHkx17YkC(a.getWindow().getDecorView());
 *
 *     Instantiated per activity (from the settings toggle
 *     cunKcUKP1NjaABI4G7Qe$jAxcmxMQHwL30UotfsBv.onCheckedChanged @1106674 and
 *     from oRfibdnyvv3dEUIoNSY0$kRbFm7USGDhCvFgJ8BvY.run @1303701). The
 *     decorView hand-off feeds the mod's view-tree helper and is UI-only.
 *     Port: clear the flag on every activity resume via lifecycle callbacks —
 *     same per-activity effect, no hook needed.
 *
 *  2. Block screenshot/recording detection (mod: privacy.capture_detection,
 *     pref app.telegram.bemai3012_blockScreenCaptureDetection)
 *
 *     Mod evidence — JUmLsXwFN0AQKajIphb6 = the "HookScreenCaptureDetection"
 *     installer (libnc.so.c 165248–168000, strings DAT_00fdd058/00fdcf58/
 *     00fdcf98/00fdd1d8/00fdd198/00fdd158): it hooks
 *       - ScreenRecordingCallbacks.addCallback / notifyCallbacks
 *         ("[HookScreenCaptureDetection] … ScreenRecordingCallbacks:")
 *       - FileObserver.onEvent / startWatching and
 *         android.os.FileObserver$ObserverThread
 *     plus a ContentObserver.dispatchChange hook installed from
 *     oRfibdnyvv3dEUIoNSY0$UVFD3BoJI5sq4SwfZZu0.run (1298470). That split
 *     matches the mod's own summary string: "Android 14+ system callbacks are
 *     blocked; older Android also blocks common MediaStore/FileObserver
 *     detection."
 *
 *     Ported here: startWatching + onEvent (framework android.os.FileObserver)
 *     and the ScreenRecordingCallbacks methods when the platform class exists
 *     (API 35+; the device runs Android 16). Both block by skipping
 *     chain.proceed(), exactly what the mod's gate-then-return hooks did.
 *
 *     NOT ported: the ContentObserver.dispatchChange hook — the mod's filter
 *     inside it (which observer/uri it gates on) is not decrypted yet, and
 *     blocking every dispatchChange process-wide would break unrelated FB
 *     observers. Revisit after decrypting libnc 1298470–1299500 further
 *     (see docs/feature-matrix.md §10).
 *
 * Both toggles default OFF (opt-in, safe); the mod's defaults are unknown —
 * its pref reader defaults live as native constants not yet decrypted.
 */
object PrivacyHook {

    private const val TAG = "FBAR.Privacy"

    private var detectionInstalled = false

    /** Application-level setup (lifecycle callbacks for the FLAG_SECURE path). */
    fun init(app: Application) {
        val allowCapture = Settings.getBoolean(Settings.PRIVACY_ALLOW_CAPTURE, false)
        if (!allowCapture) return

        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            // The mod cleared FLAG_SECURE per activity; resume is the moment a
            // window re-applies its flags, so clear there.
            override fun onActivityResumed(activity: android.app.Activity) {
                runCatching {
                    activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                }
            }

            override fun onActivityCreated(a: android.app.Activity, b: android.os.Bundle?) {}
            override fun onActivityStarted(a: android.app.Activity) {}
            override fun onActivityPaused(a: android.app.Activity) {}
            override fun onActivityStopped(a: android.app.Activity) {}
            override fun onActivitySaveInstanceState(a: android.app.Activity, b: android.os.Bundle) {}
            override fun onActivityDestroyed(a: android.app.Activity) {}
        })
        L.i(TAG, "FLAG_SECURE cleared per activity (screenshots/recording allowed)")
    }

    /**
     * Installs the framework hooks for the capture-detection block. All
     * targets are boot-classloader classes, so this needs no DexKit and can
     * run as soon as the app is created.
     *
     * @return true when the toggle is on and at least one hook armed.
     */
    fun install(module: XposedInterface): Boolean {
        if (!Settings.getBoolean(Settings.PRIVACY_BLOCK_DETECTION, false)) return false
        if (detectionInstalled) return true

        var armed = 0

        // android.os.FileObserver — FB's screenshot watcher subclasses it.
        // startWatching is the concrete registration entry (subclass instances
        // dispatch to it); blocking it starves the observer of events. onEvent
        // is also hooked like the mod did — it fires for any observer whose
        // dispatch goes through the base declaration.
        runCatching {
            val fo = Class.forName("android.os.FileObserver")
            for (m in fo.declaredMethods) {
                if (Modifier.isAbstract(m.modifiers)) continue
                if (m.name == "startWatching" || m.name == "onEvent") {
                    module.hook(m).intercept(BlockHooker)
                    armed++
                }
            }
        }.onFailure { L.w(TAG, "FileObserver hook failed", it) }

        // android.view.ScreenRecordingCallbacks (API 35+) — the Android 14+
        // "system callbacks" arm of the mod's installer.
        if (Build.VERSION.SDK_INT >= 35) {
            runCatching {
                val src = Class.forName("android.view.ScreenRecordingCallbacks")
                for (m in src.declaredMethods) {
                    if (Modifier.isAbstract(m.modifiers)) continue
                    if (m.name == "addCallback" || m.name == "notifyCallbacks") {
                        module.hook(m).intercept(BlockHooker)
                        armed++
                    }
                }
            }.onFailure { L.w(TAG, "ScreenRecordingCallbacks hook unavailable", it) }
        }

        detectionInstalled = armed > 0
        L.i(TAG, "capture-detection block: $armed framework hook(s) armed")
        return detectionInstalled
    }

    /** Skips the original call — the mod's hooks gated then returned early. */
    private object BlockHooker : Hooker {
        override fun intercept(chain: XposedInterface.Chain): Any? {
            // no chain.proceed(): the observed call is swallowed
            return null
        }
    }
}
