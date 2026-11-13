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
 * M2.8 hide-seen-story block — port of the original mod's edEtjRgjEmsdyz4jjDIB
 * callback (libnc.so.c 1153068–1153390), installed on X.AI9.A0F.
 *
 * X.AI9 is `StoryViewerFragment` (`__redex_internal_original_name` survives
 * in the mod's build; jadx file AI9.java). A0F is its static
 * `initializeNonCriticalControllers`-style setup method (the literal
 * "StoryviewerFragment.initializeNonCriticalControllers" sits inside the
 * body) — on 576.0.0.42.73 the same method is C22592BAi.A0C, on fb571
 * LX/9sO;.A0B; the literal is unique in each build. The method configures
 * the viewer's non-critical controllers — including the
 * SeenMutationController ("configureSeenMutationController") on 576.
 *
 * The mod's replacement returned null whenever its toggle was on, so the
 * whole controller setup is skipped. (Its log line "Đã ẩn Story đã xem" —
 * "Hid already-viewed Story" — was dead scaffolding, built and discarded.)
 * Gated on the mod's own toggle, hideSeenStory (default off).
 */
object HideSeenStoryHook {

    private const val TAG = "FBAR.Feed"

    /** Cache key — stored in MethodCache's methods map, not its class map. */
    const val CACHE_KEY = "stories.hideSeenStory"

    /** The setup method's own QPL marker literal — unique per build. */
    private const val SETUP_ANCHOR = "StoryviewerFragment.initializeNonCriticalControllers"

    /** Discovery target — resolved through the shared Discovery pipeline. */
    val target = HookTarget(
        key = CACHE_KEY,
        description = "StoryViewerFragment non-critical controller setup (mod: edEtjRgj, X.AI9.A0F)",
        multi = true,
    ) { bridge: DexKitBridge ->
        bridge.findMethod {
            matcher {
                usingStrings(SETUP_ANCHOR)
            }
        }
    }

    /**
     * Finds and hooks the setup method — the exact hook the mod installed.
     *
     * @return the hooked methods for the discovery cache, or null if none.
     */
    fun install(module: XposedInterface, bridge: DexKitBridge, classLoader: ClassLoader): List<Method>? {
        val report = try {
            Discovery().discover(bridge, classLoader, listOf(target)).first()
        } catch (t: Throwable) {
            L.w(TAG, "DexKit query failed for story viewer setup", t)
            return null
        }
        return if (report.status == Discovery.Status.FOUND) {
            hookSetup(module, report.methods)
        } else {
            L.w(TAG, "${report.status} story viewer setup (anchor: $SETUP_ANCHOR)")
            null
        }
    }

    /** Cache-hit path: hook the previously discovered setup method(s) directly. */
    fun installCached(module: XposedInterface, methods: List<Method>): Boolean {
        if (methods.isEmpty()) return false
        return hookSetup(module, methods) != null
    }

    private fun hookSetup(module: XposedInterface, methods: List<Method>): List<Method>? {
        val hooked = ArrayList<Method>(methods.size)
        for (m in methods) {
            // R8 can centralize the anchor literal into a dispatch table —
            // same guard as AdFilterHook.install.
            if (tn.loukious.facebookappadsremover.hooks.AdFilterHook.isStringDispatchTable(m)) {
                L.w(TAG, "skipping string-table method for story viewer setup: ${m.declaringClass.name}.${m.name}")
                continue
            }
            try {
                module.hook(m).intercept(BlockSetupHook)
                hooked.add(m)
                L.i(TAG, "hooked story viewer setup: ${m.declaringClass.name}.${m.name}/${m.parameterCount}")
            } catch (t: Throwable) {
                L.w(TAG, "hook failed on ${m.declaringClass.name}.${m.name}", t)
            }
        }
        return if (hooked.isEmpty()) null else hooked
    }

    /** edEtjRgj port: return null (skip the void setup) while the toggle is on. */
    private object BlockSetupHook : Hooker {
        private var logged = false

        override fun intercept(chain: XposedInterface.Chain): Any? {
            if (!enabled()) return chain.proceed()
            if (!logged) {
                logged = true
                L.i(TAG, "story viewer setup blocked: ${chain.executable.name}")
            }
            return null
        }
    }

    private fun enabled(): Boolean =
        Settings.getBoolean(Settings.STORIES_HIDE_SEEN, false)
}
