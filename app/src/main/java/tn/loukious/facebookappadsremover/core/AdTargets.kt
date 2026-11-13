package tn.loukious.facebookappadsremover.core

import org.luckypray.dexkit.DexKitBridge

/**
 * M2 ad/filter hook targets — a direct port of the original mod's hook map that
 * was recovered from libnc.so (three native installers):
 *
 *  - Feed installer (AdjzonOq0OhXgiiuU6H, libnc.so.c 1159885–1160989):
 *    11 fixed class.method hooks around the newsfeed ads channel, the
 *    sponsored-story pool/vend pipeline, and multi-ad row rendering.
 *  - Video-ads installer (Rn0LbcxLisWuSI9YThk, 1162992–1163868):
 *    instream ad-break fetch/render, tap-to-fullscreen ad fetch, in-content
 *    video ad render, video-home sponsored pool.
 *  - Banner installer (kEMr0xnlytyyfRWPypcd, 1211381–1215105): a runtime
 *    dex-scan for banner-ad classes (~40 QPL anchors), then hooking every
 *    boolean method (handled in AdFilterHook.installBannerScan).
 *  - Reels installer (ZZyGsYCYYeoF2gip6l1, 1165515–1166174): reels video-ad
 *    GraphQL fetch, banner-ad component render, shorts sponsored pool.
 *    Unportable hooks tracked in docs/unported-hooks.md.
 *
 * The mod hooked obfuscated names directly (X.3p5.A01, X.Xq6.A09, …) which
 * drift per release. The rewrite re-anchors each hook on strings that live
 * INSIDE the hooked method (log literals like
 * "FeedNetworkController.doAdChannelNetworkRequest"), verified against the
 * stock 576.0.0.42.73 decompile — those survive obfuscation. Method names are
 * NOT used as anchors.
 */
object AdTargets {

