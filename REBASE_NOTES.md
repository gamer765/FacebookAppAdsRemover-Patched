# Rebase Notes

This branch is based on the exact file tree from upstream:

- Repository: Xposed-Modules-Repo/tn.loukious.facebookappadsremover
- Upstream commit: 416eb4523732233fdd4edecfa1666e7f4f6e7c74
- Upstream version: 1.25

## Local changes retained

The legacy experimental source tree was intentionally not carried forward. The
rebased build uses upstream's modern libxposed architecture, process filtering,
discovery/cache validation, account/session handling, and targeted ad hooks.

The following stable Reels additions from the gamer765 experiments were ported
into the upstream HookTarget system:

1. reels_ad_query_send Object-returning wrapper block
2. IMMERSIVE_REAL_TIME_INTENT one-argument void handoff block
3. fb_reels_facebook_reels_multiads_media zero-argument media runnable block
4. ReelsVddLayout::commentBarStateChange state gate, preserving the call while
   forcing only the final in-content-ad boolean false

No hardcoded obfuscated X.* class mappings are retained.

## Stability

- DexKit is updated from upstream 2.2.0 to 2.3.0.
- The upstream ProcessFilter and bounded/cached discovery model replace the old
  multi-entry-point retry scanners.
- The old global ViewGroup/TextView/WebView/Activity fallback hooks are not
  present in this rebased tree.

## Version

- versionCode: 45
- versionName: 1.25-gamer765-rebase1
