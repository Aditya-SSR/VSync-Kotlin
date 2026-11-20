package com.minimallabs.vsync.core

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import com.minimallabs.vsync.R

/** Launcher icon variants, backed by activity-alias enable states (exactly one enabled). */
object AppIcons {
    class Variant(val key: String, val label: String, val preview: Int, val alias: String)

    val all = listOf(
        Variant("default", "Default", R.drawable.ic_launcher_foreground, "MainActivityDefault"),
        Variant("classy", "Classy", R.drawable.ic_launcher_foreground_classy, "MainActivityClassy"),
        Variant("glassy", "Glassy", R.drawable.ic_launcher_foreground_glassy, "MainActivityGlassy"),
        Variant("rainbow", "Rainbow", R.drawable.ic_launcher_foreground_rainbow, "MainActivityRainbow"),
    )

    // The aliases live in the code namespace, which stays com.minimallabs.vsync even for the .dev build.
    private fun component(context: Context, alias: String) = ComponentName(context.packageName, "com.minimallabs.vsync.$alias")

    fun current(context: Context): String {
        val pm = context.packageManager
        for (v in all) {
            val enabled = when (pm.getComponentEnabledSetting(component(context, v.alias))) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
                PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> v.key == "default"
                else -> false
            }
            if (enabled) return v.key
        }
        return "default"
    }

    fun set(context: Context, key: String) {
        val pm = context.packageManager
        // Enable the new one first so the launcher never has zero entries.
        all.firstOrNull { it.key == key }?.let {
            pm.setComponentEnabledSetting(component(context, it.alias), PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
        }
        for (v in all) if (v.key != key) {
            pm.setComponentEnabledSetting(component(context, v.alias), PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
        }
    }
}