    val all: List<HookTarget> = listOf(

        // ---------------- Feed installer ----------------

        HookTarget(
            key = "ads.adChannelFetch",
            description = "FeedNetworkController ad-channel network request (mod: NEWSFEED_AD_CHANNEL_FETCH)",
            multi = true,
            action = HookAction.BLOCK_NULL,
        ) { bridge: DexKitBridge ->
            bridge.findMethod {
                matcher {
                    usingStrings("FeedNetworkController.doAdChannelNetworkRequest")
                }
            }
        },
        HookTarget(
            key = "ads.adChannelRequest",
            description = "Ads channel request builder incl. background prefetch (mod: NEWSFEED_AD_CHANNEL_REQUEST)",
            multi = true,
            action = HookAction.BLOCK_NULL,
        ) { bridge: DexKitBridge ->
            bridge.findMethod {
                matcher {
                    usingStrings("ADS_CHANNEL_BACKGROUND_PREFETCH")
                    paramCount(8)
                }
            }
        },
        HookTarget(
            key = "ads.sponsoredVend",
            description = "FeedSponsoredStoryHolder top-valid-ad vend (mod: NEWSFEED_SPONSORED_VEND)",
            multi = true,
            action = HookAction.BLOCK_NULL,
        ) { bridge: DexKitBridge ->
            bridge.findMethod {
                matcher {
                    usingStrings("FeedSponsoredStoryHolder.getTopValidAd")
                }
            }
        },
        HookTarget(
            key = "ads.sponsoredPoolAdd",
            description = "Sponsored pool add, rejects non-sponsored stories (mod: NEWSFEED_SPONSORED_POOL_ADD / VIDEO_HOME_AD_POOL_ADD)",
            multi = true,
            action = HookAction.BLOCK_NULL,
        ) { bridge: DexKitBridge ->
            bridge.findMethod {
                matcher {
                    usingStrings("Cannot add null or non-sponsored story")
                }
            }
        },
        HookTarget(
            key = "ads.sponsoredPaSelect",
            description = "FeedSponsoredPaExecutor ad-selection error path (mod: NEWSFEED_PA_SELECT/PA_INSERT)",
            multi = true,
            action = HookAction.BLOCK_NULL,
        ) { bridge: DexKitBridge ->
            bridge.findMethod {
                matcher {
                    usingStrings("not expected to be called in ad selection")
                }
            }
        },
        HookTarget(
            key = "ads.multiAdRender",
            description = "FBMultiAdsFeedUnit row rendering (mod: NEWSFEED_MULTI_AD_RENDER, X.OIh/OK6/OHH.render)",
            multi = true,
            action = HookAction.BLOCK_NULL,
        ) { bridge: DexKitBridge ->
            bridge.findMethod {
                matcher {
                    usingStrings("FBMultiAdsFeedUnitKComponent")
                    paramCount(1)
                }
            }
        },
        HookTarget(
            key = "ads.sponsoredAttachmentRender",
            description = "Sponsored attachment rendering, ad-checked (mod: NEWSFEED_SPONSORED_ATTACHMENT_RENDER, X.2nP.render)",
            multi = true,
            action = HookAction.SPONSORED_NULL,
        ) { bridge: DexKitBridge ->
            bridge.findMethod {
                matcher {
                    usingStrings("NPE of attachment story")
                }
            }
        },
        HookTarget(
            key = "ads.sponsoredRender",
            description = "Feed attachment component render, ad-checked (mod: NEWSFEED_SPONSORED_ROOT_RENDER, X.2U5.A17)",
            multi = true,
            action = HookAction.SPONSORED_NULL,
        ) { bridge: DexKitBridge ->
            bridge.findMethod {
                matcher {
                    usingStrings("Nothing Rendered.")
                }
            }
        },

        // ---------------- Video-ads installer ----------------

        HookTarget(
            key = "ads.videoAdFetch",
            description = "Instream image+video ad fetcher (mod: INSTREAM_IMAGE/VIDEO_AD_FETCH, X.Xq6.A08/A09)",
            multi = true,
            action = HookAction.BLOCK_NULL,
        ) { bridge: DexKitBridge ->
            bridge.findMethod {
                matcher {
                    usingStrings("Kicking off video ad fetch")
                }
            }
        },
        HookTarget(
            key = "ads.extendedBreaksFetch",
            description = "Extended ad-break fetch (mod: VIDEO_AD_BREAK_REQUEST group, X.Xoj)",
            multi = true,
            action = HookAction.BLOCK_NULL,
        ) { bridge: DexKitBridge ->
            bridge.findMethod {
                matcher {
                    usingStrings("Kicking off extended breaks fetch")
                }
            }
        },
        HookTarget(
            key = "ads.adBreakController",
            description = "Unified ad-break controller state machine (mod: VIDEO_AD_BREAK_REQUEST/STORY_SET, X.Xoj)",
            multi = true,
            action = HookAction.BLOCK_NULL,
        ) { bridge: DexKitBridge ->
            bridge.findMethod {
                matcher {
                    usingStrings("AdBreakStateMachine")
                }
            }
        },
        HookTarget(
            key = "ads.adBreakStorySet",
            description = "AdBreak state machine mCurrentAdBreakStory setter (mod: VIDEO_AD_BREAK_STORY_SET, X.Xoj.A0S -> C50373OCc.A0J)",
            multi = true,
            action = HookAction.BLOCK_NULL,
        ) { bridge: DexKitBridge ->
            // The setter's body softReports this literal when a fetched ad
            // break story arrives while one is already playing — unique in
            // both the mod's build (C75391Xoj.A0S) and stock 576 (C50373OCc.A0J).
            // Blocking it means mCurrentAdBreakStory is never assigned, so the
            // stories player (C53451PXl et al., which null-check that field)
            // never inserts an ad break between stories.
            bridge.findMethod {
                matcher {
                    usingStrings("Fetched and altered AdBreakStory when there's already an adbreak playing")
                    paramCount(3)
                }
            }
        },
        HookTarget(
            key = "ads.tapToFullscreenAdFetch",
            description = "Tap-to-fullscreen ad fetch (mod: VIDEO_TAP_TO_FULLSCREEN_AD_FETCH, X.Xpl)",
            multi = true,
            action = HookAction.BLOCK_FALSE,
        ) { bridge: DexKitBridge ->
            bridge.findMethod {
                matcher {
                    name("maybeFetchTapToFullscreenAd")
                    declaredClass {
                        usingStrings("Host story doesn't have a media attachment")
                    }
                }
            }
        },
        HookTarget(
            key = "ads.inContentVideoRender",
            description = "In-content video ad component (mod: IN_CONTENT_VIDEO_AD_RENDER, X.Xrh.render)",
            multi = true,
            action = HookAction.BLOCK_NULL,
        ) { bridge: DexKitBridge ->
            bridge.findMethod {
                matcher {
                    usingStrings("fb_in_content_ads")
                }
            }
        },
        HookTarget(
            key = "ads.videoHomeAdVend",
            description = "Video-home sponsored pool vend (mod: VIDEO_HOME_AD_VEND, X.5fA.A0C/A08)",
            multi = true,
            action = HookAction.BLOCK_NULL,
        ) { bridge: DexKitBridge ->
            bridge.findMethod {
                matcher {
                    usingStrings("-WVCDF-NO-AD")
                }
            }
        },

        // ---------------- Reels installer (ZZyGsYCYYeoF2gip6l1) ----------------
        // 9-hook map recovered from libnc.so.c 1165515–1166174. Obfuscated names
        // (X.7P4 / X.XoY / X.Xro / X.4rB / X.4pC in the mod's build) drift per
        // release, so each hook is re-anchored on in-method literals verified
        // against the installed 576.0.0.42.73 decompile. The rest of the map
        // has no stable anchor and is tracked in docs/unported-hooks.md.

        HookTarget(
            key = "ads.reelsVideoAdQuery",
            description = "Reels video-ads GraphQL query build (mod: REELS_AD_FETCH_DISPATCH + REELS_VIDEO_AD_QUERY, X.7P4.A06/A07)",
            multi = true,
            action = HookAction.BLOCK_NULL,
        ) { bridge: DexKitBridge ->
            // Only the void query builders are null-safe. The same literal
            // also sits in a ListenableFuture-returning fetcher (X.6yP.A07
            // on 576) whose callers would NPE on a null result — leave that
            // path to the video-ads installer targets above.
            bridge.findMethod {
                matcher {
                    usingStrings("FBFetchReelsVideoAdsQuery")
                }
            }.filter { it.returnTypeName == "void" }
        },
        HookTarget(
            key = "ads.reelsBannerRender",
            description = "ReelsBannerAdsComponent layout render (mod: REELS_BANNER_RENDER, X.XoY.A17)",
            multi = true,
            action = HookAction.BLOCK_NULL,
        ) { bridge: DexKitBridge ->
            bridge.findMethod {
                matcher {
                    usingStrings("REELS_BLOKS_BANNER_ADS_RENDER_COMPONENT_TEST_KEY")
                    paramCount(1)
                }
            }
        },

        // ---------------- Story / Reels / Shorts callbacks ----------------
        // Ports of the remaining decoded ad callbacks: the FgOJ banner
        // installer's two hookAllMethods (libnc.so.c 118736–119332) and the
        // story/ad XC_MethodReplacement hooks fKtn (X.AP5.A00) and UVFD3
        // (X.4qr.A00). All are swHOME_ADS-gated in the mod; their elaborate
        // log lines are dead scaffolding (built then discarded — see
        // tools/extract/banner_callbacks.txt / story_ad_callbacks.txt), so
        // only the blocking behavior is ported.

        HookTarget(
            key = "ads.reelsAdParams",
            description = "Reels launch-params builder incl. affiliate overlay (mod: FgOJ/x0g6, X.8HI.A04)",
            multi = true,
            action = HookAction.BLOCK_NULL,
        ) { bridge: DexKitBridge ->
            // X.8HI.A04 (mod) / LX/7zC;.A02 (fb571): the void builder that
            // puts every Reels ad prop (ReelsIsEligibleForInContentAds,
            // ReelsInstreamAdBreaks, … and ReelsAffiliateLinkBannerOverlay).
            // Nulled, the Reels viewer receives zero ad params. The void
            // filter keeps out any sibling that only reads the props.
            bridge.findMethod {
                matcher {
                    usingStrings("ReelsIsEligibleForInContentAds")
                }
            }.filter { it.returnTypeName == "void" }
        },
        HookTarget(
            key = "ads.reelsPostLoopBanner",
            description = "Reels IdleState.onEnter post-loop/banner ad pipeline (mod: FgOJ/EKUKHus, X.7P4.A0Y)",
            multi = true,
            action = HookAction.BLOCK_NULL,
        ) { bridge: DexKitBridge ->
            // X.7P4.A0Y (mod) / LX/6pp;.A09 (fb571): the banner/post-loop ads
            // state machine's IdleState.onEnter — adds the post-loop ad state
            // and triggers the FBFetchReelsVideoAdsQuery fetch. Nulled, no
            // post-loop banner is queued from this state.
            bridge.findMethod {
                matcher {
                    usingStrings("REPLACE_POSTLOOP_WITH_BANNER")
                }
            }
        },
        HookTarget(
            key = "ads.storyAdBucketParse",
            description = "AdBucketParser.parse story ad-bucket validation (mod: fKtn, X.AP5.A00)",
            multi = true,
            action = HookAction.BLOCK_NULL,
        ) { bridge: DexKitBridge ->
            // X.AP5.A00 (mod) / LX/A3P;.A00 (fb571): parses story ad buckets
            // ("AdBucketParser.parse validation" is its own soft-report). The
            // literal also sits in an R8 string dispatch table on fb571
            // (LX/9Oh;.A00(I)String) — the return-type filter keeps it out;
            // parse returns a bucket object, never String.
            bridge.findMethod {
                matcher {
                    usingStrings("AdBucketParser.parse validation")
                }
            }.filter { it.returnTypeName != "java.lang.String" }
        },
        HookTarget(
            key = "ads.shortsMidCardAd",
            description = "FBShortsMidCardFeedUnit type-node getter, sponsored-checked (mod: EQRQ/UVFD3, X.4qr.A00)",
            multi = true,
            action = HookAction.RECEIVER_SPONSORED_NULL,
        ) { bridge: DexKitBridge ->
            // X.4qr.A00 (mod) / LX/4aX;.A00 (fb571): a bare TreeJNI GraphQL
            // wrapper whose only method returns the Shorts mid-card unit's
            // __typename node ("FBShortsMidCardFeedUnit"). The class carries
            // no strings, so the anchor is the TreeJNI constant pair
            // (field hash 3386882, inline hash 1742214758) — schema-derived
            // and identical in the mod's build and fb571; the pair matches
            // exactly one method across all fb571 dexes.
            bridge.findMethod {
                matcher {
                    usingNumbers(3386882, 1742214758)
                }
            }
        },
    )

