package tn.loukious.facebookappadsremover

import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.TextView
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage
import org.luckypray.dexkit.DexKitBridge
import java.io.File
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.ArrayDeque
import java.util.Collections
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Passive diagnostics for Reels ads that survive the normal blockers.
 *
 * IMPORTANT: this class never changes a Facebook method argument/result. It only logs to
 * XposedBridge so Vector/LSPosed can show the path used by a visible ad. The existing generic
 * Reels diagnostics use the module's release Log helper (which is silent when BuildConfig.DEBUG
 * is false), so production test builds otherwise provide no evidence for pager/classifier hits.
 */
class ObfuscationResistantReelsTraceHooks : IXposedHookLoadPackage {

    companion object {
        private const val TAG = "FBAdsReelsTrace"
        private const val HOST_PACKAGE = "com.facebook.katana"
        private const val REELS_CACHE_FILE = "fbar_reels_guard_cache.properties"
        private const val MAX_SCAN_ATTEMPTS = 10
        private const val NORMAL_EVENT_FIRST = 40
        private const val NORMAL_EVENT_EVERY = 250

        private val attachHookInstalled = AtomicBoolean(false)
        private val dexReadyHookInstalled = AtomicBoolean(false)
        private val frameworkProbeInstalled = AtomicBoolean(false)
        private val dexKitLoaded = AtomicBoolean(false)
        private val scanInProgress = AtomicBoolean(false)
        private val scanAttempts = AtomicInteger(0)
        private val normalEvents = AtomicInteger(0)
        private val cacheLogged = AtomicBoolean(false)

        private val hookedPagerMethods: MutableSet<Method> = Collections.synchronizedSet(HashSet())
        private val hookedSnapshotMethods: MutableSet<Method> = Collections.synchronizedSet(HashSet())
        private val hookedRenderableMethods: MutableSet<Method> = Collections.synchronizedSet(HashSet())
        private val hookedMarkerSpecs: MutableSet<String> = ConcurrentHashMap.newKeySet()
        private val markerHits = ConcurrentHashMap<String, AtomicInteger>()
        private val wrapperListFields = ConcurrentHashMap<Class<*>, Field?>()

        @Volatile
        private var application: Application? = null

        @Volatile
        private var classifier: CachedClassifier? = null

        private val markerStrings = listOf(
            "FbShortsAdsSponsoredLabelComponent",
            "ReelsBannerAdsComponent",
            "ReelsBannerAdsNativeComponent",
            "ReelsBannerAdsFetchHelper",
            "FBFetchReelsVideoAdsQuery",
            "reels_ad_query_send",
            "after_model_added_to_pool",
            "fb_reels_facebook_reels_multiads_media",
            "IMMERSIVE_REAL_TIME_INTENT",
            "POE_TRIGGERED_INTERSTITIAL",
            "fb_shorts_similar_ad",
            "ReelsAdsDelayedSkipController",
            "FbShortsViewerAdGenAiTransparencyComponentForSponsoredAds"
        )

        private fun xlog(message: String) {
            XposedBridge.log("$TAG: $message")
        }

        private fun event(message: String) {
            val n = normalEvents.incrementAndGet()
            if (n <= NORMAL_EVENT_FIRST || n % NORMAL_EVENT_EVERY == 0) {
                xlog("E$n $message")
            }
        }

        // High-value ad evidence is never globally suppressed. This is intentionally
        // separate from event(): exp12 exhausted its 700-event budget on ordinary UGC
        // within about one minute, leaving no diagnostics hours later when an ad leaked.
        private fun signal(message: String) {
            xlog("SIGNAL $message")
        }

        private fun isInterestingClassification(value: String?): Boolean =
            value != null && value != "UGC"

        private fun shortStack(skip: Int = 0, max: Int = 10): String = Throwable().stackTrace
            .drop(2 + skip)
            .take(max)
            .joinToString(" <- ") { "${it.className}.${it.methodName}" }

        private fun ensureDexKitLoaded() {
            if (dexKitLoaded.get()) return
            synchronized(dexKitLoaded) {
                if (dexKitLoaded.get()) return
                System.loadLibrary("dexkit")
                dexKitLoaded.set(true)
            }
        }

        private fun resolveWrapperListField(clazz: Class<*>?): Field? {
            if (clazz == null) return null
            wrapperListFields[clazz]?.let { return it }
            val resolved = runCatching {
                var current: Class<*>? = clazz
                var found: Field? = null
                while (current != null && current != Any::class.java && found == null) {
                    found = current.declaredFields.firstOrNull { field ->
                        !Modifier.isStatic(field.modifiers) && Iterable::class.java.isAssignableFrom(field.type)
                    }
                    current = current.superclass
                }
                found?.isAccessible = true
                found
            }.getOrNull()
            wrapperListFields[clazz] = resolved
            return resolved
        }

        private fun summarizeItems(items: Iterable<*>?, localClassifier: CachedClassifier?): String {
            if (items == null) return "items=null"
            var total = 0
            var classified = 0
            val values = LinkedHashMap<String, Int>()
            val sample = ArrayList<String>(12)
            for (item in items) {
                total++
                val cls = localClassifier?.classificationOf(item)
                if (cls != null) {
                    classified++
                    values[cls] = (values[cls] ?: 0) + 1
                }
                if (sample.size < 12) {
                    sample.add("${item?.javaClass?.name ?: "null"}=${cls ?: "?"}")
                }
            }
            return "total=$total classified=$classified values=$values sample=[${sample.joinToString(", ")}]"
        }

        private fun hookPagerMethod(method: Method, localClassifier: CachedClassifier?) {
            if (!hookedPagerMethods.add(method)) return
            method.isAccessible = true
            // High priority: inspect the original list before the normal filter rewrites it.
            XposedBridge.hookMethod(method, object : XC_MethodHook(20_000) {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val wrappers = param.args.getOrNull(0) as? Iterable<*> ?: return
                    var wrapperCount = 0
                    var itemCount = 0
                    val classifications = LinkedHashMap<String, Int>()
                    val sample = ArrayList<String>(16)
                    for (wrapper in wrappers) {
                        wrapperCount++
                        val field = resolveWrapperListField(wrapper?.javaClass)
                        val items = runCatching { field?.get(wrapper) }.getOrNull() as? Iterable<*> ?: continue
                        for (item in items) {
                            itemCount++
                            val cls = localClassifier?.classificationOf(item)
                            if (cls != null) classifications[cls] = (classifications[cls] ?: 0) + 1
                            if (sample.size < 16) {
                                sample.add("${item?.javaClass?.name ?: "null"}=${cls ?: "?"}")
                            }
                        }
                    }
                    val detail =
                        "PAGER ${method.declaringClass.name}.${method.name} wrappers=$wrapperCount " +
                            "items=$itemCount classifications=$classifications sample=[${sample.joinToString(", ")}]"
                    if (classifications.keys.any(::isInterestingClassification)) signal(detail) else event(detail)
                }
            })
            xlog("INSTALLED pager trace ${method.declaringClass.name}.${method.name}")
        }

