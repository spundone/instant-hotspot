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

    /**
     * Saved page index while the walkthrough is in progress, or **-1** when finished.
     * **Default 0 (not -1)**: a missing key must mean "in progress" so [isHostModeForOnboarding]
     * and [isFlowComplete] are not wrong when prefs are read before migration or off the main
     * path. Treating a missing value like **-1** (done) would skip [AppPrefs.isOnboardingWantsHost]
     * and show controller pairing on the host role path.
     */
    fun currentPageOrDone(context: Context): Int {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_STATE, 0)
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
     * Mark the walkthrough finished. If [KEY_MODE] is already **host** (user verified the host
     * gate on the role step and [commitHostMode] ran), keep **host** and the **full** console; do
     * not drop them into the controller simple home, which is wrong and was causing post-onboarding
     * crashes and inconsistent state. Controllers: **controller** + simple home, as before.
     */
    fun markFlowComplete(context: Context) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val finishAsHost = p.getString(KEY_MODE, MODE_CONTROLLER) == MODE_HOST
        val e = p.edit()
            .putInt(KEY_STATE, -1)
        if (finishAsHost) {
            e.putString(KEY_MODE, MODE_HOST)
                .putBoolean(KEY_USE_SIMPLE_HOME, false)
        } else {
            e.putString(KEY_MODE, MODE_CONTROLLER)
                .putBoolean(KEY_USE_SIMPLE_HOME, true)
        }
        e.commit()
        AppPrefs.setOnboardingWantsHost(context, false)
    }

    fun resetFlow(context: Context) {
        setPage(context, 0)
    }
}
