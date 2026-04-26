package com.spandan.instanthotspot.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Controller may remember several paired hosts, each with its own shared secret
 * and a chosen "active" host for BLE commands.
 */
data class PairedHostEntry(
    val address: String,
    val displayName: String?,
    val secret: String,
    val pairedAtMs: Long,
)

object PairedHostRegistry {
    private const val PREF = "instant_hotspot_prefs"
    private const val KEY = "paired_hosts_v1"

    private fun normAddr(a: String): String = a.trim().uppercase()

    private fun loadJsonOnly(context: Context): List<PairedHostEntry> {
        val json = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getString(KEY, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(json)
            val out = ArrayList<PairedHostEntry>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val name = o.optString("displayName", "").trim().ifBlank { null }
                out.add(
                    PairedHostEntry(
                        address = o.getString("address"),
                        displayName = name,
                        secret = o.getString("secret"),
                        pairedAtMs = o.getLong("pairedAtMs"),
                    ),
                )
            }
            out
        }.getOrDefault(emptyList())
    }

    private fun save(context: Context, list: List<PairedHostEntry>) {
        val arr = JSONArray()
        for (e in list) {
            val o = JSONObject()
            o.put("address", normAddr(e.address))
            o.put("displayName", e.displayName ?: "")
            o.put("secret", e.secret)
            o.put("pairedAtMs", e.pairedAtMs)
            arr.put(o)
        }
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY, arr.toString())
            .commit()
    }

    /**
     * Legacy: [AppPrefs.lastPairedHost] + [AppPrefs.sharedSecret] + [AppPrefs.pairedHostDisplayName] + [KEY_CLIENT_PAIRED].
     * Does not call [AppPrefs.isClientPaired] to avoid re-entrancy with [PairedHostRegistry.hasAny].
     */
    fun migrateIfNeeded(context: Context) {
        if (loadJsonOnly(context).isNotEmpty()) return
        val prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        if (!prefs.getBoolean("client_paired", false)) return
        val hostAddr = AppPrefs.lastPairedHost(context) ?: return
        if (hostAddr.isBlank() || hostAddr == "manual-secret") return
        if (!AppPrefs.hasNonDefaultSecret(context)) return
        val secret = AppPrefs.sharedSecret(context)
        val n = normAddr(hostAddr)
        val list = listOf(
            PairedHostEntry(
                address = n,
                displayName = AppPrefs.pairedHostDisplayName(context),
                secret = secret,
                pairedAtMs = System.currentTimeMillis(),
            ),
        )
        save(context, list)
    }

    fun all(context: Context): List<PairedHostEntry> {
        migrateIfNeeded(context)
        return loadJsonOnly(context)
    }

    fun hasAny(context: Context): Boolean = all(context).isNotEmpty()

    /**
     * Secret for sending commands: must match a saved host entry (preferred, then last completed pair,
     * then most recently added). Stale [AppPrefs.preferredHostAddress] not in the list is ignored.
     */
    fun activeSecret(context: Context): String? {
        val list = all(context)
        if (list.isEmpty()) return null
        val preferred = AppPrefs.preferredHostAddress(context)?.let { normAddr(it) }
        if (preferred != null) {
            list.firstOrNull { normAddr(it.address) == preferred }?.let { return it.secret }
        }
        val last = AppPrefs.lastPairedHost(context)?.let { normAddr(it) }
        if (last != null) {
            list.firstOrNull { normAddr(it.address) == last }?.let { return it.secret }
        }
        return list.maxByOrNull { it.pairedAtMs }?.secret
    }

    fun upsert(
        context: Context,
        address: String,
        secret: String,
        displayName: String?,
    ) {
        if (address.isBlank() || secret.isBlank()) return
        val n = normAddr(address)
        val list = loadJsonOnly(context).filter { normAddr(it.address) != n }.toMutableList()
        list.add(
            PairedHostEntry(
                address = n,
                displayName = displayName?.trim()?.ifBlank { null },
                secret = secret,
                pairedAtMs = System.currentTimeMillis(),
            ),
        )
        save(context, list)
    }

    fun remove(context: Context, address: String) {
        if (address.isBlank()) return
        val n = normAddr(address)
        val list = loadJsonOnly(context).filter { normAddr(it.address) != n }
        if (list.isEmpty()) {
            context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .edit()
                .remove(KEY)
                .commit()
        } else {
            save(context, list)
        }
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY)
            .commit()
    }
}
