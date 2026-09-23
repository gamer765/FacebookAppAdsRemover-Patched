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
 * Facebook 579 follow-up for Reels ad request paths that changed shape after the original
 * universal stable-string hooks were written.
 *
 * Keep this layer below the UI/rendering boundary. Facebook reuses several Reels components
 * and helper wrappers between ads and organic Reels. Vector logs from exp10 showed the new
 * one-argument fb_shorts_similar_ad wrapper firing repeatedly on ordinary Reels while comments
 * were missing, so that wrapper is no longer blocked here. The older zero-arg ad-only resolver
 * remains in ObfuscationResistantVideoHooks.
 *
 * Remaining 579 gap blocks:
 *  - FBFetchReelsVideoAdsQuery methods whose signature changed from the old void dispatcher;
 *  - reels_ad_query_send Object-returning lambda/coroutine wrappers;
 *  - IMMERSIVE_REAL_TIME_INTENT one-argument void handoff.
 *
 * XposedBridge.log diagnostics are retained so Vector/LSPosed records installs and hits.
 */
class ObfuscationResistantReelsGapHooks : IXposedHookLoadPackage {

    companion object {
        private const val TAG = "FBAdsObfReelsGap"
        private const val HOST_PACKAGE = "com.facebook.katana"
        private const val MAX_SCAN_ATTEMPTS = 10

        private val attachHookInstalled = AtomicBoolean(false)
        private val dexReadyHookInstalled = AtomicBoolean(false)
        private val dexKitLoaded = AtomicBoolean(false)
        private val scanInProgress = AtomicBoolean(false)
        private val scanAttempts = AtomicInteger(0)
        private val hookedMethods: MutableSet<Method> = Collections.synchronizedSet(HashSet())
        private val installedLabels: MutableSet<String> = ConcurrentHashMap.newKeySet()

        private val desiredLabels = setOf(
            "fbfetch-reels-video-ads-query",
            "reels-query-object-wrapper",
            "rti-void-handoff"
        )

        private fun xlog(message: String) {
            Log.i(TAG, message)
            XposedBridge.log("$TAG: $message")
        }

        private fun xlog(message: String, throwable: Throwable) {
            Log.w(TAG, message, throwable)
            XposedBridge.log("$TAG: $message\n${Log.getStackTraceString(throwable)}")
        }

        private fun ensureDexKitLoaded() {
            if (dexKitLoaded.get()) return
            synchronized(dexKitLoaded) {
                if (dexKitLoaded.get()) return
                System.loadLibrary("dexkit")
                dexKitLoaded.set(true)
            }
        }

        private fun immediateNoAdFuture(method: Method): Any? {
            if (method.returnType.name != "com.google.common.util.concurrent.ListenableFuture") return null
            return runCatching {
                val futures = Class.forName(
                    "com.google.common.util.concurrent.Futures",
                    false,
                    method.declaringClass.classLoader
                )
                val immediateFuture = futures.getDeclaredMethod("immediateFuture", Any::class.java)
                immediateFuture.isAccessible = true
                immediateFuture.invoke(null, null)
            }.onFailure {
                xlog(
                    "Unable to create completed no-ad future for " +
                        "${method.declaringClass.name}.${method.name}",
                    it
                )
            }.getOrNull()
        }

        private fun safeResult(label: String, method: Method): Any? {
            if (
                label == "fbfetch-reels-video-ads-query" &&
                method.returnType.name == "com.google.common.util.concurrent.ListenableFuture"
            ) {
                immediateNoAdFuture(method)?.let { return it }
            }
            return when (method.returnType) {
                Void.TYPE -> null
                java.lang.Boolean.TYPE -> false
                java.lang.Byte.TYPE -> 0.toByte()
                java.lang.Character.TYPE -> '\u0000'
                java.lang.Short.TYPE -> 0.toShort()
                java.lang.Integer.TYPE -> 0
                java.lang.Long.TYPE -> 0L
                java.lang.Float.TYPE -> 0f
                java.lang.Double.TYPE -> 0.0
                else -> null
            }
        }

        private fun hookBlocked(label: String, method: Method): Boolean {
            if (!hookedMethods.add(method)) {
                installedLabels.add(label)
                return false
            }
            method.isAccessible = true
            XposedBridge.hookMethod(method, object : XC_MethodHook(10000) {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val result = safeResult(label, method)
                    val resultMode = if (
                        label == "fbfetch-reels-video-ads-query" &&
                        method.returnType.name == "com.google.common.util.concurrent.ListenableFuture" &&
                        result != null
                    ) "completed-no-ad-future" else "safe-default"
                    xlog("HIT $label ${method.declaringClass.name}.${method.name} result=$resultMode")
                    param.result = result
                }
            })
            installedLabels.add(label)
            xlog(
                "INSTALLED $label ${method.declaringClass.name}.${method.name}" +
                    " params=${method.parameterCount} return=${method.returnType.name}"
            )
            return true
        }

