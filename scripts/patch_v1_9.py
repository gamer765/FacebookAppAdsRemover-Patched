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

anchor = '''        runCatching { installReelsAdDiagnostics(classLoader, bridge) }
            .onFailure { Log.w(TAG, "Failed to install Reels ad diagnostics", it) }
'''
install = anchor + '''        runCatching { installReelsAsyncAdNetworkBlock(classLoader, bridge) }
            .onFailure { AndroidLog.e(TAG, "Failed to install Reels async ad network block", it) }
        runCatching { installFacebook576VideoInterruptionFastPaths(classLoader) }
            .onFailure { AndroidLog.e(TAG, "Failed to install Facebook 576 video interruption fast paths", it) }
'''
if anchor not in s:
    raise SystemExit('ERROR: Reels diagnostics install anchor not found; expected upstream tag 1.9')
s = s.replace(anchor, install, 1)

extra = r'''

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

    // Reels async-ad builders. 6UG is the path missed in 1.9.1: it builds a
    // PAGE_MOBILE_REELS request tagged fb_shorts_similar_ad with
    // ASYNC_ADS_REQUEST_TYPE_NONE. 6UH and 6UI are the RTI/POE paths.
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
        "Facebook 576 ad hooks: watchMain=$mainFetcher watchHelper=$helperFetcher " +
            "similar=$similarAdTrigger rti=$rtiTrigger poe=$poeTrigger " +
            "adChannel=$adChannel interstitial=$interstitialBuilder"
    )
}
'''

if 'private fun installReelsAsyncAdNetworkBlock' not in s:
    s += extra

p.write_text(s, encoding='utf-8')

b = Path('app/build.gradle.kts')
bs = b.read_text(encoding='utf-8')
if 'versionCode = 10' not in bs or 'versionName = "1.9"' not in bs:
    raise SystemExit('ERROR: expected v1.9 version fields not found')
bs = bs.replace('versionCode = 10', 'versionCode = 12', 1)
bs = bs.replace('versionName = "1.9"', 'versionName = "1.9.2-patched"', 1)
b.write_text(bs, encoding='utf-8')

print('Patched ReelsAdClassifier ConcurrentHashMap null-cache bug')
print('Added stable Reels async ad-channel network block')
print('Added Facebook 576 similar-ad / RTI / POE / interstitial fast paths')
print('Set version to 1.9.2-patched (12)')
