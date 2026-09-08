#!/usr/bin/env python3
from pathlib import Path

# Applied after patch_v1_9.py + patch_v1_9_4.py + patch_v1_9_4_fix.py.
# Updates the exact early hooks for Facebook 577.0.0.50.72 / 474426253 while
# retaining the known-good Facebook 576 fallbacks.

p = Path('app/src/main/java/tn/loukious/facebookappadsremover/Patches.kt')
s = p.read_text(encoding='utf-8')

# Keep the existing public entry point used by Module.java, but make its logs and
# comments clear that it now supports both Facebook 576 and 577.
s = s.replace(
    '// Facebook 576.0.0.42.73 / 474227118 account-specific video/Reels ad fast\n'
    '// paths. These are exact fallbacks for the user\'s current build and fail\n'
    '// harmlessly when Facebook changes the obfuscated names.\n',
    '// Facebook 576.0.0.42.73 / 474227118 and Facebook 577.0.0.50.72 /\n'
    '// 474426253 account-specific video/Reels ad fast paths. These exact\n'
    '// fallbacks install before the slower DexKit scan and fail harmlessly when\n'
    '// Facebook changes obfuscated names again.\n',
    1,
)
s = s.replace(
    'AndroidLog.i(TAG, "Blocked Facebook 576 ad path: $className.$methodName")',
    'AndroidLog.i(TAG, "Blocked Facebook fast ad path: $className.$methodName")',
    1,
)
s = s.replace(
    'AndroidLog.i(TAG, "Installed Facebook 576 ad hook: $className.$methodName")',
    'AndroidLog.i(TAG, "Installed Facebook fast ad hook: $className.$methodName")',
    1,
)

# Insert Facebook 577 exact fast paths immediately before the final status log.
log_anchor = '''    AndroidLog.i(
        TAG,
        "Facebook 576 ad hooks: reelsPrimary=$primaryReelsFetch reelsDispatch=$reelsAdDispatcher " +
            "commercialBreak=$commercialBreakDispatcher profileReels=$profileReelsFetch " +
            "reelsPrefetch=$reelsBackgroundPrefetch watchMain=$mainFetcher watchHelper=$helperFetcher " +
            "similar=$similarAdTrigger rti=$rtiTrigger poe=$poeTrigger " +
            "adChannel=$adChannel interstitial=$interstitialBuilder"
    )
'''
if log_anchor not in s:
    raise SystemExit('ERROR: Facebook 576 fast-path status log anchor not found')

