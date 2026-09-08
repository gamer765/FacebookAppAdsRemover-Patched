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
import java.lang.reflect.Method
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Experimental entry point that avoids relying on Facebook's obfuscated X.* names.
 *
 * The normal v1.9.5 Module entry point is intentionally NOT loaded by xposed_init on this
 * branch. All features are installed through the generic structural / stable-string resolver
 * in Patches.kt, while the video/Reels network blockers below are resolved directly from
 * stable bytecode strings so they can be installed as soon as Facebook's secondary dex is ready.
 */
class ObfuscationResistantAllFeaturesHooks : IXposedHookLoadPackage {

    companion object {
        private const val TAG = "FBAdsObfAll"
        private const val HOST_PACKAGE = "com.facebook.katana"
        private const val MAX_FULL_SCAN_ATTEMPTS = 10

        private val attachHookInstalled = AtomicBoolean(false)
        private val dexReadyHookInstalled = AtomicBoolean(false)
        private val dexKitLoaded = AtomicBoolean(false)
        private val fullScanInProgress = AtomicBoolean(false)
        private val fullHooksInstalled = AtomicBoolean(false)
        private val scanAttempts = AtomicInteger(0)
        private val hookedStableMethods: MutableSet<Method> =
            Collections.synchronizedSet(HashSet())
        private val stableLabels: MutableSet<String> = ConcurrentHashMap.newKeySet()

        @Volatile
        private var application: Application? = null

        private data class StableTarget(
            val label: String,
            val returnType: String,
            val paramCount: Int?,
            val strings: Array<String>
        )

        private val stableVideoTargets = listOf(
            StableTarget(
                "reels-dispatch",
                "void",
                null,
                arrayOf("reels_ad_query_send")
            ),
            StableTarget(
                "reels-primary-query",
                "void",
                null,
                arrayOf("FBFetchReelsVideoAdsQuery", "reels_ad_query_send")
            ),
            StableTarget(
                "commercial-break",
                "void",
                null,
                arrayOf("commercial_break_query_send", "Kicking off video ad fetch")
            ),
            StableTarget(
                "adbreak-server",
                "void",
                null,
                arrayOf("AdBreakServerAPI", "Fetch adbreak when already fetching")
            ),
            StableTarget(
                "profile-reels",
                "void",
                null,
                arrayOf("ProfileReelsAsyncAdsQuery")
            ),
            StableTarget(
                "reels-prefetch",
                "void",
                null,
                arrayOf("ReelsAdsBackgroundPrefetchInitializer", "ReelsAdsPeriodicPrefetch")
            ),
            StableTarget(
                "async-ad-channel",
                "void",
                2,
                arrayOf("VideoHomeCSRNetworkRequester.doAdChannelNetworkRequest")
            ),
            StableTarget(
                "rti-interstitial",
                "java.lang.Object",
                0,
                arrayOf("IMMERSIVE_REAL_TIME_INTENT")
            ),
            StableTarget(
                "poe-interstitial",
                "java.lang.Object",
                0,
                arrayOf("POE_TRIGGERED_INTERSTITIAL")
            ),
            StableTarget(
                "similar-reels-ad",
                "java.lang.Object",
                0,
                arrayOf("fb_shorts_similar_ad")
            )
        )

        private fun ensureDexKitLoaded() {
            if (dexKitLoaded.get()) return
            synchronized(dexKitLoaded) {
                if (dexKitLoaded.get()) return
                System.loadLibrary("dexkit")
                dexKitLoaded.set(true)
            }
        }

        private fun hookStableMethod(label: String, method: Method): Boolean {
            if (!hookedStableMethods.add(method)) {
                stableLabels.add(label)
                return false
            }
            method.isAccessible = true
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    param.result = null
                    Log.i(TAG, "Blocked stable target $label ${method.declaringClass.name}.${method.name}")
                }
            })
            stableLabels.add(label)
            Log.i(TAG, "Installed stable target $label ${method.declaringClass.name}.${method.name}")
            return true
        }

        private fun installStableVideoTargets(
            bridge: DexKitBridge,
            classLoader: ClassLoader
        ): Int {
            var installed = 0
            stableVideoTargets.forEach targetLoop@ { target ->
                val results = try {
                    bridge.findMethod {
                        matcher {
                            usingStrings(*target.strings)
                            returnType = target.returnType
                            target.paramCount?.let { paramCount = it }
                        }
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "Stable target scan failed label=${target.label}", t)
                    return@targetLoop
                }

                results.forEach methodLoop@ { methodData ->
                    val method = runCatching {
                        methodData.getMethodInstance(classLoader)
                    }.getOrNull() ?: return@methodLoop
                    if (method.name == "<init>" || method.name == "<clinit>") return@methodLoop
                    if (hookStableMethod(target.label, method)) installed++
                }
            }
            return installed
        }

        private fun installEarlyFrameworkGuards() {
            runCatching { installGameAdJavascriptInterfaceBridgeHook() }
                .onFailure { Log.w(TAG, "Early game-ad bridge hook failed", it) }
            runCatching { installGlobalAdSurfaceFallbacksEarly() }
                .onFailure { Log.w(TAG, "Early surface fallback hook failed", it) }
        }

        @JvmStatic
        fun tryInstallAllFeatures(classLoader: ClassLoader, reason: String) {
            if (fullHooksInstalled.get()) return
            if (scanAttempts.get() >= MAX_FULL_SCAN_ATTEMPTS) return
            if (!fullScanInProgress.compareAndSet(false, true)) return
            scanAttempts.incrementAndGet()

            Thread({
                try {
                    ensureDexKitLoaded()
                    val bridge = DexKitBridge.create(classLoader, true)
                    try {
                        val stableInstalled = installStableVideoTargets(bridge, classLoader)
                        val fullInstalled = runCatching {
                            installFacebookAdRemover(classLoader, bridge)
                        }.getOrElse {
                            Log.w(TAG, "Generic all-feature resolver failed reason=$reason", it)
                            false
                        }
                        Log.i(
                            TAG,
                            "Resolver reason=$reason stableInstalled=$stableInstalled " +
                                "stableLabels=${stableLabels.sorted()} fullInstalled=$fullInstalled"
                        )
                        if (fullInstalled) {
                            fullHooksInstalled.set(true)
                        }
                    } finally {
                        bridge.close()
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "All-feature resolver pass failed reason=$reason", t)
                } finally {
                    fullScanInProgress.set(false)
                }
            }, "FBAdsObfAllScan").start()
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
                                val configuredLoader =
                                    (param.thisObject as? ClassLoader) ?: classLoader
                                tryInstallAllFeatures(configuredLoader, "MultiDex.configure")
                            }
                        })
                    }
                Log.i(TAG, "Waiting for Facebook secondary dex readiness")
            }.onFailure {
                Log.w(TAG, "Unable to hook Facebook MultiDex readiness", it)
            }
        }

        private fun scheduleRetries(classLoader: ClassLoader) {
            val handler = Handler(Looper.getMainLooper())
            val delays = longArrayOf(0L, 100L, 350L, 900L, 2_000L, 4_000L, 7_500L, 12_000L)
            delays.forEachIndexed { index, delay ->
                handler.postDelayed(
                    {
                        tryInstallAllFeatures(classLoader, "attach-retry-${index + 1}")
                    },
                    delay
                )
            }
        }
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != HOST_PACKAGE) return

        hookDexReadiness(lpparam.classLoader)
        if (!attachHookInstalled.compareAndSet(false, true)) return

        val attach = Application::class.java.getDeclaredMethod("attach", Context::class.java)
        attach.isAccessible = true
        XposedBridge.hookMethod(attach, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val app = param.thisObject as? Application ?: return
                application = app
                installEarlyFrameworkGuards()
                scheduleRetries(app.classLoader)
                Log.i(TAG, "Obfuscation-resistant all-feature entry point attached")
            }
        })
    }
}
