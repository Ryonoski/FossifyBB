package org.fossify.home.activities

import android.app.Activity

/** Shared state between the lab window and the activity running on the virtual display (same process). */
object LabState {
    @Volatile var targetActivity: Activity? = null
    @Volatile var targetDisplayId: Int? = null
    @Volatile var ticks: Int = 0
    @Volatile var clicks: Int = 0

    fun reset() {
        targetActivity = null
        targetDisplayId = null
        ticks = 0
        clicks = 0
    }
}
