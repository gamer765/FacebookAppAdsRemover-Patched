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
 *  - reels_ad_query_send Object-returning lambda/coroutine wrappers;
 *  - IMMERSIVE_REAL_TIME_INTENT one-argument void handoff;
 *  - Facebook 579 in-content-ad state listener: suppress only A0B/A09 enter-ad events before X.8et.A02 is set;
 *  - X.8et direct state updater: clear internal A02 if a second caller leaves it true;
 *  - ReelsVddLayout in-content-ad state gate: force isPlayingInContentVideoAd=false as fallback.
 *
 * FBFetchReelsVideoAdsQuery is intentionally NOT blocked: in v579 its A07 path returns
 * an async future, and forcing null leaves Reels playback stuck after the "ad starting"
 * transition. Ads from that path are removed downstream by classification instead.
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
            "reels-query-object-wrapper",
            "rti-void-handoff",
            "reels-content-video-ad-state-gate",
            "reels-content-video-ad-state-listener",
            "reels-content-video-ad-direct-state-guard"
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

        private fun installDirectContentAdStateGuard(classLoader: ClassLoader): Int {
            // Facebook 579 has a second caller of ReelsVddLayout::commentBarStateChange:
            // X.8et.A00(...). Unlike X.B94.AuA, this path writes the internal
            // X.8et.A02 AtomicBoolean directly before calling X.7V4.A00.
            //
            // The exp19 log captured X.7V4.A00(true) with no conditional B94 hit,
            // proving this direct path can independently enter ad state. Allow A00
            // to perform all of its normal bookkeeping, then immediately clear only
            // A02 if it was left true. The X.7V4 fallback hook simultaneously forces
            // the externally published CommentBarState flag false.
            val clazz = runCatching { Class.forName("X.8et", false, classLoader) }.getOrNull()
                ?: return 0
            val stateField = runCatching {
                clazz.getDeclaredField("A02").apply { isAccessible = true }
            }.getOrNull() ?: return 0
            val method = clazz.declaredMethods.firstOrNull {
                it.name == "A00" &&
                    it.returnType == Void.TYPE &&
                    it.parameterCount == 6
            } ?: return 0

            if (!hookedMethods.add(method)) {
                installedLabels.add("reels-content-video-ad-direct-state-guard")
                return 0
            }

            method.isAccessible = true
            XposedBridge.hookMethod(method, object : XC_MethodHook(-10000) {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val target = param.thisObject ?: return
                    val atomic = runCatching {
                        stateField.get(target) as? java.util.concurrent.atomic.AtomicBoolean
                    }.getOrNull() ?: return
                    if (atomic.compareAndSet(true, false)) {
                        xlog(
                            "HIT reels-content-video-ad-direct-state-guard " +
                                "${method.declaringClass.name}.${method.name} cleared internal A02=true"
                        )
                    }
                }
            })
            installedLabels.add("reels-content-video-ad-direct-state-guard")
            xlog(
                "INSTALLED reels-content-video-ad-direct-state-guard " +
                    "${method.declaringClass.name}.${method.name}"
            )
            return 1
        }

        private fun installContentAdStateListenerBlock(classLoader: ClassLoader): Int {
            // Facebook 579 DEX (classes6.dex) gives the exact transition logic:
            //
            // X.B94.AuA(X.bsE)
            //   if (event.Au8() == 178) {
            //       val state = (event as X.CZQ).A00
            //       val enteringAd = (state == X.556.A0B || state == X.556.A09)
            //       X.8et.A02.set(enteringAd)
            //       X.7V4.A00(..., enteringAd)
            //   }
            //
            // Exp18 returned from AuA for every callback, which also swallowed the
            // false/cleanup transitions. Exp19 then compared X.555 and X.556 objects
            // directly, so the condition never matched. Mirror Facebook's own A1X
            // predicate here and suppress ONLY the two states that set enteringAd=true.
            val clazz = runCatching { Class.forName("X.B94", false, classLoader) }.getOrNull()
                ?: return 0
            val method = clazz.declaredMethods.firstOrNull {
                it.name == "AuA" &&
                    it.returnType == Void.TYPE &&
                    it.parameterCount == 1
            } ?: return 0

            val eventClass = runCatching { Class.forName("X.CZQ", false, classLoader) }.getOrNull()
                ?: return 0
            val eventStateField = runCatching {
                eventClass.getDeclaredField("A00").apply { isAccessible = true }
            }.getOrNull() ?: return 0
            val classifierClass = runCatching { Class.forName("X.556", false, classLoader) }.getOrNull()
                ?: return 0
            val stateValueClass = runCatching { Class.forName("X.555", false, classLoader) }.getOrNull()
                ?: return 0
            val adStateA0B = runCatching {
                classifierClass.getDeclaredField("A0B").apply { isAccessible = true }.get(null)
            }.getOrNull() ?: return 0
            val adStateA09 = runCatching {
                classifierClass.getDeclaredField("A09").apply { isAccessible = true }.get(null)
            }.getOrNull() ?: return 0

            // Facebook's own B94.AuA does not compare CZQ.A00 directly with
            // X.556.A0B/A09. CZQ.A00 is X.555 and the classifier constants are
            // X.556; the app calls X.9dO.A1X(X.556, X.555) for both tests.
            // Use that exact predicate so our hook mirrors v579 semantics.
            val statePredicate = runCatching {
                val helperClass = Class.forName("X.9dO", false, classLoader)
                helperClass.getDeclaredMethod("A1X", classifierClass, stateValueClass).apply {
                    isAccessible = true
                }
            }.getOrNull() ?: return 0

            if (!hookedMethods.add(method)) {
                installedLabels.add("reels-content-video-ad-state-listener")
                return 0
            }
            method.isAccessible = true
            XposedBridge.hookMethod(method, object : XC_MethodHook(10000) {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val event = param.args.getOrNull(0) ?: return
                    if (!eventClass.isInstance(event)) return
                    val state = runCatching { eventStateField.get(event) }.getOrNull() ?: return

                    val matchesA0B = runCatching {
                        statePredicate.invoke(null, adStateA0B, state) as? Boolean
                    }.getOrNull() == true
                    val matchesA09 = if (!matchesA0B) {
                        runCatching {
                            statePredicate.invoke(null, adStateA09, state) as? Boolean
                        }.getOrNull() == true
                    } else false

                    if (!matchesA0B && !matchesA09) {
                        // Leave the normal false/cleanup transition untouched.
                        return
                    }

                    xlog(
                        "HIT reels-content-video-ad-state-listener " +
                            "${method.declaringClass.name}.${method.name} " +
                            "state=${if (matchesA0B) "A0B" else "A09"} suppressing enter-ad transition"
                    )
                    param.result = null
                }
            })
            installedLabels.add("reels-content-video-ad-state-listener")
            xlog(
                "INSTALLED reels-content-video-ad-state-listener " +
                    "${method.declaringClass.name}.${method.name} conditional=A0B|A09"
            )
            return 1
        }

        private fun installStateGate(
            bridge: DexKitBridge,
            classLoader: ClassLoader
        ): Int {
            var count = 0
            val results = bridge.findMethod {
                matcher { usingStrings("ReelsVddLayout::commentBarStateChange") }
            }
            results.forEach { methodData ->
                val method = runCatching { methodData.getMethodInstance(classLoader) }.getOrNull()
                    ?: return@forEach
                if (method.name == "<init>" || method.name == "<clinit>") return@forEach
                val params = method.parameterTypes
                if (
                    method.returnType != Void.TYPE ||
                    params.size != 6 ||
                    params[3] != java.lang.Boolean.TYPE ||
                    params[4] != java.lang.Boolean.TYPE ||
                    params[5] != java.lang.Boolean.TYPE
                ) return@forEach
                if (!hookedMethods.add(method)) {
                    installedLabels.add("reels-content-video-ad-state-gate")
                    return@forEach
                }
                method.isAccessible = true
                XposedBridge.hookMethod(method, object : XC_MethodHook(10000) {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (param.args.getOrNull(5) == true) {
                            xlog(
                                "HIT reels-content-video-ad-state-gate " +
                                    "${method.declaringClass.name}.${method.name} forcing isPlayingInContentVideoAd=false"
                            )
                            param.args[5] = false
                        }
                    }
                })
                installedLabels.add("reels-content-video-ad-state-gate")
                xlog(
                    "INSTALLED reels-content-video-ad-state-gate " +
                        "${method.declaringClass.name}.${method.name} params=${method.parameterCount}"
                )
                count++
            }
            return count
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

                        // Do not null FBFetchReelsVideoAdsQuery on Facebook 579.
                        // X.7K0.A07 returns a future; exp15 showed that nulling it prevents
                        // the ad from loading but leaves the current Reel paused in an
                        // "ad starting" transition. The downstream pager/render classifier
                        // now removes the v579 ad model safely instead.

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

                        // Facebook 579 in-content Reel ads transition the current organic
                        // Reel into ad mode through ReelsVddLayout::commentBarStateChange.
                        // The method's final boolean maps directly to CommentBarState's
                        // isPlayingInContentVideoAd field. Force only that flag false so
                        // the organic Reel remains active while downstream ad filtering
                        // discards the ad payload.
                        installed += installContentAdStateListenerBlock(classLoader)
                        installed += installDirectContentAdStateGuard(classLoader)
                        installed += installStateGate(bridge, classLoader)

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
