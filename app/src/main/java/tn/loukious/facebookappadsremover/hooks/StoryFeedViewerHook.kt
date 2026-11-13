package tn.loukious.facebookappadsremover.hooks

import tn.loukious.facebookappadsremover.core.Discovery
import tn.loukious.facebookappadsremover.core.HookTarget
import tn.loukious.facebookappadsremover.core.L
import tn.loukious.facebookappadsremover.core.Settings
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedInterface.Hooker
import org.luckypray.dexkit.DexKitBridge
import java.lang.reflect.Method

/**
 * M2.7 stories-in-feed viewer block — port of the original mod's N38Ias3fZvDtx4XvGTfi
 * callback (libnc.so.c 182439–183785), installed on X.91I.A17.
 *
 * X.91I is StoriesSingleBucketInlineViewerRootComponentSpec — the Litho spec
 * of the inline Stories viewer FB intersperses between News Feed posts
 * ("Story 24h"). A17 is its render method and carries the
 * "updateState:…updateOnReactionStateChanged" literal. The mod's replacement
 * returned null whenever its toggle was on, so the component never renders.
 * (The mod's log line for this hook — "Story 24h hiển thị giữa NewsFeed" —
 * was built and then discarded without being written: dead scaffolding.)
 *
 * The mod gated this on app.telegram.bemai3012_Story24hInNewsFeed — the same
 * toggle that drops MULTI_FB_STORIES_TRAY rows in the feed filter
 * (NewsfeedFilterHook.PREF_STORIES, default off). Anchor literal verified
 * unique in fb571 (LX/A72;.A1G) and present in the mod's build (C91I.A17).
 */
object StoryFeedViewerHook {

    private const val TAG = "FBAR.Feed"

    /** Cache key — stored in MethodCache's methods map, not its class map. */
    const val CACHE_KEY = "feed.storyViewerRender"

    /** The render method's unique updateState log literal. */
    private const val RENDER_ANCHOR =
        "updateState:StoriesSingleBucketInlineViewerRootComponent.updateOnReactionStateChanged"

    /** Discovery target — resolved through the shared Discovery pipeline. */
    val target = HookTarget(
        key = CACHE_KEY,
        description = "StoriesSingleBucketInlineViewerRootComponent render (mod: N38Ias3, X.91I.A17)",
        multi = true,
    ) { bridge: DexKitBridge ->
        bridge.findMethod {
            matcher {
                usingStrings(RENDER_ANCHOR)
            }
        }
    }

    /**
     * Finds and hooks the render method — the exact hook the mod installed.
     *
     * @return the hooked methods for the discovery cache, or null if none.
     */
    fun install(module: XposedInterface, bridge: DexKitBridge, classLoader: ClassLoader): List<Method>? {
        val report = try {
            Discovery().discover(bridge, classLoader, listOf(target)).first()
        } catch (t: Throwable) {
            L.w(TAG, "DexKit query failed for story viewer render", t)
            return null
        }
        return if (report.status == Discovery.Status.FOUND) {
            hookRender(module, report.methods)
        } else {
            L.w(TAG, "${report.status} story viewer render (anchor: $RENDER_ANCHOR)")
            null
        }
    }

    /** Cache-hit path: hook the previously discovered render method(s) directly. */
    fun installCached(module: XposedInterface, methods: List<Method>): Boolean {
        if (methods.isEmpty()) return false
        return hookRender(module, methods) != null
    }

    private fun hookRender(module: XposedInterface, methods: List<Method>): List<Method>? {
        val hooked = ArrayList<Method>(methods.size)
        for (m in methods) {
            // R8 can centralize the anchor literal into a dispatch table —
            // same guard as AdFilterHook.install.
            if (tn.loukious.facebookappadsremover.hooks.AdFilterHook.isStringDispatchTable(m)) {
                L.w(TAG, "skipping string-table method for story viewer: ${m.declaringClass.name}.${m.name}")
                continue
            }
            try {
                module.hook(m).intercept(BlockRenderHook)
                hooked.add(m)
                L.i(TAG, "hooked story viewer render: ${m.declaringClass.name}.${m.name}/${m.parameterCount}")
            } catch (t: Throwable) {
                L.w(TAG, "hook failed on ${m.declaringClass.name}.${m.name}", t)
            }
        }
        return if (hooked.isEmpty()) null else hooked
    }

    /** N38Ias3 port: return null while the stories toggle is on. */
    private object BlockRenderHook : Hooker {
        private var logged = false

        override fun intercept(chain: XposedInterface.Chain): Any? {
            if (!enabled()) return chain.proceed()
            if (!logged) {
                logged = true
                L.i(TAG, "story viewer render blocked: ${chain.executable.name}")
            }
            return null
        }
    }

    private fun enabled(): Boolean =
        Settings.getBoolean(Settings.FEED_STORIES, false)
}
