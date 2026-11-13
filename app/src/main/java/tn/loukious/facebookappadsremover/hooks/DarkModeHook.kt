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
 * Dark mode — port of the original mod's theme hook (installer
 * `hzCa2sx7Z1cA8tRYLHt0`, libnc.so.c 1186165–1187450, boot table §0 #1
 * "Hook Facebook Theme").
 *
 * The mod installed TWO hooks, both forcing the result to the `dark` pref
 * value (its callbacks Hk8o/bsrnc literally did setResult(T7Puc())):
 *
 *  1. Theme manager: every declared 0-param Boolean method named "A04" on
 *     X.1XO — identified as ThemePreferences via the skeleton's
 *     `__redex_internal_original_name` ("ThemePreferences$asyncLogAppInitial
 *     Theme$1"). A04() is the isDarkMode getter: State.SYSTEM + OS uiMode +
 *     a mobile-config kill-switch gate. Decrypted installer strings:
 *     DAT_010bf858="X.1XO", DAT_010bf898="A04".
 *  2. Activity level: every declared 0-param Boolean method named
 *     "isDarkMode" on com.facebook.base.activity.FbFragmentActivity — a
 *     STABLE class name (DAT_010bf958/DAT_010bf998 decrypt to exactly
 *     these), so this arm needs no DexKit.
 *
 * One deliberate deviation: the mod forced the getter to the pref value
 * BOTH ways (toggle off ⇒ isDarkMode forced FALSE ⇒ light mode even if the
 * user picked dark natively). We only override when the toggle is ON, so a
 * native dark-mode preference is left alone.
 *
 * Anchor for the 576 ThemePreferences equivalent (C1QZ.A06): the EndToEnd
 * flag literal "fb.e2e.enable_dark_mode" — the first line of the getter body.
 * (The mod build's A04 also gated on mobile-config 36314949215396018L, but
 * 576 dropped that kill-switch entirely, so a usingNumbers anchor finds
 * nothing — verified against the 576 secondary decompile, where A06 has no
 * MobileConfigUnsafeContext call.)
 */
object DarkModeHook {

    private const val TAG = "FBAR.Theme"

    /** Cache key — methods map entry; the class map doubles as the sentinel. */
    const val CACHE_KEY = "theme.isDarkMode"

    /** The e2e flag literal that opens the isDarkMode getter body. */
    private const val E2E_ANCHOR = "fb.e2e.enable_dark_mode"

    /** Discovery target for the ThemePreferences isDarkMode getter. */
    val target = HookTarget(
        key = CACHE_KEY,
        description = "ThemePreferences isDarkMode getter (mod: hzCa, X.1XO.A04 / 576: C1QZ.A06)",
    ) { bridge: DexKitBridge ->
        bridge.findMethod {
            matcher {
                usingStrings(E2E_ANCHOR)
            }
        }
    }

    /**
     * Stable-name arm — needs only the secondary dexes to attach, so it rides
     * the probe cadence (like AccountHook), not the DexKit paths.
     *
     * @return true when the class resolved and at least one method was hooked.
     */
    fun installActivityHook(module: XposedInterface, classLoader: ClassLoader): Boolean {
        val cls = runCatching {
            Class.forName("com.facebook.base.activity.FbFragmentActivity", false, classLoader)
        }.getOrNull() ?: return false
        var count = 0
        for (m in cls.declaredMethods) {
            if (m.parameterCount != 0 || m.returnType != java.lang.Boolean.TYPE) continue
            if (m.name != "isDarkMode") continue
            runCatching {
                module.hook(m).intercept(DarkHook)
                count++
            }.onFailure { L.w(TAG, "hook failed on ${m.declaringClass.name}.${m.name}", it) }
        }
        if (count > 0) L.i(TAG, "activity isDarkMode hooked: $count (mod log: 'Theme activity hook count=')")
        return count > 0
    }

    /**
     * DexKit path: discover and hook the ThemePreferences getter.
     *
     * @return the hooked methods for the discovery cache, or null if none.
     */
    fun install(module: XposedInterface, bridge: DexKitBridge, classLoader: ClassLoader): List<Method>? {
        val report = try {
            Discovery().discover(bridge, classLoader, listOf(target)).first()
        } catch (t: Throwable) {
            L.w(TAG, "DexKit query failed for theme getter", t)
            return null
        }
        if (report.status != Discovery.Status.FOUND) {
            L.w(TAG, "${report.status} theme getter (anchor: $E2E_ANCHOR)")
            return null
        }
        return hookGetter(module, report.methods)
    }

    /** Cache-hit path: hook the previously discovered getter(s) directly. */
    fun installCached(module: XposedInterface, methods: List<Method>): Boolean {
        if (methods.isEmpty()) return false
        return hookGetter(module, methods) != null
    }

    private fun hookGetter(module: XposedInterface, methods: List<Method>): List<Method>? {
        val hooked = ArrayList<Method>(methods.size)
        for (m in methods) {
            // The mod's own filter: 0-param Boolean methods only — a drifted
            // anchor landing on a different shape must not be forced.
            if (m.parameterCount != 0 || m.returnType != java.lang.Boolean.TYPE) {
                L.w(TAG, "skipping non-boolean/is-arg candidate ${m.declaringClass.name}.${m.name}/${m.parameterCount}")
                continue
            }
            // R8 can centralize the anchor literal into a dispatch table —
            // same guard as AdFilterHook/StoryFeedViewerHook.
            if (tn.loukious.facebookappadsremover.hooks.AdFilterHook.isStringDispatchTable(m)) {
                L.w(TAG, "skipping string-table method for theme getter: ${m.declaringClass.name}.${m.name}")
                continue
            }
            try {
                module.hook(m).intercept(DarkHook)
                hooked.add(m)
                L.i(TAG, "hooked theme getter: ${m.declaringClass.name}.${m.name} (mod log: 'Theme manager hook count=')")
            } catch (t: Throwable) {
                L.w(TAG, "hook failed on ${m.declaringClass.name}.${m.name}", t)
            }
        }
        return if (hooked.isEmpty()) null else hooked
    }

    /** Both installer arms: force true while the dark toggle is on. */
    private object DarkHook : Hooker {
        override fun intercept(chain: XposedInterface.Chain): Any? {
            if (Settings.getBoolean(Settings.APPEARANCE_DARK, false)) return true
            return chain.proceed()
        }
    }
}
