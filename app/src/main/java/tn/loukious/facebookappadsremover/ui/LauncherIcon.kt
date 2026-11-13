package tn.loukious.facebookappadsremover.ui

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

/**
 * Launcher-icon visibility.
 *
 * The drawer entry is an `<activity-alias>` (see AndroidManifest.xml), not the
 * activity itself, so hiding the icon disables only that alias.
 * [MainActivity] stays exported and startable by explicit intent, which is the
 * way back in:
 *
 *     adb shell am start -n tn.loukious.facebookappadsremover/.ui.MainActivity
 *
 * A hidden icon also takes the Xposed module list's settings button with it,
 * because that opens a module through
 * `PackageManager.getLaunchIntentForPackage()` — which is why the manifest
 * carries the always-enabled `ui.InfoAlias`. Nothing else starts
 * [MainActivity]: the launcher entry is its only entry point.
 *
 * PackageManager holds the state, so no preference shadows it and nothing can
 * drift out of sync with the component that actually controls the icon.
 */
object LauncherIcon {

    /** Must match <activity-alias android:name> in AndroidManifest.xml. */
    private const val ALIAS = "tn.loukious.facebookappadsremover.ui.LauncherAlias"

    private fun component(context: Context) = ComponentName(context.packageName, ALIAS)

    /**
     * True unless the alias was explicitly disabled. The manifest default is
     * enabled, and COMPONENT_ENABLED_STATE_DEFAULT means "no override", so both
     * count as visible.
     */
    fun isVisible(context: Context): Boolean = runCatching {
        context.packageManager.getComponentEnabledSetting(component(context)) !=
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
    }.getOrDefault(true)

    /**
     * Shows or hides the drawer icon. DONT_KILL_APP keeps this settings screen
     * alive — the change needs no process restart, and killing it would close
     * the very screen the user just flipped the switch on.
     */
    fun setVisible(context: Context, visible: Boolean) {
        runCatching {
            context.packageManager.setComponentEnabledSetting(
                component(context),
                if (visible) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP,
            )
        }
    }
}
