package tn.loukious.facebookappadsremover

import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage
import org.luckypray.dexkit.DexKitBridge
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Strict all-feature experiment.
 *
 * The normal Module entry point is not loaded on this branch. Instead we:
 *  1. run the version-independent stable-string Reels/video resolver,
 *  2. install framework-only hooks that do not depend on Facebook X.* names,
 *  3. run the existing broad DexKit/structural resolver for Feed, Stories,
 *     Marketplace, Reels, video, instant-game ads and related surfaces.
 *
 * CI strips the exact 576/577 fast-path call from installFacebookAdRemover(),
 * so a successful test is not rescued by the hard-coded X.* mappings.
 */
class ObfuscationResistantAllHooks : IXposedHookLoadPackage {

    companion object {
        private const val TAG = "FBAdsObfAll"
        private const val HOST_PACKAGE = "com.facebook.katana"
        private const val MAX_FULL_SCAN_ATTEMPTS = 6

        private val attachHookInstalled = AtomicBoolean(false)
        private val dexReadyHookInstalled = AtomicBoolean(false)
        private val dexKitLoaded = AtomicBoolean(false)
        private val fullScanInProgress = AtomicBoolean(false)
        private val fullHooksInstalled = AtomicBoolean(false)
        private val fullScanAttempts = AtomicInteger(0)

        @Volatile
        private var application: Application? = null

        private fun ensureDexKitLoaded() {
            if (dexKitLoaded.get()) return
            synchronized(dexKitLoaded) {
                if (dexKitLoaded.get()) return
                System.loadLibrary("dexkit")
                dexKitLoaded.set(true)
            }
        }

        private fun hostVersionName(app: Application): String {
            return runCatching {
                app.packageManager.getPackageInfo(app.packageName, 0).versionName.orEmpty()
            }.getOrDefault("")
        }

        private fun installCachedGenericTargets(app: Application, classLoader: ClassLoader) {
            val version = hostVersionName(app)
            runCatching {
                val count = loadCachedFeedGuardCandidates(app, classLoader, version)
                if (count > 0) Log.i(TAG, "Loaded $count cached feed target(s)")
            }.onFailure { Log.w(TAG, "Cached feed target restore failed", it) }

            runCatching {
                installReelsGuardFromCache(app, classLoader, version)
            }.onFailure { Log.w(TAG, "Cached Reels target restore failed", it) }

            runCatching {
                installMarketplaceNetGuardFromCache(app, classLoader, version)
            }.onFailure { Log.w(TAG, "Cached Marketplace target restore failed", it) }
        }

        private fun saveGenericTargets() {
            val app = application ?: return
            val version = hostVersionName(app)
            runCatching { saveFeedGuardCandidateCache(app, version) }
                .onFailure { Log.w(TAG, "Feed target cache save failed", it) }
            runCatching { saveReelsGuardCache(app, version) }
                .onFailure { Log.w(TAG, "Reels target cache save failed", it) }
            runCatching { saveMarketplaceNetGuardCache(app, version) }
                .onFailure { Log.w(TAG, "Marketplace target cache save failed", it) }
        }

        private fun installFrameworkOnlyHooks() {
            runCatching { installGameAdJavascriptInterfaceBridgeHook() }
                .onFailure { Log.w(TAG, "Early game-ad bridge hook failed", it) }
            runCatching { installGlobalAdSurfaceFallbacksEarly() }
                .onFailure { Log.w(TAG, "Early semantic ad-surface hooks failed", it) }
        }

        private fun installFullGenericSuite(classLoader: ClassLoader, reason: String) {
            if (fullHooksInstalled.get()) return
            if (fullScanAttempts.get() >= MAX_FULL_SCAN_ATTEMPTS) return
            if (!fullScanInProgress.compareAndSet(false, true)) return

            val attempt = fullScanAttempts.incrementAndGet()
            Thread({
                try {
                    ensureDexKitLoaded()
                    val bridge = DexKitBridge.create(classLoader, true)
                    try {
                        val installed = installFacebookAdRemover(classLoader, bridge)
                        if (installed) {
                            fullHooksInstalled.set(true)
                            saveGenericTargets()
                            Log.i(
                                TAG,
                                "ALL-FEATURE generic resolver active reason=$reason attempt=$attempt"
                            )
                        } else {
                            Log.i(
                                TAG,
                                "All-feature scan deferred reason=$reason attempt=$attempt; secondary dex not ready"
                            )
                        }
                    } finally {
                        bridge.close()
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "All-feature generic scan failed reason=$reason attempt=$attempt", t)
                } finally {
                    fullScanInProgress.set(false)
                }
            }, "FBAdsAllFeatureScan-$attempt").start()
        }

        private fun hookDexReadiness(classLoader: ClassLoader) {
            if (!dexReadyHookInstalled.compareAndSet(false, true)) return
            runCatching {
                val multiDexClass = Class.forName(
                    "com.facebook.common.dextricks.MultiDexClassLoaderJava",
                    false,
                    classLoader
                )
                multiDexClass.declaredMethods
                    .filter { it.name == "configure" && it.parameterCount == 1 }
                    .forEach { method ->
                        method.isAccessible = true
                        XposedBridge.hookMethod(method, object : XC_MethodHook() {
                            override fun afterHookedMethod(param: MethodHookParam) {
                                val configuredLoader = (param.thisObject as? ClassLoader) ?: classLoader
                                ObfuscationResistantVideoHooks.installFocusedStableStringHooks(
                                    configuredLoader,
                                    "all-features MultiDex.configure"
                                )
                                installFullGenericSuite(configuredLoader, "MultiDex.configure")
                            }
                        })
                    }
            }.onFailure {
                Log.w(TAG, "Unable to hook Facebook MultiDex readiness", it)
            }
        }

        private fun scheduleRetries(classLoader: ClassLoader) {
            val handler = Handler(Looper.getMainLooper())
            val delays = longArrayOf(0L, 100L, 350L, 900L, 2_000L, 4_000L, 8_000L)
            delays.forEachIndexed { index, delay ->
                handler.postDelayed({
                    ObfuscationResistantVideoHooks.installFocusedStableStringHooks(
                        classLoader,
                        "all-features attach-retry-${index + 1}"
                    )
                    installFullGenericSuite(
                        classLoader,
                        "attach-retry-${index + 1}"
                    )
                }, delay)
            }
        }
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != HOST_PACKAGE) return

        // Reuse the strict stable-string video resolver. It contains no hard-coded X.* names.
        ObfuscationResistantVideoHooks().handleLoadPackage(lpparam)
        hookDexReadiness(lpparam.classLoader)

        if (!attachHookInstalled.compareAndSet(false, true)) return
        val attach = Application::class.java.getDeclaredMethod("attach", Context::class.java)
        attach.isAccessible = true
        XposedBridge.hookMethod(attach, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val app = param.thisObject as? Application ?: return
                application = app
                installCachedGenericTargets(app, app.classLoader)
                installFrameworkOnlyHooks()
                scheduleRetries(app.classLoader)
            }
        })
    }
}
