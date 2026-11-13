package tn.loukious.facebookappadsremover.hooks

import android.content.Intent
import android.net.Uri
import tn.loukious.facebookappadsremover.core.L
import tn.loukious.facebookappadsremover.core.Settings
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedInterface.Hooker
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.net.URLDecoder

/**
 * Clean URL — port of the original mod's l.php unwrap (`X.O1EMPB7OWX4fymeZ5Qom`,
 * RE notes: tools/extract/link_handling_notes.md, second section).
 *
 * Decoded mod behaviour:
 *
 *  - `O1EMPB7OWX4fymeZ5Qom.UuCRUjLnvPR7SP3GNGo(String, MethodHookParam)`
 *    (@210945) — hook callback gated on the ENCRYPTED pref read of
 *    `app.telegram.bemai3012_swFbclid` (default FALSE): when the intercepted
 *    URL contains "fbclid" and matches
 *    `.+?l\.php\?u=(.+?)(%26|%3F)fbclid.+`, it extracts group 1 and
 *    URL-decodes it (utf-8) — i.e. unwraps Facebook's
 *    `facebook.com/l.php?u=<real-url>&fbclid=…` redirect wrapper to the real
 *    destination.
 *  - The decoded URL is then opened per the mode setting from
 *    `X.Fo7eNrTYDmtmyAWsvqo0.s9sEFa2Cpw9OaL6Yf67():int`: 1 = the mod's
 *    in-app browser panel (Fo7e.JAvH7JcvC2m10XLQTVY), 2 = Fo7e's alternate
 *    opener, and the FALLBACK branch rewrites `args[0]`'s Intent data
 *    (`Uri.parse(u)`) and re-invokes the original method.
 *
 * Deliberate deviations (documented, not invented):
 *  - This port implements ONLY the fallback branch — rewrite the intercepted
 *    intent's data and let it proceed. The in-app browser panel (Fo7e/BdR1
 *    WebView UI) is deferred with the mod's other WebView panels (feature
 *    matrix, "in-FB WebView panel"); the mode setting only chose between
 *    openers, and the unwrap itself is identical in every branch.
 *  - The mod's exact registration site for the callback was not decoded
 *    (its Java-side caller lives in the mod dex); the port hooks
 *    Activity.startActivity(Intent) / (Intent, Bundle) — the framework
 *    methods FB uses to hand external links to the system, and the same
 *    family the mod's "Hook Start Activity" installer used for its
 *    activity-list dump (ActivityListHook).
 *  - The Shopee-affiliate machinery that shares this subsystem (BdR1 panel
 *    rewrite, NPogWBp0 remote-config URLs, the captured-fbclid forwarding)
 *    is author monetization and is NOT ported — see the feature matrix row
 *    "Clean URL", component B.
 *  - libxposed hook args are immutable, but the Intent object itself is not:
 *    the port mutates intent.data in place and proceeds, which is exactly
 *    the effect of the mod's invokeOriginalMethod-with-rewritten-args.
 */
object CleanUrlHook {

    private const val TAG = "FBAR.CleanUrl"

    /** Toggle key (mod pref: app.telegram.bemai3012_swFbclid). */
    const val KEY = "links.cleanUrl"

    /** Mod's unwrap pattern (DAT_00fe0ed8): everything up to the encoded
     *  param boundary that precedes fbclid, group 1 = the real destination. */
    private val LPHP_PATTERN =
        Regex(".+?l\\.php\\?u=(.+?)(%26|%3F)fbclid.+")

    private var installed = false

    /**
     * Hooks the framework methods on the boot classloader. Runs from
     * onAppCreated like the mod's boot-time installer — no DexKit needed
     * (android.app.Activity is a stable name). The toggle is read inside the
     * callback, so flipping it takes effect without a re-install.
     */
    fun install(module: XposedInterface) {
        if (installed) return
        runCatching {
            val activity = Class.forName("android.app.Activity")
            val targets = activity.declaredMethods.filter {
                !Modifier.isAbstract(it.modifiers) &&
                    it.name == "startActivity" &&
                    it.parameterCount in 1..2 &&
                    it.parameterTypes[0] == Intent::class.java
            }
            if (targets.isEmpty()) {
                L.w(TAG, "Activity.startActivity overloads not found — skipping")
                return
            }
            for (m: Method in targets) {
                module.hook(m).intercept(UnwrapHooker)
            }
            installed = true
            L.i(TAG, "clean-URL unwrap armed on ${targets.size} startActivity overload(s) " +
                    "(mod: O1EMPB7OWX4fymeZ5Qom.UuCRUjLnvPR7SP3GNGo)")
        }.onFailure { L.w(TAG, "clean-URL hook failed", it) }
    }

    /** Before-branch: unwrap l.php data on the intent, then proceed. */
    private object UnwrapHooker : Hooker {
        override fun intercept(chain: XposedInterface.Chain): Any? {
            runCatching { unwrap(chain) }
                .onFailure { L.w(TAG, "l.php unwrap failed", it) }
            return chain.proceed()
        }
    }

    private fun unwrap(chain: XposedInterface.Chain) {
        if (!Settings.getBoolean(KEY, false)) return
        val intent = chain.args.getOrNull(0) as? Intent ?: return
        val url = intent.dataString ?: return
        // Mod gate: url.contains("fbclid") (DAT_00fe0e98) — redundant with
        // the pattern but faithful.
        if (!url.contains("fbclid")) return
        val match = LPHP_PATTERN.matchEntire(url) ?: return
        val unwrapped = URLDecoder.decode(match.groupValues[1], "utf-8")
        if (unwrapped.isEmpty()) return
        intent.data = Uri.parse(unwrapped)
        L.i(TAG, "l.php unwrapped:\n  from: $url\n  to:   $unwrapped")
    }
}
