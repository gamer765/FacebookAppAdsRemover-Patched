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
 * Universal fallback for dedicated Reels ad renderers.
 *
 * Facebook 579 contains many ad-only KComponent/Litho renderer families whose leaf components
 * do not necessarily carry the shared Reels AD/ADS_MIDCARD classification model. Their stable
 * component-name strings remain unobfuscated, so resolve those strings and suppress only the
 * corresponding ad-only render entry points.
 *
 * No Facebook X.* class names are hard-coded. Resolved obfuscated classes are cached per host
 * build so subsequent launches can install hooks before the first Reels render.
 */
class ObfuscationResistantReelsRendererHooks : IXposedHookLoadPackage {

    companion object {
        private const val TAG = "FBAdsObfReelsRender"
        private const val HOST_PACKAGE = "com.facebook.katana"
        private const val PREFS_NAME = "fb_ads_obf_reels_render_targets_v2"
        private const val CACHE_PREFIX = "targets:"
        private const val MAX_SCAN_ATTEMPTS = 10

        // Verified in Facebook 579: each stable string below is referenced directly by a
        // render(ComponentContext) method in an FbShortsAds-only renderer class. This is much
        // safer than treating generic MIDCARD/PARADE/UGC classifications as ads.
        private val AD_ONLY_COMPONENT_ANCHORS = linkedSetOf(
            "FbShortsAdsActionChip",
            "FbShortsAdsAuthorKComponent",
            "FbShortsAdsAuthorProfilePictureComponent",
            "FbShortsAdsAuthorWithFDSComponent",
            "FbShortsAdsCTAKComponent",
            "FbShortsAdsCTMEditableEndSceneKComponent",
            "FbShortsAdsCreativeProductStickerCTAComponent",
            "FbShortsAdsCreativeStickerImageComponent",
            "FbShortsAdsDLPProductCardComponent",
            "FbShortsAdsDirectConversionTouchComponent",
            "FbShortsAdsDotsCarouselPlayerComponent",
            "FbShortsAdsHScrollComponent",
            "FbShortsAdsHscrollAlbumLastCardComponent",
            "FbShortsAdsHscrollCardComponent",
            "FbShortsAdsIABReentryMidsceneCardComponent",
            "FbShortsAdsIABScreenshotEndSceneComponent",
            "FbShortsAdsLeadGenMCOComponent",
            "FbShortsAdsLeadGenPIIComponent",
            "FbShortsAdsMidSceneBizAgentComponent",
            "FbShortsAdsMidsceneCardComponent",
            "FbShortsAdsMidsceneContainerComponent",
            "FbShortsAdsMixedMediaCardKComponent",
            "FbShortsAdsMultiAdsGridCardComponent",
            "FbShortsAdsMultiAdsGridComponent",
            "FbShortsAdsMultiAdsVerticalCardComponent",
            "FbShortsAdsMultiAdsVerticalComponent",
            "FbShortsAdsNativeSlideshowImageComponent",
            "FbShortsAdsNativeSlideshowPlayerComponent",
            "FbShortsAdsPhotoCardComponent",
            "FbShortsAdsPhotoInfoChip",
            "FbShortsAdsPhotoKComponent",
            "FbShortsAdsPostScrollNudgeBizAiAgentComponent",
            "FbShortsAdsPostScrollNudgeHscrollCreativeComponent",
            "FbShortsAdsPostScrollNudgeHscrollCtaComponent",
            "FbShortsAdsPostScrollNudgeHscrollHeaderComponent",
            "FbShortsAdsPostScrollNudgeHscrollMetadataCardComponent",
            "FbShortsAdsPostScrollNudgeMAIComponent",
            "FbShortsAdsPostScrollNudgeMAIContentCardComponent",
            "FbShortsAdsPostScrollNudgeMultiImageCollageComponent",
            "FbShortsAdsPostScrollNudgeScreenShotComponent",
            "FbShortsAdsPostScrollNudgeScreenShotComponentWithSmoothSwipe",
            "FbShortsAdsPostScrollNudgeTrustSignalComponent",
            "FbShortsAdsPostScrollNudgeTrustSignalComponentWithSmoothSwipe",
            "FbShortsAdsProductExtensionsCard",
            "FbShortsAdsRealTimeIntentComponent",
            "FbShortsAdsRootKComponent",
            "FbShortsAdsRtiSingleCardKComponent",
            "FbShortsAdsSponsoredSubtitleComponent",
            "FbShortsAdsStickerCTAComponent",
            "FbShortsAdsSwipeLeftComponent",
            "FbShortsAdsTooltipTouchComponent",
            "FbShortsAdsXAndBrowseProgressRingComponent",
            "FbShortsAdsXAndBrowseStartingIndicatorComponent"
        )

        private val attachHookInstalled = AtomicBoolean(false)
        private val dexReadyHookInstalled = AtomicBoolean(false)
        private val dexKitLoaded = AtomicBoolean(false)
        private val scanInProgress = AtomicBoolean(false)
        private val scanAttempts = AtomicInteger(0)
        private val resolvedAnchors: MutableSet<String> = ConcurrentHashMap.newKeySet()
        private val hookedMethods: MutableSet<Method> = Collections.synchronizedSet(HashSet())
        private val pendingCacheEntries: MutableSet<String> = ConcurrentHashMap.newKeySet()

        @Volatile
        private var application: Application? = null

        private fun labelFor(anchor: String): String = anchor
            .removePrefix("FbShortsAds")
            .replace(Regex("([a-z0-9])([A-Z])"), "$1-$2")
            .lowercase()

        private fun ensureDexKitLoaded() {
            if (dexKitLoaded.get()) return
            synchronized(dexKitLoaded) {
                if (dexKitLoaded.get()) return
                System.loadLibrary("dexkit")
                dexKitLoaded.set(true)
            }
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

        private fun cacheEntry(anchor: String, clazz: Class<*>): String = "$anchor|${clazz.name}"

        private fun savePendingCache() {
            val app = application ?: return
            if (pendingCacheEntries.isEmpty()) return
            val key = CACHE_PREFIX + hostFingerprint(app)
            val prefs = app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val merged = HashSet(prefs.getStringSet(key, emptySet()).orEmpty())
            merged.addAll(pendingCacheEntries)
            prefs.edit().putStringSet(key, merged).apply()
        }

        private fun isRenderTarget(method: Method): Boolean {
            if (Modifier.isStatic(method.modifiers) || method.isSynthetic || method.isBridge) return false
            if (method.returnType == Void.TYPE || method.returnType.isPrimitive) return false

            if (method.name == "render") return true

            // KComponent layout entry points can be renamed. On an already ad-only class, a
            // one-object-parameter/object-return method is a safe fallback shape.
            return method.parameterCount == 1 && !method.parameterTypes[0].isPrimitive
        }

        private fun hookAdOnlyClass(anchor: String, clazz: Class<*>): Boolean {
            val targets = clazz.declaredMethods.filter(::isRenderTarget)
            if (targets.isEmpty()) return false

            val label = labelFor(anchor)
            var newlyHooked = 0
            targets.forEach { method ->
                if (!hookedMethods.add(method)) return@forEach
                method.isAccessible = true
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        param.result = null
                        Log.i(
                            TAG,
                            "Blocked reels-ad-only-$label ${method.declaringClass.name}.${method.name}"
                        )
                    }
                })
                newlyHooked++
            }

