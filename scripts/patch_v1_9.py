#!/usr/bin/env python3
from pathlib import Path

p = Path('app/src/main/java/tn/loukious/facebookappadsremover/Patches.kt')
s = p.read_text(encoding='utf-8')

old_decl = '''    // Item class -> candidate accessor chains (each chain is a sequence of
    // zero-arg getters from item to model). Null entries are cached misses.
    private val accessorChainsCache = ConcurrentHashMap<Class<*>, List<List<Method>>?>()
'''
new_decl = '''    // Item class -> candidate accessor chains (each chain is a sequence of
    // zero-arg getters from item to model). ConcurrentHashMap rejects null values,
    // so misses are tracked separately instead of being written as null entries.
    private val accessorChainsCache = ConcurrentHashMap<Class<*>, List<List<Method>>>()
    private val accessorChainMissCache = ConcurrentHashMap<Class<*>, Boolean>()
'''
if old_decl not in s:
    raise SystemExit('ERROR: accessorChainsCache declaration not found; expected upstream tag 1.9')
s = s.replace(old_decl, new_decl, 1)

old_resolver = '''    private fun resolveAccessorChains(clazz: Class<*>): List<List<Method>>? {
        accessorChainsCache.get(clazz)?.let { return it }
        val chains = runCatching { findAccessorChains(clazz) }.getOrNull()
        accessorChainsCache[clazz] = chains
        return chains
    }
'''
new_resolver = '''    private fun resolveAccessorChains(clazz: Class<*>): List<List<Method>>? {
        accessorChainsCache[clazz]?.let { return it }
        if (accessorChainMissCache.containsKey(clazz)) return null

        val chains = runCatching { findAccessorChains(clazz) }.getOrNull()
        if (chains == null) {
            accessorChainMissCache[clazz] = true
        } else {
            accessorChainsCache[clazz] = chains
        }
        return chains
    }
'''
if old_resolver not in s:
    raise SystemExit('ERROR: resolveAccessorChains block not found; expected upstream tag 1.9')
s = s.replace(old_resolver, new_resolver, 1)

old_finder = '''    // Direct accessors first (item method returning a model interface), then
    // two-hop chains through a holder object (576 reels items expose the media
    // model via item.A04().A00()-style getter pairs). Only one hop through
    // non-trivial types; deeper nesting has not been seen.
    private fun findAccessorChains(clazz: Class<*>): List<List<Method>>? {
        val chains = ArrayList<List<Method>>()
        zeroArgMethods(clazz).forEach { candidate ->
            if (modelInterfaces.any { (iface, _) -> candidate.returnType == iface }) {
                chains.add(listOf(candidate))
            }
        }
        zeroArgMethods(clazz).forEach { candidate ->
            val holder = candidate.returnType
            if (holder == clazz || holder.isPrimitive || holder == Void.TYPE ||
                holder == String::class.java ||
                Collection::class.java.isAssignableFrom(holder) ||
                holder.name.startsWith("java.")
            ) return@forEach
            zeroArgMethods(holder).forEach { nested ->
                if (modelInterfaces.any { (iface, _) -> nested.returnType == iface }) {
                    chains.add(listOf(candidate, nested))
                }
            }
        }
        return chains.ifEmpty { null }
    }
'''
new_finder = '''    // Resolve accessor chains structurally. Account-specific sponsored Reel
    // wrappers can add extra holder layers, and some getters declare the concrete
    // model implementation instead of the model interface. Search up to four
    // zero-arg getter hops and accept assignable concrete return types.
    private fun findAccessorChains(clazz: Class<*>): List<List<Method>>? {
        data class AccessorNode(val type: Class<*>, val chain: List<Method>)

        val chains = ArrayList<List<Method>>()
        val queue = ArrayDeque<AccessorNode>()
        val bestDepth = HashMap<Class<*>, Int>()
        queue.add(AccessorNode(clazz, emptyList()))
        bestDepth[clazz] = 0

        while (queue.isNotEmpty() && chains.size < 24) {
            val node = queue.removeFirst()
            if (node.chain.size >= 4) continue

            zeroArgMethods(node.type).take(48).forEach { candidate ->
                val returnType = candidate.returnType
                val nextChain = node.chain + candidate

                if (modelInterfaces.any { (iface, _) -> iface.isAssignableFrom(returnType) }) {
                    chains.add(nextChain)
                    return@forEach
                }

                if (returnType == node.type || returnType == clazz ||
                    returnType.isPrimitive || returnType == Void.TYPE ||
                    returnType == String::class.java || returnType.isEnum ||
                    returnType.isArray ||
                    Collection::class.java.isAssignableFrom(returnType) ||
                    Map::class.java.isAssignableFrom(returnType) ||
                    returnType.name.startsWith("java.") ||
                    returnType.name.startsWith("kotlin.") ||
                    returnType.name.startsWith("android.")
                ) return@forEach

                val depth = nextChain.size
                val previousDepth = bestDepth[returnType]
                if (previousDepth == null || depth < previousDepth) {
                    bestDepth[returnType] = depth
                    queue.add(AccessorNode(returnType, nextChain))
                }
            }
        }
        return chains.ifEmpty { null }
    }
'''
if old_finder not in s:
    raise SystemExit('ERROR: findAccessorChains block not found; expected upstream tag 1.9')
