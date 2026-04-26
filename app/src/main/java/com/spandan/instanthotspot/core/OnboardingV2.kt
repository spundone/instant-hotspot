package com.spandan.instanthotspot.core

import android.content.Context

/**
 * Multi-step walkthrough (v0.2.0+). One int in prefs: -1 = finished, 0–5 = current page index.
 */
object OnboardingV2 {
    private const val PREFS = "instant_hotspot_prefs"
    private const val KEY_STATE = "onboarding_v2_state"
    private const val KEY_MIGRATED = "onboarding_v2_migrated"
    /** Must match [AppPrefs] key used for simple vs full home. */
    private const val KEY_USE_SIMPLE_HOME = "use_simple_controller_home"

    const val PAGE_COUNT = 6

    fun migrateIfNeeded(context: Context) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (p.getBoolean(KEY_MIGRATED, false)) return
        val usedBefore =
            AppPrefs.isClientPaired(context) ||
                !AppPrefs.lastPairedHost(context).isNullOrBlank() ||
                !AppPrefs.lastPairedController(context).isNullOrBlank()
        if (usedBefore) {
            p.edit()
                .putInt(KEY_STATE, -1)
                .putBoolean(KEY_MIGRATED, true)
                .apply()
            AppPrefs.setUseSimpleHome(context, false)
        } else {
            p.edit()
                .putInt(KEY_STATE, 0)
                .putBoolean(KEY_MIGRATED, true)
                .apply()
            AppPrefs.setUseSimpleHome(context, true)
        }
    }

    fun currentPageOrDone(context: Context): Int {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_STATE, -1)
    }

    fun isFlowComplete(context: Context): Boolean = currentPageOrDone(context) < 0

    fun setPage(context: Context, page: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_STATE, page.coerceIn(0, PAGE_COUNT - 1))
            .apply()
    }

    /**
     * Mark the walkthrough finished. [isController] chooses whether the home screen is the minimal
     * remote (controller) or full console (host).
     */
    fun markFlowComplete(context: Context, isController: Boolean) {
        // commit() so the next Activity in recreate() always reads finished + simple-home flags
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_STATE, -1)
            .putBoolean(KEY_USE_SIMPLE_HOME, isController)
            .commit()
    }

    fun resetFlow(context: Context) {
        setPage(context, 0)
    }
}