            resolvedAnchors.add(anchor)
            pendingCacheEntries.add(cacheEntry(anchor, clazz))
            Log.i(
                TAG,
                "Resolved $anchor -> ${clazz.name} targets=${targets.size} newHooks=$newlyHooked"
            )
            return true
        }

        private fun installCachedTargets(app: Application, classLoader: ClassLoader): Int {
            val entries = app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getStringSet(CACHE_PREFIX + hostFingerprint(app), emptySet())
                .orEmpty()

            var installed = 0
            entries.forEach { encoded ->
                val split = encoded.indexOf('|')
                if (split <= 0 || split == encoded.lastIndex) return@forEach
                val anchor = encoded.substring(0, split)
                if (!AD_ONLY_COMPONENT_ANCHORS.contains(anchor)) return@forEach
                val className = encoded.substring(split + 1)
                val clazz = runCatching {
                    Class.forName(className, false, classLoader)
                }.getOrNull() ?: return@forEach
                if (hookAdOnlyClass(anchor, clazz)) installed++
            }

            if (installed > 0) {
                Log.i(TAG, "Installed $installed cached dedicated Reels ad renderer target(s)")
            }
            return installed
        }

        @JvmStatic
        fun install(classLoader: ClassLoader, reason: String) {
            application?.let { installCachedTargets(it, classLoader) }
            if (resolvedAnchors.containsAll(AD_ONLY_COMPONENT_ANCHORS)) return
            if (scanAttempts.get() >= MAX_SCAN_ATTEMPTS) return
            if (!scanInProgress.compareAndSet(false, true)) return

            val attempt = scanAttempts.incrementAndGet()
            Thread({
                try {
                    ensureDexKitLoaded()
                    DexKitBridge.create(classLoader, true).use { bridge ->
                        var resolvedNow = 0
                        AD_ONLY_COMPONENT_ANCHORS.forEach { anchor ->
                            if (resolvedAnchors.contains(anchor)) return@forEach
                            val matches = bridge.findClass {
                                matcher { usingStrings(anchor) }
                            }
                            matches.forEach { classData ->
                                val clazz = runCatching {
                                    classData.getInstance(classLoader)
                                }.getOrNull() ?: return@forEach
                                if (hookAdOnlyClass(anchor, clazz)) {
                                    resolvedNow++
                                }
                            }
                        }
                        savePendingCache()
                        Log.i(
                            TAG,
                            "scan reason=$reason attempt=$attempt resolvedNow=$resolvedNow " +
                                "resolved=${resolvedAnchors.size}/${AD_ONLY_COMPONENT_ANCHORS.size}"
                        )
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "Dedicated Reels renderer scan failed reason=$reason attempt=$attempt", t)
                } finally {
                    scanInProgress.set(false)
                }
            }, "FBAdsReelsRendererScan-$attempt").start()
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
            longArrayOf(0L, 100L, 350L, 900L, 2_000L, 4_000L, 8_000L, 12_000L).forEachIndexed { i, delay ->
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
                application = app
                installCachedTargets(app, app.classLoader)
                scheduleRetries(app.classLoader)
            }
        })
    }
}