        private fun hookSnapshotMethod(method: Method, localClassifier: CachedClassifier?) {
            if (!hookedSnapshotMethods.add(method)) return
            method.isAccessible = true
            // Low priority: Xposed runs after-callbacks in reverse priority order, so this sees
            // the original snapshot before the existing default-priority Reels filter changes it.
            XposedBridge.hookMethod(method, object : XC_MethodHook(-20_000) {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (param.throwable != null) return
                    val list = param.result as? Iterable<*> ?: return
                    val summary = summarizeItems(list, localClassifier)
                    val values = list.mapNotNull { localClassifier?.classificationOf(it) }.toSet()
                    val detail = "SNAPSHOT ${method.declaringClass.name}.${method.name} $summary"
                    if (values.any(::isInterestingClassification)) signal(detail) else event(detail)
                }
            })
            xlog("INSTALLED snapshot trace ${method.declaringClass.name}.${method.name}")
        }

        private fun hookRenderable(clazz: Class<*>, localClassifier: CachedClassifier?) {
            clazz.declaredMethods.filter { method ->
                method.name == "render" && !Modifier.isStatic(method.modifiers)
            }.forEach { method ->
                if (!hookedRenderableMethods.add(method)) return@forEach
                method.isAccessible = true
                XposedBridge.hookMethod(method, object : XC_MethodHook(20_000) {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val target = param.thisObject ?: return
                        val details = ArrayList<String>(10)
                        var current: Class<*>? = target.javaClass
                        while (current != null && current != Any::class.java && details.size < 10) {
                            current.declaredFields
                                .filter { !Modifier.isStatic(it.modifiers) }
                                .take(16)
                                .forEach { field ->
                                    if (details.size >= 10) return@forEach
                                    runCatching {
                                        field.isAccessible = true
                                        val value = field.get(target) ?: return@runCatching
                                        localClassifier?.modelClassification(value)?.let { cls ->
                                            details.add("${field.name}:${value.javaClass.name}=$cls")
                                        }
                                    }
                                }
                            current = current.superclass
                        }
                        val detail = "RENDER ${clazz.name}.${method.name} models=[${details.joinToString(", ")}]"
                        if (details.any { d -> !d.endsWith("=UGC") }) {
                            if (details.isNotEmpty()) signal(detail) else event(detail)
                        } else {
                            event(detail)
                        }
                    }
                })
                xlog("INSTALLED render trace ${clazz.name}.${method.name}")
            }
        }

        private fun installFromCache(app: Application, classLoader: ClassLoader) {
            runCatching {
                val file = File(app.cacheDir, REELS_CACHE_FILE)
                if (!file.exists()) {
                    if (cacheLogged.compareAndSet(false, true)) xlog("CACHE missing ${file.absolutePath}")
                    return
                }
                val props = Properties()
                file.inputStream().use { props.load(it) }

                val interfaceSpecs = props.getProperty("modelInterfaces").orEmpty()
                    .split(',').map { it.trim() }.filter { it.isNotEmpty() }
                val pairs = interfaceSpecs.mapNotNull { spec ->
                    val split = spec.indexOf('#')
                    if (split <= 0) return@mapNotNull null
                    runCatching {
                        val iface = Class.forName(spec.substring(0, split), false, classLoader)
                        val method = iface.getDeclaredMethod(spec.substring(split + 1))
                        method.isAccessible = true
                        iface to method
                    }.getOrNull()
                }
                val localClassifier = if (pairs.isNotEmpty()) CachedClassifier(pairs) else null
                classifier = localClassifier

                val pagerSpecs = props.getProperty("pagerPush").orEmpty()
                    .split(',').map { it.trim() }.filter { it.isNotEmpty() }
                val snapshotSpecs = props.getProperty("snapshots").orEmpty()
                    .split(',').map { it.trim() }.filter { it.isNotEmpty() }
                val renderables = props.getProperty("renderables").orEmpty()
                    .split(',').map { it.trim() }.filter { it.isNotEmpty() }

                if (cacheLogged.compareAndSet(false, true)) {
                    xlog(
                        "CACHE version=${props.getProperty("version")} module=${props.getProperty("moduleVersion")} " +
                            "enum=${props.getProperty("enumClass")} interfaces=${interfaceSpecs.size} " +
                            "pager=${pagerSpecs.joinToString()} snapshots=${snapshotSpecs.joinToString()} " +
                            "renderables=${renderables.size}"
                    )
                }

                for (spec in pagerSpecs) {
                    val split = spec.indexOf('#')
                    if (split <= 0) continue
                    val clazz = runCatching { Class.forName(spec.substring(0, split), false, classLoader) }.getOrNull() ?: continue
                    val name = spec.substring(split + 1)
                    clazz.declaredMethods.filter { method ->
                        method.name == name &&
                            !Modifier.isStatic(method.modifiers) &&
                            method.returnType == java.lang.Boolean.TYPE &&
                            method.parameterCount == 1 &&
                            List::class.java.isAssignableFrom(method.parameterTypes[0])
                    }.forEach { hookPagerMethod(it, localClassifier) }
                }

                for (spec in snapshotSpecs) {
                    val split = spec.indexOf('#')
                    if (split <= 0) continue
                    val clazz = runCatching { Class.forName(spec.substring(0, split), false, classLoader) }.getOrNull() ?: continue
                    val name = spec.substring(split + 1)
                    clazz.declaredMethods.filter { method ->
                        method.name == name && Modifier.isStatic(method.modifiers) &&
                            List::class.java.isAssignableFrom(method.returnType)
                    }.forEach { hookSnapshotMethod(it, localClassifier) }
                }

                // Passive only: seeing these renderables execute is useful when a visible ad
                // bypasses the classifier. No result is changed here.
                renderables.take(80).forEach { className ->
                    runCatching { Class.forName(className, false, classLoader) }.getOrNull()?.let { clazz ->
                        hookRenderable(clazz, localClassifier)
                    }
                }
            }.onFailure { xlog("CACHE trace install failed: ${it.javaClass.simpleName}: ${it.message}") }
        }

        private fun installMarkerProbes(classLoader: ClassLoader, reason: String) {
            if (scanAttempts.get() >= MAX_SCAN_ATTEMPTS) return
            if (!scanInProgress.compareAndSet(false, true)) return
            val attempt = scanAttempts.incrementAndGet()
            Thread({
                try {
                    ensureDexKitLoaded()
                    DexKitBridge.create(classLoader, true).use { bridge ->
                        var installed = 0
                        for (marker in markerStrings) {
                            val results = bridge.findMethod { matcher { usingStrings(marker) } }
                            for (methodData in results) {
                                val method = runCatching { methodData.getMethodInstance(classLoader) }.getOrNull() ?: continue
                                if (method.name == "<init>" || method.name == "<clinit>") continue
                                val spec = "$marker|${method.declaringClass.name}#${method.name}|${method.parameterTypes.joinToString { it.name }}"
                                if (!hookedMarkerSpecs.add(spec)) continue
                                method.isAccessible = true
                                XposedBridge.hookMethod(method, object : XC_MethodHook(20_000) {
                                    override fun beforeHookedMethod(param: MethodHookParam) {
                                        val hits = markerHits.computeIfAbsent(marker) { AtomicInteger(0) }.incrementAndGet()
                                        val args = param.args.take(6).joinToString(",") { arg ->
                                            val cls = classifier?.modelClassification(arg)
                                            "${arg?.javaClass?.name ?: "null"}${cls?.let { "=$it" } ?: ""}"
                                        }
                                        val detail =
                                            "MARKER[$marker] hit=$hits ${method.declaringClass.name}.${method.name} " +
                                                "args=[$args] stack=${shortStack(0, 9)}"
                                        // fb_shorts_similar_ad is known to be shared with ordinary Reels/comments,
                                        // so keep only that marker sampled. Strong ad-specific markers must remain
                                        // visible on every hit even many hours into the process.
                                        if (marker == "fb_shorts_similar_ad") {
                                            if (hits <= 30 || hits % 25 == 0) event(detail)
                                        } else {
                                            signal(detail)
                                        }
                                    }
                                })
                                installed++
                                xlog("INSTALLED marker[$marker] ${method.declaringClass.name}.${method.name}")
                            }
                        }
                        xlog("SCAN reason=$reason attempt=$attempt markerHooks=$installed")
                    }
                } catch (t: Throwable) {
                    xlog("SCAN FAILED reason=$reason attempt=$attempt ${t.javaClass.simpleName}: ${t.message}")
                } finally {
                    scanInProgress.set(false)
                }
            }, "FBAdsReelsTraceScan-$attempt").start()
        }

        private fun installFrameworkSponsoredTextProbe() {
            if (!frameworkProbeInstalled.compareAndSet(false, true)) return
            runCatching {
                TextView::class.java.declaredMethods
                    .filter { method ->
                        method.name == "setText" && method.parameterCount > 0 &&
                            CharSequence::class.java.isAssignableFrom(method.parameterTypes[0])
                    }
                    .forEach { method ->
                        method.isAccessible = true
                        XposedBridge.hookMethod(method, object : XC_MethodHook(20_000) {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                val text = (param.args.getOrNull(0) as? CharSequence)?.toString()?.trim() ?: return
                                val lower = text.lowercase()
                                if (lower != "sponsored" && !lower.contains("sponsored") && lower != "ad" && lower != "ad choices") return
                                val view = param.thisObject as? View
                                val parents = ArrayList<String>(6)
                                var parent = view?.parent
                                repeat(6) {
                                    if (parent == null) return@repeat
                                    parents.add(parent!!.javaClass.name)
                                    parent = parent!!.parent
                                }
                                signal("UI-TEXT text=${text.take(80)} view=${view?.javaClass?.name} parents=$parents stack=${shortStack(0, 10)}")
                            }
                        })
                    }
                val contentDescription = View::class.java.getDeclaredMethod("setContentDescription", CharSequence::class.java)
                contentDescription.isAccessible = true
                XposedBridge.hookMethod(contentDescription, object : XC_MethodHook(20_000) {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val text = (param.args.getOrNull(0) as? CharSequence)?.toString()?.trim() ?: return
                        val lower = text.lowercase()
                        if (!lower.contains("sponsored") && lower != "ad" && !lower.contains("ad choices")) return
                        val view = param.thisObject as? View
                        signal("UI-A11Y text=${text.take(120)} view=${view?.javaClass?.name} stack=${shortStack(0, 10)}")
                    }
                })
                xlog("INSTALLED framework Sponsored/ad text probes")
            }.onFailure { xlog("framework text probe failed: ${it.javaClass.simpleName}: ${it.message}") }
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
                                val configured = (param.thisObject as? ClassLoader) ?: classLoader
                                application?.let { installFromCache(it, configured) }
                                installMarkerProbes(configured, "MultiDex.configure")
                            }
                        })
                    }
            }.onFailure { xlog("MultiDex readiness hook failed: ${it.javaClass.simpleName}: ${it.message}") }
        }

        private fun scheduleRetries(app: Application, classLoader: ClassLoader) {
            val handler = Handler(Looper.getMainLooper())
            longArrayOf(0L, 100L, 350L, 900L, 2_000L, 4_000L, 8_000L, 12_000L).forEachIndexed { index, delay ->
                handler.postDelayed({
                    installFromCache(app, classLoader)
                    installMarkerProbes(classLoader, "attach-retry-${index + 1}")
                }, delay)
            }
        }
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != HOST_PACKAGE) return
        xlog("MODULE LOADED process=${lpparam.processName} passive=true")
        installFrameworkSponsoredTextProbe()
        hookDexReadiness(lpparam.classLoader)
        if (!attachHookInstalled.compareAndSet(false, true)) return

        val attach = Application::class.java.getDeclaredMethod("attach", Context::class.java)
        attach.isAccessible = true
        XposedBridge.hookMethod(attach, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val app = param.thisObject as? Application ?: return
                application = app
                xlog("Application attached; passive Reels trace active")
                installFromCache(app, app.classLoader)
                scheduleRetries(app, app.classLoader)
            }
        })
    }

    private class CachedClassifier(
        private val modelInterfaces: List<Pair<Class<*>, Method>>
    ) {
        private val chainCache = ConcurrentHashMap<Class<*>, List<List<Method>>>()
        private val misses: MutableSet<Class<*>> = ConcurrentHashMap.newKeySet()

        fun modelClassification(model: Any?): String? {
            if (model == null) return null
            for ((iface, method) in modelInterfaces) {
                if (!iface.isInstance(model)) continue
                return runCatching { method.invoke(model)?.toString() }.getOrNull()
            }
            return null
        }

        fun classificationOf(item: Any?): String? {
            if (item == null) return null
            modelClassification(item)?.let { return it }
            val chains = resolveChains(item.javaClass) ?: return null
            for (chain in chains) {
                var current: Any? = item
                for (accessor in chain) {
                    current = runCatching { accessor.invoke(current) }.getOrNull() ?: break
                }
                modelClassification(current)?.let { return it }
            }
            return null
        }

        private fun resolveChains(clazz: Class<*>): List<List<Method>>? {
            chainCache[clazz]?.let { return it }
            if (misses.contains(clazz)) return null
            val resolved = runCatching { findChains(clazz) }.getOrNull()
            if (resolved.isNullOrEmpty()) {
                misses.add(clazz)
                return null
            }
            chainCache[clazz] = resolved
            return resolved
        }

        private fun findChains(clazz: Class<*>): List<List<Method>> {
            data class Node(val type: Class<*>, val chain: List<Method>)
            val found = ArrayList<List<Method>>()
            val queue = ArrayDeque<Node>()
            val bestDepth = HashMap<Class<*>, Int>()
            queue.add(Node(clazz, emptyList()))
            bestDepth[clazz] = 0

            while (queue.isNotEmpty() && found.size < 24) {
                val node = queue.removeFirst()
                if (node.chain.size >= 4) continue
                node.type.methods
                    .asSequence()
                    .filter { !Modifier.isStatic(it.modifiers) && it.parameterCount == 0 && it.returnType != Void.TYPE }
                    .take(48)
                    .forEach { candidate ->
                        val returnType = candidate.returnType
                        val next = node.chain + candidate
                        if (modelInterfaces.any { (iface, _) -> iface.isAssignableFrom(returnType) }) {
                            candidate.isAccessible = true
                            found.add(next)
                            return@forEach
                        }
                        if (
                            returnType == node.type || returnType == clazz || returnType.isPrimitive ||
                            returnType == Void.TYPE || returnType == String::class.java || returnType.isEnum ||
                            returnType.isArray || Collection::class.java.isAssignableFrom(returnType) ||
                            Map::class.java.isAssignableFrom(returnType) || returnType.name.startsWith("java.") ||
                            returnType.name.startsWith("kotlin.") || returnType.name.startsWith("android.")
                        ) return@forEach
                        val depth = next.size
                        val previous = bestDepth[returnType]
                        if (previous == null || depth < previous) {
                            candidate.isAccessible = true
                            bestDepth[returnType] = depth
                            queue.add(Node(returnType, next))
                        }
                    }
            }
            return found
        }
    }
}
