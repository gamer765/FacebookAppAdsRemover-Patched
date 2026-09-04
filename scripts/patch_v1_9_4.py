#!/usr/bin/env python3
from pathlib import Path

# This patch is applied after scripts/patch_v1_9.py (v1.9.3 base).

p = Path('app/src/main/java/tn/loukious/facebookappadsremover/Patches.kt')
s = p.read_text(encoding='utf-8')

# Make the exact Facebook 576 fast-path installer callable from Module.java.
s = s.replace(
    'private fun installFacebook576VideoInterruptionFastPaths(classLoader: ClassLoader) {',
    'fun installFacebook576VideoInterruptionFastPaths(classLoader: ClassLoader) {',
    1,
)

# The fast-path installer is retried at Application.attach and MultiDex readiness,
# so make method installation idempotent.
anchor = 'fun installFacebook576VideoInterruptionFastPaths(classLoader: ClassLoader) {\n'
if anchor not in s:
    raise SystemExit('ERROR: fast-path function not found')
if 'facebook576FastPathHookedMethods' not in s:
    s = s.replace(
        anchor,
        'private val facebook576FastPathHookedMethods: MutableSet<Method> =\\n'
        '    Collections.synchronizedSet(HashSet())\\n\\n'
        + anchor,
        1,
    )

needle = '''        val method = runCatching {
            clazz.getDeclaredMethod(methodName, *parameterTypes)
        }.getOrNull() ?: return false

        method.isAccessible = true
'''
replacement = '''        val method = runCatching {
            clazz.getDeclaredMethod(methodName, *parameterTypes)
        }.getOrNull() ?: return false

        if (!facebook576FastPathHookedMethods.add(method)) return true
        method.isAccessible = true
'''
if needle not in s:
    raise SystemExit('ERROR: hookExact method anchor not found')
s = s.replace(needle, replacement, 1)

# Primitive long is needed for the commercial-break dispatcher signature.
s = s.replace(
    '        "int" -> Int::class.javaPrimitiveType\n        "boolean" -> Boolean::class.javaPrimitiveType',
    '        "int" -> Int::class.javaPrimitiveType\n        "long" -> Long::class.javaPrimitiveType\n        "boolean" -> Boolean::class.javaPrimitiveType',
    1,
)

primary_block = '''    val primaryReelsFetch = hookExact(
        "X.7kx",
        "A0B",
        listOf("com.facebook.auth.usersession.FbUserSession", "X.6iw", "int")
    )
'''
extra_dispatch = primary_block + '''
    // A0B is the POSTLOOP query builder. The normal interruption-ad path also
    // goes through A09, which logs reels_ad_query_send and constructs the
    // low-level FBFetchReelsVideoAdsQuery future directly. Blocking A09 closes
    // the path responsible for the "Reel continues after the ad" creative.
    val reelsAdDispatcher = hookExact(
        "X.7kx",
        "A09",
        listOf(
            "com.facebook.auth.usersession.FbUserSession",
            "X.7kx",
            "X.6iw",
            "int"
        )
    )
'''
if primary_block not in s:
    raise SystemExit('ERROR: primary Reels fast-path block not found')
s = s.replace(primary_block, extra_dispatch, 1)

# The commercial-break dispatcher ultimately kicks off the video-ad future and
# is a void request path. This is an additional safety net for account-specific
# Reels interruption ads that enter through the instream state machine.
profile_anchor = '''    // Profile-Reels async ad request.
'''
commercial = '''    // Commercial-break / instream video ad request dispatcher. Its bytecode
    // contains commercial_break_query_send, "Kicking off video ad fetch", and
    // ADS_NOT_LOADED and ultimately calls the same low-level video-ad fetcher.
    val commercialBreakDispatcher = hookExact(
        "X.aXM",
        "A03",
        listOf(
            "X.aY2",
            "X.44V",
            "X.d9g",
            "X.6iw",
            "java.lang.Boolean",
            "java.lang.Integer",
            "java.lang.String",
            "java.lang.String",
            "java.lang.String",
            "java.lang.String",
            "int",
            "int",
            "long",
            "boolean",
            "boolean",
            "boolean"
        )
    )

'''
if profile_anchor not in s:
    raise SystemExit('ERROR: profile Reels anchor not found')
