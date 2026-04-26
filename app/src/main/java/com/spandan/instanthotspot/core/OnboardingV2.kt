package com.spandan.instanthotspot.core

import android.content.Context

/**
 * Multi-step walkthrough (v0.2.0+). One int in prefs: -1 = finished, 0 = welcome … last = role-dependent.
 * Last step (tiles & widgets) exists only in controller mode; host flow is one page shorter.
 */
object OnboardingV2 {
    private const val PREFS = "instant_hotspot_prefs"
    private const val KEY_MODE = "mode"
    private const val MODE_HOST = "host"
    private const val MODE_CONTROLLER = "controller"
    private const val KEY_STATE = "onboarding_v2_state"
    private const val KEY_MIGRATED = "onboarding_v2_migrated"
    /** Must match [AppPrefs] key used for simple vs full home. */
    private const val KEY_USE_SIMPLE_HOME = "use_simple_controller_home"
    /** Must match [AppPrefs] — turn off open pairing after onboarding completes. */
    private const val KEY_PAIRING_MODE_ENABLED = "pairing_mode_enabled"

    /** With controller + shortcuts step; [pageCount] is 5 for host, 6 for controller. */
    const val PAGE_COUNT_MAX = 6

    /**
     * Host skips the “tiles & widgets” step. Must match [com.spandan.instanthotspot.MainActivity] `mode` pref.
     */
    fun isHostMode(context: Context): Boolean {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_MODE, MODE_CONTROLLER) == MODE_HOST
    }

    /**
     * Walkthrough that uses the **host** branch (5 steps, host pairing, etc.).
     * While host prereqs are not met, [AppPrefs] may temporarily keep `mode` = controller; we still
     * treat the user as host for UI if they chose **Host** on the role step ([isOnboardingWantsHost]).
     */
    fun isHostModeForOnboarding(context: Context): Boolean {
        if (isHostMode(context)) return true
        if (isFlowComplete(context)) return false
        return AppPrefs.isOnboardingWantsHost(context)
    }

    fun pageCount(context: Context): Int = if (isHostModeForOnboarding(context)) 5 else 6

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
        val max = (pageCount(context) - 1).coerceAtLeast(0)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_STATE, page.coerceIn(0, max))
            .apply()
    }

    /**
     * Mark the walkthrough finished. The main home is always **controller** (minimal remote) after
     * onboarding; the user can switch to host or full console from the app. Uses
     * SharedPreferences commit() so the next Activity after recreate() reads updated prefs.
     */
    fun markFlowComplete(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_STATE, -1)
            .putString(KEY_MODE, MODE_CONTROLLER)
            .putBoolean(KEY_USE_SIMPLE_HOME, true)
            .putBoolean(KEY_PAIRING_MODE_ENABLED, false)
            .commit()
    }

    fun resetFlow(context: Context) {
        setPage(context, 0)
    }
}
