# FacebookAppAdsRemover-Patched

Patched fork workspace based on `Loukious/FacebookAppAdsRemover` tag **1.9**.

The complete buildable patched source is published under [`source/`](source/). The patch scripts used to regenerate it from upstream are kept under [`scripts/`](scripts/).

## 1.9.4-patched

Targeted at Facebook `576.0.0.42.73` / build `474227118`.

Changes include:

- Fixes the `ReelsAdClassifier` `ConcurrentHashMap` null-cache crash.
- Expands the Reels classifier to follow deeper wrapper/accessor chains and accept assignable concrete model types.
- Blocks the primary `FBFetchReelsVideoAdsQuery` sponsored-Reels fetch path.
- Blocks the alternate `reels_ad_query_send` dispatcher used by the "Reel continues after the ad" interruption path.
- Blocks profile-Reels async ad requests and Reels ad background prefetch.
- Blocks RTI / POE / similar-ad async Reels request paths.
- Blocks commercial-break / Watch pre-roll and mid-roll video-ad request paths.
- Installs the Facebook-576 exact fast-path hooks at `Application.attach` and retries at Facebook MultiDex readiness so ad prefetch cannot win the startup race.
- Retains the upstream feed, Marketplace, Stories, Reels and other ad-removal hooks.

## Build

From the repository root:

```bash
cd source
./gradlew :app:assembleRelease
```

GitHub Actions also rebuilds the module from upstream tag 1.9 by applying `scripts/patch_v1_9.py` and `scripts/patch_v1_9_4.py` with Java 21 / Gradle 8.13.

## Installation

This fork cannot use the upstream author's signing key. A locally signed build therefore cannot update an APK signed by the original project. Once installed with the fork's persistent signing certificate, later fork builds signed with the same certificate can update in place.

Enable `com.facebook.katana` in the module's LSPosed scope and force-stop Facebook after updating the module.