s = s.replace(old_finder, new_finder, 1)

anchor = '''        runCatching { installReelsAdDiagnostics(classLoader, bridge) }
            .onFailure { Log.w(TAG, "Failed to install Reels ad diagnostics", it) }
'''
install = anchor + '''        runCatching { installReelsPrimarySponsoredFetchBlock(classLoader, bridge) }
            .onFailure { AndroidLog.e(TAG, "Failed to install primary Reels sponsored fetch block", it) }
        runCatching { installReelsAsyncAdNetworkBlock(classLoader, bridge) }
            .onFailure { AndroidLog.e(TAG, "Failed to install Reels async ad network block", it) }
        runCatching { installFacebook576VideoInterruptionFastPaths(classLoader) }
            .onFailure { AndroidLog.e(TAG, "Failed to install Facebook 576 video interruption fast paths", it) }
'''
if anchor not in s:
    raise SystemExit('ERROR: Reels diagnostics install anchor not found; expected upstream tag 1.9')
s = s.replace(anchor, install, 1)

extra = r'''

// ---------------------------------------------------------------------------
// Primary full-page sponsored Reel fetchers. In Facebook 576.0.0.42.73 the
// main Reels ad loader contains the stable query name FBFetchReelsVideoAdsQuery
// plus the reels_ad_query_send marker and returns void. No-oping that request
// method prevents ordinary sponsored Reel cards (including delayed/"Skip ad"
// creatives) from entering the pager. Profile Reels has a separate async-ad
// query and is blocked the same way. Background ad prefetch is also disabled.
// ---------------------------------------------------------------------------
private fun installReelsPrimarySponsoredFetchBlock(classLoader: ClassLoader, bridge: DexKitBridge) {
    fun hookVoidByStrings(label: String, vararg needles: String): Int {
        var installed = 0
        runCatching {
            bridge.findMethod {
                matcher {
                    usingStrings(*needles)
                    returnType = "void"
                }
            }.forEach { methodData ->
                val method = runCatching { methodData.getMethodInstance(classLoader) }.getOrNull()
                    ?: return@forEach
                method.isAccessible = true
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        param.result = null
                        AndroidLog.i(TAG, "Blocked $label: ${method.declaringClass.name}.${method.name}")
                    }
                })
                installed++
                AndroidLog.i(TAG, "Installed $label block: ${method.declaringClass.name}.${method.name}")
            }
        }.onFailure { AndroidLog.e(TAG, "$label resolution failed", it) }
        if (installed == 0) AndroidLog.w(TAG, "$label target not found")
        return installed
    }

    hookVoidByStrings(
        "primary Reels sponsored fetch",
        "FBFetchReelsVideoAdsQuery",
        "reels_ad_query_send"
    )
    hookVoidByStrings("profile Reels async sponsored fetch", "ProfileReelsAsyncAdsQuery")
    hookVoidByStrings(
        "Reels ads background prefetch",
        "ReelsAdsBackgroundPrefetchInitializer",
        "ReelsAdsPeriodicPrefetch"
    )
}

// ---------------------------------------------------------------------------
// Reels async-ad network channel. Facebook 576 routes full-page sponsored Reel
// cards through VideoHomeCSRNetworkRequester.doAdChannelNetworkRequest with a
// ReelsAsyncAdsNetworkRequestParams payload. Blocking this method stops the ad
// response before it can be inserted into the Reels pager. The stable trace
// string is used rather than the obfuscated X.* class name.
// ---------------------------------------------------------------------------
private fun installReelsAsyncAdNetworkBlock(classLoader: ClassLoader, bridge: DexKitBridge) {
    var installed = 0
    runCatching {
        bridge.findMethod {
            matcher {
                usingStrings("VideoHomeCSRNetworkRequester.doAdChannelNetworkRequest")
                returnType = "void"
                paramCount = 2
            }
        }.forEach { methodData ->
            val method = runCatching { methodData.getMethodInstance(classLoader) }.getOrNull()
                ?: return@forEach
            method.isAccessible = true
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    param.result = null
                    AndroidLog.i(
                        TAG,
                        "Blocked Reels async ad channel: ${method.declaringClass.name}.${method.name}"
                    )
                }
            })
            installed++
            AndroidLog.i(
                TAG,
                "Installed Reels async ad channel block: ${method.declaringClass.name}.${method.name}"
            )
        }
    }.onFailure {
        AndroidLog.e(TAG, "Reels async ad channel resolution failed", it)
    }
    if (installed == 0) {
        AndroidLog.w(TAG, "Reels async ad channel target not found")
    }
}

// ---------------------------------------------------------------------------
// Facebook 576.0.0.42.73 / 474227118 account-specific video/Reels ad fast
// paths. These are exact fallbacks for the user's current build and fail
// harmlessly when Facebook changes the obfuscated names.
// ---------------------------------------------------------------------------
private fun installFacebook576VideoInterruptionFastPaths(classLoader: ClassLoader) {
    fun resolveClass(name: String): Class<*>? =
        runCatching { Class.forName(name, false, classLoader) }.getOrNull()

    fun resolveType(name: String): Class<*>? = when (name) {
        "int" -> Int::class.javaPrimitiveType
        "boolean" -> Boolean::class.javaPrimitiveType
        else -> resolveClass(name)
    }

    fun hookExact(
        className: String,
        methodName: String,
        parameterNames: List<String>
    ): Boolean {
        val clazz = resolveClass(className) ?: return false
        val parameterTypes = parameterNames.map { resolveType(it) ?: return false }.toTypedArray()
        val method = runCatching {
            clazz.getDeclaredMethod(methodName, *parameterTypes)
        }.getOrNull() ?: return false

        method.isAccessible = true
        XposedBridge.hookMethod(method, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                param.result = null
                AndroidLog.i(TAG, "Blocked Facebook 576 ad path: $className.$methodName")
            }
        })
        AndroidLog.i(TAG, "Installed Facebook 576 ad hook: $className.$methodName")
        return true
    }

    // Main Reels sponsored-video request. This is the strongest exact target
    // found in 474227118: it is a void method dedicated to
    // FBFetchReelsVideoAdsQuery and logs reels_ad_query_send.
    val primaryReelsFetch = hookExact(
        "X.7kx",
        "A0B",
        listOf("com.facebook.auth.usersession.FbUserSession", "X.6iw", "int")
    )

    // Profile-Reels async ad request.
    val profileReelsFetch = hookExact(
        "X.AAi",
        "A03",
        listOf(
            "com.facebook.auth.usersession.FbUserSession",
            "java.lang.Integer",
            "java.lang.Integer",
            "boolean"
        )
    )

    // Reels ad-only background prefetch job.
    val reelsBackgroundPrefetch = hookExact(
        "com.facebook.video.videohome.prefetching.ads.background.ReelsAdsBackgroundPrefetchAppJob",
        "A00",
        emptyList()
    )

    // Long-form Watch / pre-roll / mid-roll ad-break fetchers.
    val mainFetcher = hookExact(
        "X.aaR",
        "A05",
        listOf(
            "com.facebook.auth.usersession.FbUserSession",
            "X.44V",
            "X.4x2",
            "int",
            "boolean",
            "boolean"
        )
    )

    val helperFetcher = hookExact(
        "X.aa6",
        "A07",
        listOf(
            "com.facebook.auth.usersession.FbUserSession",
            "X.44V",
            "X.44V",
            "X.aa6"
        )
    )

    // Reels async-ad builders. 6UG builds a PAGE_MOBILE_REELS request tagged
    // fb_shorts_similar_ad; 6UH and 6UI are the RTI/POE paths.
    val similarAdTrigger = hookExact("X.6UG", "invoke", emptyList())
    val rtiTrigger = hookExact("X.6UH", "invoke", emptyList())
    val poeTrigger = hookExact("X.6UI", "invoke", emptyList())

    // Exact fallback for the stable-string network hook above.
    val adChannel = hookExact("X.4zL", "Adv", listOf("X.1s2", "X.3wD"))

    // Another interstitial GraphQL builder used by the Reels async-ad stack.
    val interstitialBuilder = hookExact(
        "X.6Bl",
        "A00",
        listOf("X.6Bl", "kotlin.jvm.functions.Function1")
    )

    AndroidLog.i(
        TAG,
        "Facebook 576 ad hooks: reelsPrimary=$primaryReelsFetch profileReels=$profileReelsFetch " +
            "reelsPrefetch=$reelsBackgroundPrefetch watchMain=$mainFetcher watchHelper=$helperFetcher " +
            "similar=$similarAdTrigger rti=$rtiTrigger poe=$poeTrigger " +
            "adChannel=$adChannel interstitial=$interstitialBuilder"
    )
}
'''

if 'private fun installReelsPrimarySponsoredFetchBlock' not in s:
    s += extra

p.write_text(s, encoding='utf-8')

b = Path('app/build.gradle.kts')
bs = b.read_text(encoding='utf-8')
if 'versionCode = 10' not in bs or 'versionName = "1.9"' not in bs:
    raise SystemExit('ERROR: expected v1.9 version fields not found')
bs = bs.replace('versionCode = 10', 'versionCode = 13', 1)
bs = bs.replace('versionName = "1.9"', 'versionName = "1.9.3-patched"', 1)
b.write_text(bs, encoding='utf-8')

print('Patched ReelsAdClassifier ConcurrentHashMap null-cache bug')
print('Expanded Reels classifier to four structural getter hops + assignable model types')
print('Added FBFetchReelsVideoAdsQuery / ProfileReelsAsyncAdsQuery blockers')
print('Disabled Reels ad background prefetch')
print('Retained async-ad / RTI / POE / Watch ad-break blockers')
print('Set version to 1.9.3-patched (13)')