        private fun installMethodGap(
            bridge: DexKitBridge,
            classLoader: ClassLoader,
            label: String,
            marker: String,
            predicate: (Method) -> Boolean
        ): Int {
            var count = 0
            val results = bridge.findMethod {
                matcher { usingStrings(marker) }
            }
            results.forEach { methodData ->
                val method = runCatching { methodData.getMethodInstance(classLoader) }.getOrNull()
                    ?: return@forEach
                if (method.name == "<init>" || method.name == "<clinit>") return@forEach
                if (!predicate(method)) return@forEach
                if (hookBlocked(label, method)) count++
            }
            return count
        }

        @JvmStatic
        fun install(classLoader: ClassLoader, reason: String) {
            if (installedLabels.containsAll(desiredLabels)) return
            if (scanAttempts.get() >= MAX_SCAN_ATTEMPTS) return
            if (!scanInProgress.compareAndSet(false, true)) return
            val attempt = scanAttempts.incrementAndGet()

            Thread({
                try {
                    ensureDexKitLoaded()
                    DexKitBridge.create(classLoader, true).use { bridge ->
                        var installed = 0

                        // Facebook 579 leak captured by exp13:
                        // FBFetchReelsVideoAdsQuery -> X.7K0.A07 -> visible "Ad" Reel about
                        // 20 seconds later. The older resolver required the v576/v577
                        // void + reels_ad_query_send shape, so this changed method escaped.
                        // The marker itself names a dedicated Reels video-ad GraphQL query,
                        // so block every non-constructor method carrying it regardless of
                        // the obfuscated method's changed return type / parameter count.
                        installed += installMethodGap(
                            bridge,
                            classLoader,
                            "fbfetch-reels-video-ads-query",
                            "FBFetchReelsVideoAdsQuery"
                        ) { _ -> true }

                        installed += installMethodGap(
                            bridge,
                            classLoader,
                            "reels-query-object-wrapper",
                            "reels_ad_query_send"
                        ) { method ->
                            method.returnType == Any::class.java && method.parameterCount in 1..2
                        }

                        // Intentionally do NOT block the 1-arg fb_shorts_similar_ad wrappers.
                        // Exp10 Vector logs showed X.Sat.invoke firing on ordinary Reels while
                        // comments were missing, so this path is shared with normal Reels UI.

                        installed += installMethodGap(
                            bridge,
                            classLoader,
                            "rti-void-handoff",
                            "IMMERSIVE_REAL_TIME_INTENT"
                        ) { method ->
                            method.returnType == Void.TYPE && method.parameterCount == 1
                        }

                        xlog(
                            "SCAN reason=$reason attempt=$attempt installed=$installed " +
                                "labels=${installedLabels.sorted()} rendererBlocking=disabled " +
                                "similarWrapperBlocking=disabled"
                        )
                    }
                } catch (t: Throwable) {
                    xlog("SCAN FAILED reason=$reason attempt=$attempt", t)
                } finally {
                    scanInProgress.set(false)
                }
            }, "FBAdsReelsGapScan-$attempt").start()
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
                                xlog("Facebook MultiDex configured; rescanning request gaps")
                                install(configuredLoader, "MultiDex.configure")
                            }
                        })
                    }
            }.onFailure {
                xlog("Unable to hook Facebook MultiDex readiness", it)
            }
        }

        private fun scheduleRetries(classLoader: ClassLoader) {
            val handler = Handler(Looper.getMainLooper())
            longArrayOf(0L, 100L, 350L, 900L, 2_000L, 4_000L, 8_000L, 12_000L).forEachIndexed { i, delay ->
                handler.postDelayed({ install(classLoader, "attach-retry-${i + 1}") }, delay)
            }
        }
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != HOST_PACKAGE) return

        XposedBridge.log(
            "$TAG: MODULE LOADED process=${lpparam.processName} " +
                "rendererBlocking=disabled similarWrapperBlocking=disabled"
        )
        hookDexReadiness(lpparam.classLoader)
        if (!attachHookInstalled.compareAndSet(false, true)) return

        val attach = Application::class.java.getDeclaredMethod("attach", Context::class.java)
        attach.isAccessible = true
        XposedBridge.hookMethod(attach, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val app = param.thisObject as? Application ?: return
                xlog("Application attached version-gap request hooks active")
                scheduleRetries(app.classLoader)
            }
        })
    }
}
