# FacebookAppAdsRemover-Patched

Patched fork workspace based on `Loukious/FacebookAppAdsRemover` tag **1.9**.

The complete buildable patched source is published under [`source/`](source/). The patch scripts used to regenerate it from upstream are kept under [`scripts/`](scripts/).

## 1.9.5-patched

Targeted at Facebook `577.0.0.50.72` / build `474426253`, while retaining the previous Facebook `576.0.0.42.73` / `474227118` fallbacks.

Changes include:

- Fixes the `ReelsAdClassifier` `ConcurrentHashMap` null-cache crash.
- Expands the Reels classifier to follow deeper wrapper/accessor chains and accept assignable concrete model types.
- Blocks the primary `FBFetchReelsVideoAdsQuery` sponsored-Reels fetch path.
- Blocks the alternate `reels_ad_query_send` dispatcher used by the "Reel continues after the ad" interruption path.
- Blocks profile-Reels async ad requests and Reels ad background prefetch.
- Blocks RTI / POE / similar-ad async Reels request paths.
- Blocks commercial-break / Watch pre-roll and mid-roll video-ad request paths.
- Adds Facebook 577 exact early-hook mappings discovered from build `474426253`:
  - Reels request / dispatcher: `X.7ez.A0B(...)` and `X.7ez.A09(...)`
  - Profile Reels: `X.B5Y.A03(...)`
  - Similar / RTI / POE builders: `X.6V0`, `X.6V3`, `X.6V6`
  - Async ad channel: `X.53Y.Ael(...)`
  - Commercial-break / ad-break fetchers: `X.SQq.A02(...)`, `X.SMj.A05(...)`, `X.SMM.A07(...)`
- Adds stable-string fallback hooks for `commercial_break_query_send`, `AdBreakServerAPI`, and `Fetch adbreak when already fetching` so later obfuscation changes are less likely to break the module immediately.
- Installs exact fast-path hooks at `Application.attach` and retries at Facebook MultiDex readiness so ad prefetch cannot win the startup race.
- Retains the upstream feed, Marketplace, Stories, Reels and other ad-removal hooks.

## Build

From the repository root:

```bash
cd source
./gradlew :app:assembleRelease
```

GitHub Actions also rebuilds the module from upstream tag 1.9 by applying the patch scripts in order with Java 21 / Gradle 8.13.

## Installation

This fork cannot use the upstream author's signing key. A build signed with a different certificate cannot update an already-installed APK from another signer. Keep the same signing certificate for subsequent fork builds if you want normal in-place updates.

Enable `com.facebook.katana` in the module's LSPosed scope and force-stop Facebook after updating the module.