s = s.replace(profile_anchor, commercial + profile_anchor, 1)

# The full DexKit pass should also block every void Reels dispatcher carrying
# reels_ad_query_send, not only the method that also embeds the GraphQL name.
primary_dynamic = '''    hookVoidByStrings(
        "primary Reels sponsored fetch",
        "FBFetchReelsVideoAdsQuery",
        "reels_ad_query_send"
    )
'''
all_dispatch = primary_dynamic + '''    hookVoidByStrings(
        "all Reels ad dispatchers",
        "reels_ad_query_send"
    )
'''
if primary_dynamic not in s:
    raise SystemExit('ERROR: dynamic primary Reels hook anchor not found')
s = s.replace(primary_dynamic, all_dispatch, 1)

# Include the new fast-path states in the always-on release log.
s = s.replace(
    '"Facebook 576 ad hooks: reelsPrimary=$primaryReelsFetch profileReels=$profileReelsFetch " +',
    '"Facebook 576 ad hooks: reelsPrimary=$primaryReelsFetch reelsDispatch=$reelsAdDispatcher " +\n'
    '            "commercialBreak=$commercialBreakDispatcher profileReels=$profileReelsFetch " +',
    1,
)

p.write_text(s, encoding='utf-8')

# Install exact Facebook-576 hooks synchronously at Application.attach, before
# the 3-second DexKit pass and before Reels has a chance to prefetch an ad.
m = Path('app/src/main/java/tn/loukious/facebookappadsremover/Module.java')
ms = m.read_text(encoding='utf-8')
attach_anchor = '''                sApplication = application;
                loadCachedFeedGuardCandidates(application);
'''
attach_replacement = '''                sApplication = application;
                try {
                    PatchesKt.installFacebook576VideoInterruptionFastPaths(application.getClassLoader());
                } catch (Throwable throwable) {
                    Log.e(TAG, "Failed early Facebook 576 video-ad hooks at Application.attach", throwable);
                }
                loadCachedFeedGuardCandidates(application);
'''
if attach_anchor not in ms:
    raise SystemExit('ERROR: Application.attach anchor not found')
ms = ms.replace(attach_anchor, attach_replacement, 1)

# Retry immediately when Facebook finishes configuring secondary dex. The exact
# installer is idempotent, so repeated readiness callbacks are safe.
dex_anchor = '''    private static void tryInstallFastFeedHooksAtDexReady(
            ClassLoader classLoader,
            String readinessSource
    ) {
        tryInstallFeedComponentGuard(classLoader, readinessSource);
'''
dex_replacement = '''    private static void tryInstallFastFeedHooksAtDexReady(
            ClassLoader classLoader,
            String readinessSource
    ) {
        try {
            PatchesKt.installFacebook576VideoInterruptionFastPaths(classLoader);
        } catch (Throwable throwable) {
            Log.e(TAG, "Failed Facebook 576 video-ad hooks at " + readinessSource, throwable);
        }
        tryInstallFeedComponentGuard(classLoader, readinessSource);
'''
if dex_anchor not in ms:
    raise SystemExit('ERROR: MultiDex readiness anchor not found')
ms = ms.replace(dex_anchor, dex_replacement, 1)
m.write_text(ms, encoding='utf-8')

b = Path('app/build.gradle.kts')
bs = b.read_text(encoding='utf-8')
if 'versionCode = 13' not in bs or 'versionName = "1.9.3-patched"' not in bs:
    raise SystemExit('ERROR: expected v1.9.3 patched version fields not found')
bs = bs.replace('versionCode = 13', 'versionCode = 14', 1)
bs = bs.replace('versionName = "1.9.3-patched"', 'versionName = "1.9.4-patched"', 1)
b.write_text(bs, encoding='utf-8')

print('Added early Application.attach / MultiDex exact hooks')
print('Blocked X.7kx.A09 Reels ad dispatcher in addition to X.7kx.A0B')
print('Blocked X.aXM.A03 commercial-break video-ad dispatcher')
print('Added full-scan reels_ad_query_send void-method blocker')
print('Set version to 1.9.4-patched (14)')
