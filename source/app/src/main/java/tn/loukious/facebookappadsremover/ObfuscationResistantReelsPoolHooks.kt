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
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Supplemental version-independent Reels hooks discovered from Facebook 579.
 *
 * These hooks intentionally use only stable strings and method shape. No X.* class or
 * method names are hard-coded. They address update-sensitive paths that can escape the
 * main generic resolver:
 *
 *  - native sponsored stories being inserted into the Reels sponsored pool;
 *  - commercial-break requests whose logging markers were split across methods in 579;
 *  - Facebook 579's dedicated multi-ad media runnable, which can prepare a slideshow/grid
 *    Reels ad before the leaf renderer carries a classifiable AD/ADS_MIDCARD model.
 */
class ObfuscationResistantReelsPoolHooks : IXposedHookLoadPackage {

    companion object {
        private const val TAG = "FBAdsObfReelsPool"
        private const val HOST_PACKAGE = "com.facebook.katana"

        private val attachHookInstalled = AtomicBoolean(false)
        private val dexReadyHookInstalled = AtomicBoolean(false)
        private val dexKitLoaded = AtomicBoolean(false)
        private val scanInProgress = AtomicBoolean(false)
        private val sponsoredPoolInstalled = AtomicBoolean(false)
        private val commercialBreakInstalled = AtomicBoolean(false)
        private val multiAdsMediaInstalled = AtomicBoolean(false)
        private val hookedMethods: MutableSet<Method> = Collections.synchronizedSet(HashSet())

        private fun ensureDexKitLoaded() {
            if (dexKitLoaded.get()) return
            synchronized(dexKitLoaded) {
                if (dexKitLoaded.get()) return
                System.loadLibrary("dexkit")
                dexKitLoaded.set(true)
            }
        }

        private fun hookMethod(label: String, method: Method): Boolean {
            if (!hookedMethods.add(method)) return false
            method.isAccessible = true
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    param.result = null
                    Log.i(TAG, "Blocked $label ${method.declaringClass.name}.${method.name}")
                }
            })
            Log.i(TAG, "Installed $label ${method.declaringClass.name}.${method.name}")
            return true
        }

        private fun installSponsoredPoolBlock(bridge: DexKitBridge, classLoader: ClassLoader): Int {
            if (sponsoredPoolInstalled.get()) return 0
            var count = 0
            val results = bridge.findMethod {
                matcher {
                    usingStrings(
                        "after_model_added_to_pool",
                        "Cannot add null or non-sponsored story"
                    )
                    returnType = "void"
                    paramCount = 3
                }
            }
            results.forEach { methodData ->
                val method = runCatching { methodData.getMethodInstance(classLoader) }.getOrNull()
                    ?: return@forEach
                if (hookMethod("reels-sponsored-pool-add", method)) count++
            }
            if (count > 0) sponsoredPoolInstalled.set(true)
            return count
        }

        private fun installCommercialBreakBlock(bridge: DexKitBridge, classLoader: ClassLoader): Int {
            if (commercialBreakInstalled.get()) return 0
            var count = 0
            val results = bridge.findMethod {
                matcher {
                    // 576/577 placed this beside "Kicking off video ad fetch". In 579
                    // that second marker moved to another method, while this send marker
                    // stayed on both dedicated void request methods.
                    usingStrings("commercial_break_query_send")
                    returnType = "void"
                }
            }
            results.forEach { methodData ->
                val method = runCatching { methodData.getMethodInstance(classLoader) }.getOrNull()
                    ?: return@forEach
                if (hookMethod("commercial-break-send", method)) count++
            }
            if (count > 0) commercialBreakInstalled.set(true)
            return count
        }

        private fun installMultiAdsMediaBlock(bridge: DexKitBridge, classLoader: ClassLoader): Int {
            if (multiAdsMediaInstalled.get()) return 0
            var count = 0
            val results = bridge.findMethod {
                matcher {
                    // Facebook 579 dedicated Reels multi-ad media path. APK analysis shows
                    // this marker on a zero-arg runnable tied to the NativeSlideshow/MultiAds
                    // creative family. It is ad-specific, so blocking it does not require
                    // broadening the shared Reels classifier to MIDCARD/PARADE/UGC.
                    usingStrings("fb_reels_facebook_reels_multiads_media")
                    returnType = "void"
                    paramCount = 0
                }
            }
            results.forEach { methodData ->
                val method = runCatching { methodData.getMethodInstance(classLoader) }.getOrNull()
                    ?: return@forEach
                if (hookMethod("reels-multiads-media", method)) count++
            }
            if (count > 0) multiAdsMediaInstalled.set(true)
            return count
        }

        @JvmStatic
        fun install(classLoader: ClassLoader, reason: String) {
            if (
                sponsoredPoolInstalled.get() &&
                commercialBreakInstalled.get() &&
                multiAdsMediaInstalled.get()
            ) return
            if (!scanInProgress.compareAndSet(false, true)) return
            Thread({
                try {
                    ensureDexKitLoaded()
                    DexKitBridge.create(classLoader, true).use { bridge ->
                        val sponsored = installSponsoredPoolBlock(bridge, classLoader)
                        val commercial = installCommercialBreakBlock(bridge, classLoader)
                        val multiAds = installMultiAdsMediaBlock(bridge, classLoader)
                        Log.i(
                            TAG,
                            "scan reason=$reason sponsoredPool=$sponsored " +
                                "commercialBreak=$commercial multiAdsMedia=$multiAds"
                        )
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "supplemental Reels scan failed reason=$reason", t)
                } finally {
                    scanInProgress.set(false)
                }
            }, "FBAdsReelsPoolScan").start()
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
                                install(configuredLoader, "MultiDex.configure")
                            }
                        })
                    }
            }.onFailure {
                Log.w(TAG, "Unable to hook Facebook MultiDex readiness", it)
            }
        }

        private fun scheduleRetries(classLoader: ClassLoader) {
            val handler = Handler(Looper.getMainLooper())
            longArrayOf(0L, 100L, 350L, 900L, 2_000L, 4_000L, 8_000L).forEachIndexed { i, delay ->
                handler.postDelayed({ install(classLoader, "attach-retry-${i + 1}") }, delay)
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
                scheduleRetries(app.classLoader)
            }
        })
    }
}
