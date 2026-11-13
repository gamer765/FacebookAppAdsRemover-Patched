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
 * Stories-tray hide — port of the original mod's hideTagStory ("Remove 'Stories'
 * card at top", installer `GhTa.JvXXhNaRKzGCpkFxz5M` @119816–120968).
 *
 * The mod findClass'd `X.1Yo` + `X.2dd`, walked getDeclaredMethods, kept
 * static 2-param methods named `A00`, and hooked each with
 * `WmSptztYYqIQqwkfDShG.replaceHookedMethod` (@1042017): pref TRUE → return
 * null, FALSE → invokeOriginalMethod; success log `Đã ẩn khay Story tray —
 * Bảng tin` ("Hid Story tray — News Feed").
 *
 * RE detail (2026-09-06): in the mod build only **X.2dd.A00** matches that
 * filter — X.1Yo's statics are 0/1-param (the tray *gate*,
 * `MobileConfigUnsafeContext.AtC(36316104561272407L) || AtC(36316104570840687L)`),
 * so the "X.1Yo" findClass was effectively a no-op. The real site:
 * X.2dd = `NewsFeedAdapterConfiguration`, static
 * `A00(config, ImmutableList.Builder) → C69902c1` = **addStoriesAdapter** —
 * checks the gate, then `builder.add(trayAdapter)`; nulling it keeps the
 * stories tray adapter out of the feed's adapter list, hiding the tray card.
 *
 * 576 equivalent (verified in the decompile 2026-09-06): `C2ML.A00` —
 * structurally identical, anchored on its own QPL literal
 * "NewsFeedAdapterConfiguration.addStoriesAdapter" (the only occurrence in
 * the dex; "stories_tray_create_adapter_start" appears in C2ML + C19171nM,
 * so it is NOT unique). Gate = `C19171nM.CaI()`; tray adapter object =
 * `C20071ou`; return type is an object, so the mod's return null maps
 * directly to skipping the original.
 */
object StoriesTrayHook {

    private const val TAG = "FBAR.Tray"

    /** Cache key — methods map; the class map doubles as sentinel. */
    const val CACHE_KEY = "stories.hideTray"

    private val targets = listOf(
        HookTarget(
            key = CACHE_KEY,
            description = "NewsFeedAdapterConfiguration.addStoriesAdapter (mod: JvXX/X.2dd site)",
        ) { bridge: DexKitBridge ->
            bridge.findMethod { matcher { usingStrings("NewsFeedAdapterConfiguration.addStoriesAdapter") } }
        },
    )

    /**
     * DexKit path: discover and hook the tray-adapter site.
     *
     * @return the hooked methods for the discovery cache, or null if not found.
     */
    fun install(module: XposedInterface, bridge: DexKitBridge, classLoader: ClassLoader): List<Method>? {
        val reports = try {
            Discovery().discover(bridge, classLoader, targets)
        } catch (t: Throwable) {
            L.w(TAG, "DexKit query failed for stories tray", t)
            return null
        }
        val methods = ArrayList<Method>()
        for (report in reports) {
            if (report.status != Discovery.Status.FOUND) {
                L.w(TAG, "${report.status} stories tray site: ${report.key}")
                continue
            }
            methods.addAll(hookSite(module, report.methods))
        }
        return if (methods.isEmpty()) null else methods
    }

    /** Cache-hit path: hook the previously discovered site directly. */
    fun installCached(module: XposedInterface, methods: List<Method>): Boolean {
        if (methods.isEmpty()) return false
        return hookSite(module, methods).isNotEmpty()
    }

    private fun hookSite(module: XposedInterface, methods: List<Method>): List<Method> {
        val hooked = ArrayList<Method>(methods.size)
        for (m in methods) {
            // R8 can centralize the anchor literal into a dispatch table —
            // same guard as AdFilterHook/DarkModeHook/AutoRefreshHook.
            if (AdFilterHook.isStringDispatchTable(m)) {
                L.w(TAG, "skipping string-table method: ${m.declaringClass.name}.${m.name}")
                continue
            }
            try {
                module.hook(m).intercept(HideHook)
                hooked.add(m)
                L.i(TAG, "hooked stories tray site: ${m.declaringClass.name}.${m.name}/${m.parameterCount} (mod log: 'Stories tray gate/adapter hooked')")
            } catch (t: Throwable) {
                L.w(TAG, "hook failed on ${m.declaringClass.name}.${m.name}", t)
            }
        }
        return hooked
    }

    /**
     * While the toggle is on, skip addStoriesAdapter entirely — the tray
     * adapter never lands in the feed's builder (mod: return null).
     * The return type is an object, so no primitive coercion is needed.
     */
    private object HideHook : Hooker {
        override fun intercept(chain: XposedInterface.Chain): Any? {
            if (!Settings.getBoolean(Settings.STORIES_HIDE_TRAY, false)) {
                return chain.proceed()
            }
            L.i(TAG, "HIDE stories tray adapter (mod: hideTagStory)")
            return null
        }
    }
}
