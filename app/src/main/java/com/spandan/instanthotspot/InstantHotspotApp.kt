package com.spandan.instanthotspot

import android.app.Application
import com.google.android.material.color.DynamicColors

class InstantHotspotApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Follow system wallpaper accent on Android 12+.
        DynamicColors.applyToActivitiesIfAvailable(this)
    }
}