    /**
     * Banner-ads dex-scan anchors — the ~40 string constants the mod fed to
     * dexplore (QPL event names, log literals, JNI registration names) to find
     * every banner-ad class, after which it hooked all boolean methods.
     * Handled by AdFilterHook.installBannerScan, not by HookTargets.
     */
    val bannerAnchors = listOf(
        "banner_ad", "banner_ads", "banner_ads_overlay", "bannerAdsOverlay",
        "banner_ad_visible", "banner_ad_dismiss", "banner_ad_click",
        "banner_ads_impression", "affiliate_link_banner_impression",
        "try_it_surface_banner_impression",
        "click_on_instream_legacy_banner_ad",
        "click_on_instream_legacy_banner_ad_context_card",
        "click_on_reel_instream_unified_player_banner_ad",
        "click_on_reel_banner_call_ad",
        "reels_banner_click_iab_inline", "reels_banner_click_iab_chaining",
        "reels_banner_click_wnb_inline", "reels_banner_click_wnb_chaining",
        "banner_ad_above_metadata_transition_key", "bannerAdSurfaceDelayMs",
        "bannerAdBreak", "bannerPo",
        "Kicking off banner ads fetch",
        "error while trying to load banner ad",
        "Failed to fetch banner ad",
        "loadbanneradasync", "hidebanneradasync",
        "banner_ads_click", "banner_ads_report_ad", "banner_ads_hide_ad",
        "affiliate_link_banner_click", "affiliate_link_attachment_banner_click",
        "mailboxinthreadadcontextbannerjni", "zero_banner_impression",
        "banner_upgrade_mobile_plan",
    )
}
