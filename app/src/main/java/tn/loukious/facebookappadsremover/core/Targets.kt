package tn.loukious.facebookappadsremover.core

import org.luckypray.dexkit.DexKitBridge

/**
 * Probe targets for M1 — these validate the discovery pipeline against a real
 * Facebook install before feature work begins.
 *
 * All anchors below were verified against the installed stock build
 * (576.0.0.42.73) — both against the decompiled secondary dex sources and
 * live via DexKit on-device:
 *
 *  - Cookie filter class: builds ImmutableSet("xs", "c_user", "datr", "fr").
 *    The four-name AND query is globally unique (C86164oW in this build; the
 *    original-mod decompile called it X.5Dx).
 *  - Story field registry: the giant method registering every GraphQL story
 *    field. "sponsored_data" alone has 5 hits; adding the unique
 *    "bloks_ads_context_header" narrows to exactly one (C717644f.A04, the
 *    X.3WJ equivalent in the original-mod decompile).
 *  - "SPONSORED" alone matched 63 methods — unusable as an anchor. The feed
 *    row-type enum needs M2 investigation; GraphQLFeedStoryCategory (the
 *    story category enum with PROMOTION values) is probed as a stable class
 *    instead.
 */
object ProbeTargets {

    val all: List<HookTarget> = listOf(
        HookTarget(
            key = "probe.cookieSync",
            description = "Session cookie filter class (xs/c_user/datr/fr)",
        ) { bridge: DexKitBridge ->
            bridge.findMethod {
                matcher {
                    usingStrings("xs", "c_user", "datr", "fr")
                }
            }
        },
        HookTarget(
            key = "probe.storyFieldRegistry",
            description = "GraphQL story field registry method (sponsored_data + bloks_ads_context_header)",
        ) { bridge: DexKitBridge ->
            bridge.findMethod {
                matcher {
                    usingStrings("sponsored_data", "bloks_ads_context_header")
                }
            }
        },
    )

    /**
     * Classes that are NOT obfuscated in current Facebook builds — resolvable
     * directly via Class.forName once the secondary dex is loaded. Used as
     * sanity probes and later as stable hook anchors where possible.
     */
    val stableClasses = listOf(
        "com.facebook.ads.AdsScreenshotDetector",
        "com.facebook.graphql.model.GraphQLStory",
        "com.facebook.auth.credentials.SessionCookie",
        "com.facebook.auth.usersession.FbUserSession",
        "com.crossapp.graphql.facebook.enums.GraphQLFeedStoryCategory",
    )
}
