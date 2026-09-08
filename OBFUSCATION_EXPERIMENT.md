# All-feature obfuscation-resistance experiment

Branch: `experiment/obfuscation-resistant-hooks`

This branch tests whether the module can survive ordinary Facebook R8/ProGuard class and method shuffling without per-release `X.*` remapping.

## Strict test mode

The LSPosed entry point is only:

`tn.loukious.facebookappadsremover.ObfuscationResistantAllHooks`

The normal `Module` entry point is deliberately not loaded. The CI build also runs `scripts/strict_all_features.py`, which removes the call to `installFacebook576VideoInterruptionFastPaths()` from the generic installer before compiling. Therefore the test artifact cannot fall back to the known Facebook 576/577 hard-coded `X.*` video mappings.

## Resolution strategy by feature

### News Feed

- Discover Litho components from stable component names such as `NewsFeedFeedUnitComponent` and `LoggingComponent`.
- Resolve render methods and child/edge fields by method and type shape rather than obfuscated names.
- Resolve feed CSR filters, sponsored pools and late-stage list sanitizers structurally with DexKit.
- Cache the resolved classes per Facebook version so subsequent cold starts can hook before the cached sponsored feed renders.

### Stories

- Discover the dedicated story-ad provider/store from stable strings such as `AdsPaginatingNetworkAdBucketFetcher`, `FbStoryAdInDiscStoreImpl`, `IN_DISC_METADATA_KEY`, `AD_BUCKETS_KEY`, and `StoryAdsInDisc`.
- Resolve fetch/merge/update/insertion methods by shape rather than class name.

### Marketplace

- Use the existing Marketplace GraphQL/network/render DexKit discovery and cache the resolved network guard per Facebook version.
- Do not depend on a fixed `X.*` class name.

### Reels and video ads

- Resolve dedicated ad request methods from stable strings such as `reels_ad_query_send`, `FBFetchReelsVideoAdsQuery`, `commercial_break_query_send`, `AdBreakServerAPI`, `ProfileReelsAsyncAdsQuery`, `IMMERSIVE_REAL_TIME_INTENT`, `POE_TRIGGERED_INTERSTITIAL`, and `fb_shorts_similar_ad`.
- Cache resolved method descriptors per Facebook build.
- Run a focused scan at Facebook MultiDex readiness and on early retries so ad prefetch does not win the startup race.

### Instant games / Audience Network

- Resolve Quicksilver request handlers from stable JSON/error strings and bridge behavior.
- Intercept the runtime Javascript bridge through Android's stable `WebView.addJavascriptInterface` API rather than a Facebook class name.
- Rewrite rewarded-ad result JSON at the webview delivery boundary.
- Use stable Android/Meta activity class names and lifecycle hooks for Audience Network/playable fallbacks.

## What this protects against

The goal is resistance to ordinary obfuscation changes: classes moving from one `X.*` identifier to another, methods being renamed, and internal wrapper types being shuffled while their strings, contracts and behavior remain recognizable.

It is not intended to be permanently immune to architectural changes. A Facebook release can still require work if Meta removes the stable markers, changes GraphQL/ad semantics, changes Litho component contracts, moves a feature native-side, or replaces an ad pipeline entirely.

## Test matrix

For a meaningful strict test, verify all of these with the normal `Module` entry point disabled:

- News Feed sponsored posts
- Story ads
- Marketplace sponsored units
- Reels feed-native sponsored cards
- Reels interruption ads (`Reel continues after the ad`)
- Watch/pre-roll/mid-roll video ads
- Instant-game interstitial/rewarded/banner ads

Current experimental module version: `1.10.0-obfexp3-allfeatures` (`versionCode 18`).
