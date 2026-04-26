package com.spandan.instanthotspot.core

import android.content.Context

object AppPrefs {
    private const val PREFS_NAME = "instant_hotspot_prefs"
    private const val KEY_SHARED_SECRET = "shared_secret"
    private const val KEY_LAST_ACCEPTED_TS = "last_accepted_timestamp"
    private const val KEY_PENDING_PAIR_CODE = "pending_pair_code"
    private const val KEY_APPROVED_PAIR_CODE = "approved_pair_code"
    private const val KEY_PAIRING_MODE_ENABLED = "pairing_mode_enabled"
    private const val KEY_HOST_SERVICE_ACTIVE = "host_service_active"
    private const val KEY_CLIENT_PAIRED = "client_paired"
    private const val KEY_LAST_HOST_REACHABLE_MS = "last_host_reachable_ms"
    private const val KEY_LAST_SYNCED_HOTSPOT_CONFIG = "last_synced_hotspot_config"
    private const val KEY_LAST_PAIRED_HOST = "last_paired_host"
    private const val KEY_LAST_PAIRED_CONTROLLER = "last_paired_controller"
    private const val KEY_LAST_AP_STATE = "last_ap_state_line"

    // Replace with pairing-generated secret in the next milestone.
    private const val DEFAULT_DEV_SECRET = "change-me-before-production"

    fun sharedSecret(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_SHARED_SECRET, DEFAULT_DEV_SECRET) ?: DEFAULT_DEV_SECRET
    }

    fun setSharedSecret(context: Context, secret: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_SHARED_SECRET, secret.trim())
            .apply()
    }

    fun hasNonDefaultSecret(context: Context): Boolean {
        return sharedSecret(context) != DEFAULT_DEV_SECRET
    }

    fun lastAcceptedTimestamp(context: Context): Long {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getLong(KEY_LAST_ACCEPTED_TS, 0L)
    }

    fun setLastAcceptedTimestamp(context: Context, value: Long) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_LAST_ACCEPTED_TS, value)
            .apply()
    }

    fun pendingPairCode(context: Context): String? {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_PENDING_PAIR_CODE, null)
    }

    fun setPendingPairCode(context: Context, code: String?) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PENDING_PAIR_CODE, code)
            .commit()
    }

    fun approvedPairCode(context: Context): String? {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_APPROVED_PAIR_CODE, null)
    }

    fun setApprovedPairCode(context: Context, code: String?) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_APPROVED_PAIR_CODE, code)
            .commit()
    }

    fun isPairingModeEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_PAIRING_MODE_ENABLED, true)
    }

    fun setPairingModeEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_PAIRING_MODE_ENABLED, enabled)
            .apply()
    }

    fun isHostServiceActive(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_HOST_SERVICE_ACTIVE, false)
    }

    fun setHostServiceActive(context: Context, active: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_HOST_SERVICE_ACTIVE, active)
            .apply()
    }

    fun isClientPaired(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_CLIENT_PAIRED, false)
    }

    fun setClientPaired(context: Context, paired: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_CLIENT_PAIRED, paired)
            .apply()
    }

    fun markHostReachableNow(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_LAST_HOST_REACHABLE_MS, System.currentTimeMillis())
            .apply()
    }

    fun isHostReachableRecently(context: Context, windowMs: Long = 30_000L): Boolean {
        val ts = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getLong(KEY_LAST_HOST_REACHABLE_MS, 0L)
        if (ts <= 0) return false
        return System.currentTimeMillis() - ts <= windowMs
    }

    fun lastSyncedHotspotConfig(context: Context): String? {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_LAST_SYNCED_HOTSPOT_CONFIG, null)
    }

    fun setLastSyncedHotspotConfig(context: Context, config: String?) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LAST_SYNCED_HOTSPOT_CONFIG, config)
            .commit()
    }

    fun lastPairedHost(context: Context): String? {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_LAST_PAIRED_HOST, null)
    }

    fun setLastPairedHost(context: Context, hostId: String?) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LAST_PAIRED_HOST, hostId)
            .apply()
    }

    fun lastPairedController(context: Context): String? {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_LAST_PAIRED_CONTROLLER, null)
    }

    fun setLastPairedController(context: Context, controllerId: String?) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LAST_PAIRED_CONTROLLER, controllerId)
            .apply()
    }

    fun setLastApStateLine(context: Context, line: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LAST_AP_STATE, line)
            .apply()
    }

    fun lastApStateLine(context: Context): String? {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_LAST_AP_STATE, null)
    }

    /** Clears host pairing state, command replay cursor, and sets a new shared secret. */
    fun unpairAsHostWithNewSecret(context: Context, newSecret: String) {
        setLastPairedController(context, null)
        setPendingPairCode(context, null)
        setApprovedPairCode(context, null)
        setLastAcceptedTimestamp(context, 0L)
        setSharedSecret(context, newSecret)
    }

    /** Clears controller “paired” state and synced config; resets secret to the default dev placeholder. */
    fun unpairAsController(context: Context) {
        setClientPaired(context, false)
        setLastPairedHost(context, null)
        setLastSyncedHotspotConfig(context, null)
        setSharedSecret(context, DEFAULT_DEV_SECRET)
    }
}