fb577 = '''    // Facebook 577.0.0.50.72 / 474426253 renamed the obfuscated Reels and
    // commercial-break classes while retaining the same stable query strings.
    // These exact hooks close the cold-start window before DexKit can resolve
    // the corresponding stable-string methods.
    val primaryReelsFetch577 = hookExact(
        "X.7ez",
        "A0B",
        listOf("com.facebook.auth.usersession.FbUserSession", "X.7ej", "int")
    )

    val reelsAdDispatcher577 = hookExact(
        "X.7ez",
        "A09",
        listOf(
            "com.facebook.auth.usersession.FbUserSession",
            "X.7ez",
            "X.7ej",
            "int"
        )
    )

    val commercialBreakDispatcher577 = hookExact(
        "X.SQq",
        "A02",
        listOf(
            "X.SRC",
            "X.3zW",
            "X.V9M",
            "X.7ej",
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

    val profileReelsFetch577 = hookExact(
        "X.B5Y",
        "A03",
        listOf(
            "com.facebook.auth.usersession.FbUserSession",
            "java.lang.Integer",
            "java.lang.Integer",
            "boolean"
        )
    )

    val mainFetcher577 = hookExact(
        "X.SMj",
        "A05",
        listOf(
            "com.facebook.auth.usersession.FbUserSession",
            "X.3zW",
            "X.56N",
            "int",
            "boolean",
            "boolean"
        )
    )

    val helperFetcher577 = hookExact(
        "X.SMM",
        "A07",
        listOf(
            "com.facebook.auth.usersession.FbUserSession",
            "X.3zW",
            "X.3zW",
            "X.SMM"
        )
    )

    val similarAdTrigger577 = hookExact("X.6V0", "invoke", emptyList())
    val rtiTrigger577 = hookExact("X.6V3", "invoke", emptyList())
    val poeTrigger577 = hookExact("X.6V6", "invoke", emptyList())
    val adChannel577 = hookExact("X.53Y", "Ael", listOf("X.1kN", "X.5Cd"))

'''
new_log = fb577 + '''    AndroidLog.i(
        TAG,
        "Facebook 576/577 ad hooks: " +
            "576[reelsPrimary=$primaryReelsFetch reelsDispatch=$reelsAdDispatcher " +
            "commercialBreak=$commercialBreakDispatcher profileReels=$profileReelsFetch " +
            "reelsPrefetch=$reelsBackgroundPrefetch watchMain=$mainFetcher watchHelper=$helperFetcher " +
            "similar=$similarAdTrigger rti=$rtiTrigger poe=$poeTrigger adChannel=$adChannel " +
            "interstitial=$interstitialBuilder] " +
            "577[reelsPrimary=$primaryReelsFetch577 reelsDispatch=$reelsAdDispatcher577 " +
            "commercialBreak=$commercialBreakDispatcher577 profileReels=$profileReelsFetch577 " +
            "watchMain=$mainFetcher577 watchHelper=$helperFetcher577 similar=$similarAdTrigger577 " +
            "rti=$rtiTrigger577 poe=$poeTrigger577 adChannel=$adChannel577]"
    )
'''
s = s.replace(log_anchor, new_log, 1)

# Future-proof the slower DexKit pass too. These stable strings resolve the new
# 577 methods even when the next Facebook build changes X.* names again.
dynamic_anchor = '''    hookVoidByStrings(
        "all Reels ad dispatchers",
        "reels_ad_query_send"
    )
'''
if dynamic_anchor not in s:
    raise SystemExit('ERROR: Reels dispatcher dynamic hook anchor not found')
dynamic_extra = dynamic_anchor + '''    hookVoidByStrings(
        "commercial-break video ad dispatchers",
        "commercial_break_query_send",
        "Kicking off video ad fetch"
    )
    hookVoidByStrings(
        "ad-break server fetchers",
        "AdBreakServerAPI",
        "Fetch adbreak when already fetching",
        "PRE_ROLL"
    )
'''
s = s.replace(dynamic_anchor, dynamic_extra, 1)

p.write_text(s, encoding='utf-8')

# Bump the module so LSPosed/Android sees this as a new build and cached hook
# metadata is invalidated for the updated Facebook version.
b = Path('app/build.gradle.kts')
bs = b.read_text(encoding='utf-8')
if 'versionCode = 14' not in bs or 'versionName = "1.9.4-patched"' not in bs:
    raise SystemExit('ERROR: expected v1.9.4 patched version fields not found')
bs = bs.replace('versionCode = 14', 'versionCode = 15', 1)
bs = bs.replace('versionName = "1.9.4-patched"', 'versionName = "1.9.5-patched"', 1)
b.write_text(bs, encoding='utf-8')

print('Added Facebook 577.0.0.50.72 / 474426253 early exact hooks')
print('Mapped Reels fetch/dispatcher X.7ez and request model X.7ej')
print('Mapped RTI/POE/similar-ad builders X.6V3/X.6V6/X.6V0')
print('Mapped Reels ad channel X.53Y.Ael and Profile Reels X.B5Y.A03')
print('Mapped commercial/ad-break paths X.SQq, X.SMj and X.SMM')
print('Added stable-string commercial/ad-break fallback hooks')
print('Set version to 1.9.5-patched (15)')
