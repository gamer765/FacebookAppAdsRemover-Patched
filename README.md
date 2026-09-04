# FacebookAppAdsRemover-Patched

Source-built patch workspace for `Loukious/FacebookAppAdsRemover` tag **1.9**.

## 1.9.1-patched

This build is targeted at Facebook `576.0.0.42.73` / build `474227118` and contains:

- Fix for the `ReelsAdClassifier` `ConcurrentHashMap.put()` null-cache crash seen in LSPosed logs.
- Existing upstream Reels classifications including `AD` and `ADS_MIDCARD` remain enabled.
- Exact guarded fast-path hooks for the Facebook 576 video/Reels interruption pipeline:
  - `X.aaR.A05(...)`
  - `X.aa6.A07(...)`
  - `X.6UH.invoke()`
  - `X.6UI.invoke()`
- Diagnostic log messages when those hooks install or block an interruption request.
- Version bumped to `1.9.1-patched` / versionCode `11`.

The GitHub Actions workflow clones upstream tag 1.9, applies `scripts/patch_v1_9.py`, and compiles the release APK with Java 21 / Gradle 8.13.

Because this is not signed with the upstream author's private key, the official v1.9 APK must be uninstalled before installing the locally signed patched build. After installation, enable Facebook in the module's LSPosed scope and force-stop Facebook before testing.
