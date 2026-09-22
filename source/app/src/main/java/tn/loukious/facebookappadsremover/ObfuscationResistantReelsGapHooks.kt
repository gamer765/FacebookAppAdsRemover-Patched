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
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Facebook 579 follow-up for Reels ad paths that changed shape after the original universal
 * stable-string hooks were written.
 *
 * This layer deliberately avoids X.* names. It fills three concrete gaps observed in the 579
 * DEX and adds LSPosed-native diagnostics so Vector captures installs/hits:
 *
 *  - reels_ad_query_send now also appears on Object-returning lambda/coroutine wrappers;
 *  - fb_shorts_similar_ad gained a one-argument Object-returning wrapper;
 *  - Reels banner ads use ReelsBannerAdsComponent, outside the FbShortsAds* renderer family.
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

        // A small set of critical ad-only renderers is repeated here intentionally. The main
        // renderer layer still owns the full list; these duplicates give us XposedBridge logs
        // for the most important paths in Vector and act as a second fail-closed guard.
        private val TRACE_RENDER_ANCHORS = linkedMapOf(
            "ReelsBannerAdsComponent" to "banner-ads",
            "ReelsAdsCaptionCommentComponent" to "ads-caption-comment",
            "FbShortsAdsRootKComponent" to "fbshorts-root",
            "FbShortsAdsNativeSlideshowImageComponent" to "native-slideshow-image",
            "FbShortsAdsMultiAdsGridComponent" to "multiads-grid",
            "FbShortsAdsMultiAdsVerticalComponent" to "multiads-vertical",
            "FbShortsAdsPhotoKComponent" to "photo",
            "FbShortsAdsMixedMediaCardKComponent" to "mixed-media"
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

        private fun safeResult(method: Method): Any? = when (method.returnType) {
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

        private fun hookBlocked(label: String, method: Method): Boolean {
            if (!hookedMethods.add(method)) return false
            method.isAccessible = true
            // Run before the older renderer/request hooks. Some of those set result early,
            // which can stop later callbacks and hide diagnostics from Vector/LSPosed.
            XposedBridge.hookMethod(method, object : XC_MethodHook(10000) {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    xlog("HIT $label ${method.declaringClass.name}.${method.name}")
                    param.result = safeResult(method)
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

        private fun isRenderTarget(method: Method): Boolean {
            if (Modifier.isStatic(method.modifiers) || method.isSynthetic || method.isBridge) return false
            if (method.returnType == Void.TYPE || method.returnType.isPrimitive) return false
            if (method.returnType == String::class.java) return false
            if (method.name == "render") return true
            return method.parameterCount == 1 && !method.parameterTypes[0].isPrimitive
        }

        private fun installRendererTrace(
            bridge: DexKitBridge,
            classLoader: ClassLoader,
            anchor: String,
            label: String
        ): Int {
            var count = 0
            val classes = bridge.findClass {
                matcher { usingStrings(anchor) }
            }
            classes.forEach { classData ->
                val clazz = runCatching { classData.getInstance(classLoader) }.getOrNull()
                    ?: return@forEach
                clazz.declaredMethods.filter(::isRenderTarget).forEach { method ->
                    if (hookBlocked("renderer-$label", method)) count++
                }
            }
            return count
        }

        @JvmStatic
        fun install(classLoader: ClassLoader, reason: String) {
            if (scanAttempts.get() >= MAX_SCAN_ATTEMPTS) return
            if (!scanInProgress.compareAndSet(false, true)) return
            val attempt = scanAttempts.incrementAndGet()

            Thread({
                try {
                    ensureDexKitLoaded()
                    DexKitBridge.create(classLoader, true).use { bridge ->
                        var installed = 0

                        // 579: two new non-void dispatch wrappers carry reels_ad_query_send.
                        installed += installMethodGap(
                            bridge,
                            classLoader,
                            "reels-query-object-wrapper",
                            "reels_ad_query_send"
                        ) { method ->
                            method.returnType == Any::class.java && method.parameterCount in 1..2
                        }

                        // 579: similar-Reels ads gained a Function1-style wrapper. The older
                        // universal rule only covered zero-arg Object builders.
                        installed += installMethodGap(
                            bridge,
                            classLoader,
                            "similar-reels-object-wrapper",
                            "fb_shorts_similar_ad"
                        ) { method ->
                            method.returnType == Any::class.java && method.parameterCount == 1
                        }

                        // 579 also has a void RTI handoff on the same strong ad-only marker;
                        // the original universal rule only covered the zero-arg Object builder.
                        installed += installMethodGap(
                            bridge,
                            classLoader,
                            "rti-void-handoff",
                            "IMMERSIVE_REAL_TIME_INTENT"
                        ) { method ->
                            method.returnType == Void.TYPE && method.parameterCount == 1
                        }

                        TRACE_RENDER_ANCHORS.forEach { (anchor, label) ->
                            installed += installRendererTrace(bridge, classLoader, anchor, label)
                        }

                        xlog(
                            "SCAN reason=$reason attempt=$attempt installed=$installed " +
                                "labels=${installedLabels.sorted()}"
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
                                xlog("Facebook MultiDex configured; rescanning")
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

        XposedBridge.log("$TAG: MODULE LOADED process=${lpparam.processName}")
        hookDexReadiness(lpparam.classLoader)
        if (!attachHookInstalled.compareAndSet(false, true)) return

        val attach = Application::class.java.getDeclaredMethod("attach", Context::class.java)
        attach.isAccessible = true
        XposedBridge.hookMethod(attach, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val app = param.thisObject as? Application ?: return
                xlog("Application attached version-gap hooks active")
                scheduleRetries(app.classLoader)
            }
        })
    }
}
