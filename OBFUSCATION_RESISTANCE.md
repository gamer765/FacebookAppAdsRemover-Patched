# Obfuscation-resistant hook experiment

Branch: `experiment/obfuscation-resistant-hooks`

This experiment keeps the normal FacebookAppAdsRemover module intact and adds a second LSPosed entry point, `ObfuscationResistantVideoHooks`, whose purpose is to survive Facebook's periodic `X.*` class and method renaming.

## Strategy

Instead of depending on names such as `X.7kx`, `X.7ez`, `X.aXM`, `X.SQq`, etc., the experimental layer resolves dedicated video/Reels ad request methods with DexKit using stable strings embedded in their bytecode.

The marker set was checked against both:

- Facebook 576.0.0.42.73 / build 474227118
- Facebook 577.0.0.50.72 / build 474426253

The following markers survived the 576 -> 577 obfuscation shuffle while their declaring classes changed:

- `reels_ad_query_send`
- `FBFetchReelsVideoAdsQuery`
- `commercial_break_query_send`
- `Kicking off video ad fetch`
- `AdBreakServerAPI`
- `Fetch adbreak when already fetching`
- `ProfileReelsAsyncAdsQuery`
- `ReelsAdsBackgroundPrefetchInitializer`
- `ReelsAdsPeriodicPrefetch`
- `VideoHomeCSRNetworkRequester.doAdChannelNetworkRequest`
- `IMMERSIVE_REAL_TIME_INTENT`
- `POE_TRIGGERED_INTERSTITIAL`
- `fb_shorts_similar_ad`

## Cold-start handling

A purely dynamic DexKit scan can lose a race with Facebook's early Reels ad prefetch. The experiment addresses that in three ways:

1. It hooks Facebook `MultiDexClassLoaderJava.configure` and immediately launches a focused stable-string scan when secondary dex becomes available.
2. It retries a small focused scan during the first few seconds after `Application.attach` instead of waiting for the module's larger general scan.
3. It stores resolved reflective method descriptors in SharedPreferences keyed by Facebook version name + version code. On later launches of the same Facebook build, those cached methods are installed immediately without repeating discovery first.

The cache is deliberately build-specific. When Facebook updates, stale obfuscated names are not trusted; the focused stable-string resolver discovers the new methods automatically.

## Safety constraints

The matchers include return type and, where useful, parameter count so generic analytics/string users are not blindly blocked. Void ad-request methods are short-circuited with `null`, while the three known zero-argument ad-builder lambdas are matched as `java.lang.Object` and also return `null`, matching the previous exact-name behavior.

The normal v1.9.5 hooks remain active alongside this experiment. This makes the branch additive and easy to compare or revert.

## Build

```bash
cd source
gradle --no-daemon :app:assembleRelease
```

The branch-specific GitHub Actions workflow is `.github/workflows/build-obfuscation-resistant.yml`.

Experimental module version: `1.10.0-obfexp1` / versionCode `16`.
