package com.applemusicktv.data

import com.applemusicktv.i18n.tr

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import com.applemusicktv.R

enum class LauncherIcon(private val titleId: Int, val resource: Int, val alias: String) {
    Red(R.string.msg_303, R.mipmap.ic_launcher, "com.applemusicktv.RedLauncher"),
    White(R.string.msg_304, R.mipmap.ic_launcher_white, "com.applemusicktv.WhiteLauncher");
    val title: String get() = tr(titleId)


}

/** Only aliases are toggled. The real Activity stays enabled, including on Android 6. */
class LauncherIconManager(private val context: Context) {
    private val pm get() = context.packageManager
    private fun component(icon: LauncherIcon) = ComponentName(context.packageName, icon.alias)
    fun current(): LauncherIcon = if (
        pm.getComponentEnabledSetting(component(LauncherIcon.White)) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
    ) LauncherIcon.White else LauncherIcon.Red

    fun select(icon: LauncherIcon) {
        val old = current()
        if (old == icon) return
        // Enable the replacement before removing the previous launcher entry.
        pm.setComponentEnabledSetting(component(icon), PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
        try {
            pm.setComponentEnabledSetting(component(old), PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
        } catch (e: RuntimeException) {
            pm.setComponentEnabledSetting(component(old), PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
            pm.setComponentEnabledSetting(component(icon), PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
            throw e
        }
    }
}
