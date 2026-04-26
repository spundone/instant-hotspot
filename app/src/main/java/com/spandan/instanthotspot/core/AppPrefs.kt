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
    private const val KEY_USE_SIMPLE_HOME = "use_simple_controller_home"
    private const val KEY_TILE_NEXT_FORCE_ON = "tile_next_force_on"
    private const val KEY_PREFERRED_HOST_ADDRESS = "preferred_host_address"
    private const val KEY_MANUAL_HOTSPOT_SSID = "manual_hotspot_ssid"
    private const val KEY_MANUAL_HOTSPOT_PASSWORD = "manual_hotspot_password"
    private const val KEY_PAIRED_HOST_DISPLAY_NAME = "paired_host_display_name"
    private const val KEY_LAST_HOST_AP_STATE = "last_host_ap_state_int"
    private const val KEY_LAST_STATE_FETCH_MS = "last_host_state_fetch_ms"
    private const val KEY_HOST_BOND_ALLOWLIST = "host_bond_allowlist_enabled"
    private const val KEY_HOST_PAIRED_SINCE_MS = "host_paired_since_ms"
    private const val KEY_VERBOSE_DEBUG_UI = "verbose_debug_ui"
    private const val KEY_OB_WANTS_HOST = "onboarding_wants_host"
    private const val KEY_PREFERRED_NETWORK_MODE_BACKUP = "preferred_network_mode_backup_v1"

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
        setHostPairedSinceMs(context, 0L)
        setPendingPairCode(context, null)
        setApprovedPairCode(context, null)
        setLastAcceptedTimestamp(context, 0L)
        setSharedSecret(context, newSecret)
        HostPairingPersistence.clear(context)
    }

    /** Clears controller “paired” state and synced config; resets secret to the default dev placeholder. */
    fun unpairAsController(context: Context) {
        setClientPaired(context, false)
        setLastPairedHost(context, null)
        setPairedHostDisplayName(context, null)
        setLastSyncedHotspotConfig(context, null)
        setLastHostApState(context, HostStateCodec.PREF_UNKNOWN)
        setSharedSecret(context, DEFAULT_DEV_SECRET)
    }

    /**
     * When true, controller home uses a minimal remote on/off screen (after onboarding v2).
     * Host always uses the full console regardless.
     */
    fun useSimpleHome(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_USE_SIMPLE_HOME, true)
    }

    fun setUseSimpleHome(context: Context, useSimple: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_USE_SIMPLE_HOME, useSimple)
            .apply()
    }

    /** When true, full console shows verbose panels, long install summary, and extra debug. */
    fun isVerboseDebugEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_VERBOSE_DEBUG_UI, false)
    }

    fun setVerboseDebugEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_VERBOSE_DEBUG_UI, enabled)
            .apply()
    }

    /**
     * Onboarding: user picked Host in the walkthrough, but the device is not yet allowed to
     * commit to host mode (Magisk + root checks, or "Verify" not run). While true, the role step
     * should block "Next" until [HostOnboarding.isHostSetupSatisfied] and verify succeeds.
     */
    fun isOnboardingWantsHost(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_OB_WANTS_HOST, false)
    }

    fun setOnboardingWantsHost(context: Context, wants: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_OB_WANTS_HOST, wants)
            .apply()
    }

    /** Tile fallback toggle state so QS uses ON/OFF commands (same as app buttons). */
    fun nextTileCommandIsOn(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_TILE_NEXT_FORCE_ON, true)
    }

    fun setNextTileCommandIsOn(context: Context, value: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_TILE_NEXT_FORCE_ON, value)
            .apply()
    }

    fun preferredHostAddress(context: Context): String? {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_PREFERRED_HOST_ADDRESS, null)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }

    fun setPreferredHostAddress(context: Context, address: String?) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PREFERRED_HOST_ADDRESS, address?.trim())
            .apply()
    }

    fun manualHotspotSsid(context: Context): String? {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_MANUAL_HOTSPOT_SSID, null)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }

    fun setManualHotspotSsid(context: Context, ssid: String?) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_MANUAL_HOTSPOT_SSID, ssid?.trim())
            .apply()
    }

    fun manualHotspotPassword(context: Context): String? {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_MANUAL_HOTSPOT_PASSWORD, null)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }

    fun setManualHotspotPassword(context: Context, password: String?) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_MANUAL_HOTSPOT_PASSWORD, password?.trim())
            .apply()
    }

    /** Bluetooth name of the paired host (controller), if known. */
    fun pairedHostDisplayName(context: Context): String? {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_PAIRED_HOST_DISPLAY_NAME, null)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }

    fun setPairedHostDisplayName(context: Context, name: String?) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PAIRED_HOST_DISPLAY_NAME, name?.trim())
            .apply()
    }

    /**
     * Last known soft AP state from host read: [HostStateCodec.PREF_ON], [HostStateCodec.PREF_OFF],
     * or [HostStateCodec.PREF_UNKNOWN].
     */
    fun lastHostApState(context: Context): Int {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_LAST_HOST_AP_STATE, HostStateCodec.PREF_UNKNOWN)
    }

    fun setLastHostApState(context: Context, state: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_LAST_HOST_AP_STATE, state)
            .apply()
    }

    fun lastHostStateFetchMs(context: Context): Long {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getLong(KEY_LAST_STATE_FETCH_MS, 0L)
    }

    fun markHostStateFetchedNow(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_LAST_STATE_FETCH_MS, System.currentTimeMillis())
            .apply()
    }

    fun shouldThrottleStateRefresh(context: Context, minIntervalMs: Long = 8_000L): Boolean {
        val t = lastHostStateFetchMs(context)
        if (t <= 0L) return false
        return System.currentTimeMillis() - t < minIntervalMs
    }

    /**
     * When enabled, only Bluetooth-bonded controllers may send hotspot commands (pairing unchanged).
     */
    fun isHostBondAllowlistEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_HOST_BOND_ALLOWLIST, false)
    }

    fun setHostBondAllowlistEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_HOST_BOND_ALLOWLIST, enabled)
            .apply()
    }

    /** Set when a controller successfully completes ECDH pairing on the host. Cleared on host unpair. */
    fun hostPairedSinceMs(context: Context): Long {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getLong(KEY_HOST_PAIRED_SINCE_MS, 0L)
    }

    fun setHostPairedSinceMs(context: Context, timeMs: Long) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_HOST_PAIRED_SINCE_MS, timeMs)
            .apply()
    }

    /**
     * Raw string from `settings get global preferred_network_mode` before tools apply NR-only, for revert.
     */
    fun preferredNetworkModeBackup(context: Context): String? {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_PREFERRED_NETWORK_MODE_BACKUP, null)
    }

    fun setPreferredNetworkModeBackup(context: Context, value: String?) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PREFERRED_NETWORK_MODE_BACKUP, value)
            .apply()
    }
}
