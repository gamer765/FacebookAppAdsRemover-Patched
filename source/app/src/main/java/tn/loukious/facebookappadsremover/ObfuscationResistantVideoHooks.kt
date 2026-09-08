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
 * Experimental ad hook layer that intentionally avoids Facebook's obfuscated X.* names.
 *
 * It resolves dedicated Reels / video-ad request methods from stable strings embedded in
 * the bytecode, installs them as soon as Facebook's secondary dex is ready, and caches the
 * resolved reflective method descriptors for subsequent launches of the same Facebook build.
 *
 * The normal Module remains loaded alongside this class, so this is additive and easy to
 * remove if a future Facebook release changes the stable markers themselves.
 */
class ObfuscationResistantVideoHooks : IXposedHookLoadPackage {

    companion object {
        private const val TAG = "FBAdsObfResistant"
        private const val HOST_PACKAGE = "com.facebook.katana"
        private const val PREFS_NAME = "fb_ads_obf_resistant_targets_v1"
        private const val CACHE_PREFIX = "targets:"
        private const val MAX_SCAN_ATTEMPTS = 8

        private val attachHookInstalled = AtomicBoolean(false)
        private val dexReadyHookInstalled = AtomicBoolean(false)
        private val dexKitLoaded = AtomicBoolean(false)
        private val scanInProgress = AtomicBoolean(false)
        private val scanAttempts = AtomicInteger(0)
        private val hookedMethods: MutableSet<Method> = Collections.synchronizedSet(HashSet())
        private val installedLabels: MutableSet<String> = ConcurrentHashMap.newKeySet()
        private val pendingCacheEntries: MutableSet<String> = ConcurrentHashMap.newKeySet()

        @Volatile
        private var application: Application? = null

        private val desiredLabels = setOf(
            "reels-dispatch",
            "commercial-break",
            "adbreak-server",
            "profile-reels",
            "reels-prefetch",
            "async-ad-channel",
            "rti-interstitial",
            "poe-interstitial",
            "similar-reels-ad"
        )

        @JvmStatic
        fun installFocusedStableStringHooks(classLoader: ClassLoader, reason: String) {
            if (installedLabels.containsAll(desiredLabels)) return
            if (scanAttempts.get() >= MAX_SCAN_ATTEMPTS) return
            if (!scanInProgress.compareAndSet(false, true)) return

            scanAttempts.incrementAndGet()
            Thread({
                try {
                    ensureDexKitLoaded()
                    application?.let { installCachedTargets(it, classLoader) }
                    if (installedLabels.containsAll(desiredLabels)) return@Thread

                    val bridge = DexKitBridge.create(classLoader, true)
                    try {
                        var installed = 0

                        // In both Facebook 576 and 577, the actual sponsored-Reels query
                        // dispatchers contain reels_ad_query_send and return void.
                        installed += hookByStableStrings(
                            bridge,
                            classLoader,
                            label = "reels-dispatch",
                            returnType = "void",
                            paramCount = null,
                            needles = arrayOf("reels_ad_query_send")
                        )

                        // Stronger redundant match for the primary GraphQL request builder.
                        installed += hookByStableStrings(
                            bridge,
                            classLoader,
                            label = "reels-primary-query",
                            returnType = "void",
                            paramCount = null,
                            needles = arrayOf("FBFetchReelsVideoAdsQuery", "reels_ad_query_send")
                        )

                        // Dedicated commercial-break / instream video-ad dispatchers.
                        installed += hookByStableStrings(
                            bridge,
                            classLoader,
                            label = "commercial-break",
                            returnType = "void",
                            paramCount = null,
                            needles = arrayOf("commercial_break_query_send", "Kicking off video ad fetch")
                        )

                        // Main + helper ad-break server fetchers. These markers survived the
                        // 576 -> 577 obfuscation shuffle even though all X.* names changed.
                        installed += hookByStableStrings(
                            bridge,
                            classLoader,
                            label = "adbreak-server",
                            returnType = "void",
                            paramCount = null,
                            needles = arrayOf("AdBreakServerAPI", "Fetch adbreak when already fetching")
                        )

                        installed += hookByStableStrings(
                            bridge,
                            classLoader,
                            label = "profile-reels",
                            returnType = "void",
                            paramCount = null,
                            needles = arrayOf("ProfileReelsAsyncAdsQuery")
                        )

                        installed += hookByStableStrings(
                            bridge,
                            classLoader,
                            label = "reels-prefetch",
                            returnType = "void",
                            paramCount = null,
                            needles = arrayOf(
                                "ReelsAdsBackgroundPrefetchInitializer",
                                "ReelsAdsPeriodicPrefetch"
                            )
                        )

                        installed += hookByStableStrings(
                            bridge,
                            classLoader,
                            label = "async-ad-channel",
                            returnType = "void",
                            paramCount = 2,
                            needles = arrayOf("VideoHomeCSRNetworkRequester.doAdChannelNetworkRequest")
                        )

                        // These three builders return an Object and have no parameters in
                        // both analyzed Facebook builds. Returning null matches the behavior
                        // of the previous exact-name hooks but no longer depends on X.6UH,
                        // X.6UI, X.6V3, X.6V6, etc.
                        installed += hookByStableStrings(
                            bridge,
                            classLoader,
                            label = "rti-interstitial",
                            returnType = "java.lang.Object",
                            paramCount = 0,
                            needles = arrayOf("IMMERSIVE_REAL_TIME_INTENT")
                        )

                        installed += hookByStableStrings(
                            bridge,
                            classLoader,
                            label = "poe-interstitial",
                            returnType = "java.lang.Object",
                            paramCount = 0,
                            needles = arrayOf("POE_TRIGGERED_INTERSTITIAL")
                        )

                        installed += hookByStableStrings(
                            bridge,
                            classLoader,
                            label = "similar-reels-ad",
                            returnType = "java.lang.Object",
                            paramCount = 0,
                            needles = arrayOf("fb_shorts_similar_ad")
                        )

                        savePendingCache()
                        Log.i(
                            TAG,
                            "Focused scan reason=$reason installed=$installed labels=${installedLabels.sorted()}"
                        )
                    } finally {
                        bridge.close()
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "Focused stable-string scan failed reason=$reason", t)
                } finally {
                    scanInProgress.set(false)
                }
            }, "FBAdsStableStringScan").start()
        }

        private fun hookByStableStrings(
            bridge: DexKitBridge,
            classLoader: ClassLoader,
            label: String,
            returnType: String,
            paramCount: Int?,
            needles: Array<String>
        ): Int {
            var count = 0
            val results = bridge.findMethod {
                matcher {
                    usingStrings(*needles)
                    this.returnType = returnType
                    if (paramCount != null) this.paramCount = paramCount
                }
            }

            results.forEach { methodData ->
                val method = runCatching { methodData.getMethodInstance(classLoader) }.getOrNull()
                    ?: return@forEach
                if (method.name == "<init>" || method.name == "<clinit>") return@forEach
                if (!hookMethod(label, method)) return@forEach
                count++
            }

            if (count > 0) installedLabels.add(label)
            return count
        }

        private fun hookMethod(label: String, method: Method): Boolean {
            if (!hookedMethods.add(method)) {
                installedLabels.add(label)
                return false
            }

            method.isAccessible = true
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    param.result = null
                    Log.i(TAG, "Blocked $label ${method.declaringClass.name}.${method.name}")
                }
            })

            installedLabels.add(label)
            pendingCacheEntries.add(encodeCacheEntry(label, method))
            Log.i(TAG, "Installed $label ${method.declaringClass.name}.${method.name}")
            return true
        }

        private fun encodeCacheEntry(label: String, method: Method): String {
            val params = method.parameterTypes.joinToString(",") { it.name }
            return listOf(
                label,
                method.declaringClass.name,
                method.name,
                params,
                method.returnType.name
            ).joinToString("|")
        }

        private fun installCachedTargets(app: Application, classLoader: ClassLoader): Int {
            val fingerprint = hostFingerprint(app)
            val entries = app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getStringSet(CACHE_PREFIX + fingerprint, emptySet())
                .orEmpty()

            var installed = 0
            entries.forEach { encoded ->
                val parts = encoded.split('|', limit = 5)
                if (parts.size != 5) return@forEach
                val label = parts[0]
                val declaringClass = runCatching {
                    Class.forName(parts[1], false, classLoader)
                }.getOrNull() ?: return@forEach
                val parameterTypes = if (parts[3].isBlank()) {
                    emptyArray()
                } else {
                    parts[3].split(',').mapNotNull { resolveType(it, classLoader) }.toTypedArray()
                }
                if (parameterTypes.size != if (parts[3].isBlank()) 0 else parts[3].split(',').size) {
                    return@forEach
                }
                val method = runCatching {
                    declaringClass.getDeclaredMethod(parts[2], *parameterTypes)
                }.getOrNull() ?: return@forEach
                if (method.returnType.name != parts[4]) return@forEach
                if (hookMethod(label, method)) installed++
            }

            if (installed > 0) {
                Log.i(TAG, "Installed $installed cached stable-string target(s) for $fingerprint")
            }
            return installed
        }

        private fun resolveType(name: String, classLoader: ClassLoader): Class<*>? = when (name) {
            "boolean" -> Boolean::class.javaPrimitiveType
            "byte" -> Byte::class.javaPrimitiveType
            "char" -> Char::class.javaPrimitiveType
            "short" -> Short::class.javaPrimitiveType
            "int" -> Int::class.javaPrimitiveType
            "long" -> Long::class.javaPrimitiveType
            "float" -> Float::class.javaPrimitiveType
            "double" -> Double::class.javaPrimitiveType
            "void" -> Void.TYPE
            else -> runCatching { Class.forName(name, false, classLoader) }.getOrNull()
        }

        private fun hostFingerprint(app: Application): String {
            return runCatching {
                val info = app.packageManager.getPackageInfo(app.packageName, 0)
                val versionCode = if (android.os.Build.VERSION.SDK_INT >= 28) {
                    info.longVersionCode
                } else {
                    @Suppress("DEPRECATION")
                    info.versionCode.toLong()
                }
                "${info.versionName}:$versionCode"
            }.getOrDefault("unknown")
        }

        private fun savePendingCache() {
            val app = application ?: return
            if (pendingCacheEntries.isEmpty()) return
            val fingerprint = hostFingerprint(app)
            val prefs = app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val merged = HashSet(
                prefs.getStringSet(CACHE_PREFIX + fingerprint, emptySet()).orEmpty()
            )
            merged.addAll(pendingCacheEntries)
            prefs.edit().putStringSet(CACHE_PREFIX + fingerprint, merged).apply()
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
                                installFocusedStableStringHooks(configuredLoader, "MultiDex.configure")
                            }
                        })
                    }
            }.onFailure {
                Log.w(TAG, "Unable to hook Facebook MultiDex readiness", it)
            }
        }

        private fun ensureDexKitLoaded() {
            if (dexKitLoaded.get()) return
            synchronized(dexKitLoaded) {
                if (dexKitLoaded.get()) return
                System.loadLibrary("dexkit")
                dexKitLoaded.set(true)
            }
        }

        private fun scheduleRetries(classLoader: ClassLoader) {
            val handler = Handler(Looper.getMainLooper())
            val delays = longArrayOf(0L, 100L, 350L, 900L, 2_000L, 4_000L)
            delays.forEachIndexed { index, delay ->
                handler.postDelayed(
                    {
                        installFocusedStableStringHooks(
                            classLoader,
                            "attach-retry-${index + 1}"
                        )
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
                installCachedTargets(app, app.classLoader)
                savePendingCache()
                scheduleRetries(app.classLoader)
            }
        })
    }
}
