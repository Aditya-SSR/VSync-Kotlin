package com.minimallabs.vsync.core

import android.app.Activity
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.UpdateAvailability

/** Play in-app updates (flexible). Does nothing for a build not installed from Play. */
object AppUpdates {
    private var checked = false

    fun check(activity: Activity) {
        if (checked) return
        checked = true
        runCatching {
            val manager = AppUpdateManagerFactory.create(activity)
            manager.appUpdateInfo.addOnSuccessListener { info ->
                if (info.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE && info.isUpdateTypeAllowed(AppUpdateType.FLEXIBLE)) {
                    runCatching {
                        manager.startUpdateFlow(info, activity, AppUpdateOptions.newBuilder(AppUpdateType.FLEXIBLE).build())
                    }
                }
            }
        }
    }
}
