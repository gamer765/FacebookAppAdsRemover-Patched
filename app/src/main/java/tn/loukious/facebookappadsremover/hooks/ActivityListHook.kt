package tn.loukious.facebookappadsremover.hooks

import android.content.Intent
import tn.loukious.facebookappadsremover.core.L
import tn.loukious.facebookappadsremover.core.Settings
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedInterface.Hooker
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * Activity list — port of the original mod's "Activity List" (installer §0 #3
 * `oRfibd$qMtURs` @1304226 "Hook Start Activity" → `O82OIP65WZnsCptcqiXh`
 * @212438, `beforeHookedMethod` @212474).
 *
 * The mod hooked `Activity.startActivityForResult(Intent,int,Bundle)` (the
 * 3-arg overload — the body reads args[0] as Intent, args[1] as Integer
 * requestCode via intValue(), args[2] as Bundle) and built a text dump of
 * every hidden activity FB starts, via the Intent getters the body resolves:
 * getAction / getDataString / getType / getPackage / getComponent /
 * getCategories (Set + Iterator walk) / getFlags / getExtras (keySet +
 * hasExtra walk), plus Thread.currentThread().getName(). Decoded labels
 * (tools/extract corpora): "Intent Information:\n\n", "Action: ",
 * "Data URI: ", "Package: ", "Component: ", "Categories: ", "Flags: ",
 * "Request Code: ", "Extras:". It finished with
 * XposedBridge.invokeOriginalMethod — a pure observer, nothing blocked.
 *
 * Deviation: the mod persisted dumps under the APKMODDONE/ActivityHook
 * directory it created at boot; this port logs the same dump via the module
 * logger (logcat + libxposed log) — the module creates no sdcard dirs.
 * "Type: " (DAT_00fe1158 undecoded) is inferred from its position between
 * the Data URI and Package arms, both of which are decoded.
 */
object ActivityListHook {

    private const val TAG = "FBAR.Activity"

    /** Toggle key (mod menu: navigation.activity_list.*). */
    const val KEY = "navigation.activityList"

    private var installed = false

    /**
     * Hooks the framework method on the boot classloader. Runs from
     * onAppCreated like the mod's boot-time installer — no DexKit needed
     * (android.app.Activity is a stable name).
     */
    fun install(module: XposedInterface) {
        if (installed) return
        runCatching {
            val activity = Class.forName("android.app.Activity")
            val m: Method? = activity.declaredMethods.firstOrNull {
                !Modifier.isAbstract(it.modifiers) &&
                    it.name == "startActivityForResult" &&
                    it.parameterCount == 3 &&
                    it.parameterTypes[0] == Intent::class.java
            }
            if (m == null) {
                L.w(TAG, "startActivityForResult(Intent,int,Bundle) not found — skipping")
                return
            }
            module.hook(m).intercept(DumpHook)
            installed = true
            L.i(TAG, "activity-list dump armed on Activity.startActivityForResult (mod: O82OIP65WZnsCptcqiXh)")
        }.onFailure { L.w(TAG, "activity-list hook failed", it) }
    }

    /** Observer: dumps the Intent, then proceeds — the mod blocked nothing. */
    private object DumpHook : Hooker {
        override fun intercept(chain: XposedInterface.Chain): Any? {
            runCatching { dump(chain) }
                .onFailure { L.w(TAG, "intent dump failed", it) }
            return chain.proceed()
        }
    }

    private fun dump(chain: XposedInterface.Chain) {
        if (!Settings.getBoolean(KEY, false)) return
        val args = chain.args
        val intent = args.getOrNull(0) as? Intent ?: return
        val requestCode = (args.getOrNull(1) as? Int) ?: -1
        val sb = StringBuilder("Intent Information:\n\n")
        sb.append("Action: ").append(intent.action).append("\n\n")
        sb.append("Data URI: ").append(intent.dataString).append("\n\n")
        sb.append("Type: ").append(intent.type).append("\n\n")
        sb.append("Package: ").append(intent.`package`).append("\n\n")
        sb.append("Component: ").append(intent.component).append("\n\n")
        sb.append("Categories: ")
        val cats = intent.categories
        if (cats.isNullOrEmpty()) {
            sb.append("none")
        } else {
            sb.append(cats.joinToString(", "))
        }
        sb.append("\n\n")
        sb.append("Flags: 0x").append(Integer.toHexString(intent.flags)).append("\n\n")
        sb.append("Request Code: ").append(requestCode).append("\n\n")
        sb.append("Extras:")
        val extras = intent.extras
        if (extras == null || extras.isEmpty) {
            sb.append(" none")
        } else {
            for (key in extras.keySet()) {
                sb.append("\n  ").append(key).append("=").append(extras.get(key))
            }
        }
        sb.append("\n\n[thread: ").append(Thread.currentThread().name).append("]")
        L.i(TAG, sb.toString())
    }
}
