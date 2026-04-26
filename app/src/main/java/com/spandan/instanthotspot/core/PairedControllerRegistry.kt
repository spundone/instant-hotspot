package com.spandan.instanthotspot.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Host stores one derived secret per paired controller (Bluetooth address).
 * Command verification uses the secret for the GATT writer's device address.
 */
data class PairedControllerEntry(
    val address: String,
    val secret: String,
    val pairedAtMs: Long,
)

object PairedControllerRegistry {
    private const val PREF = "instant_hotspot_prefs"
    private const val KEY = "paired_controllers_v1"

    private fun normAddr(a: String): String = a.trim().uppercase()

    private fun loadJsonOnly(context: Context): List<PairedControllerEntry> {
        val json = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getString(KEY, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(json)
            val out = ArrayList<PairedControllerEntry>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(
                    PairedControllerEntry(
                        address = o.getString("address"),
                        secret = o.getString("secret"),
                        pairedAtMs = o.getLong("pairedAtMs"),
                    ),
                )
            }
            out
        }.getOrDefault(emptyList())
    }

    private fun save(context: Context, list: List<PairedControllerEntry>) {
        val arr = JSONArray()
        for (e in list) {
            val o = JSONObject()
            o.put("address", normAddr(e.address))
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
     * Legacy [AppPrefs.lastPairedController] + host [AppPrefs.sharedSecret] (single-pairing era).
     */
    fun migrateIfNeeded(context: Context) {
        if (loadJsonOnly(context).isNotEmpty()) return
        val legacyAddr = AppPrefs.lastPairedController(context)?.trim() ?: return
        if (legacyAddr.isBlank()) return
        if (!AppPrefs.hasNonDefaultSecret(context)) return
        val secret = AppPrefs.sharedSecret(context)
        val n = normAddr(legacyAddr)
        val list = mutableListOf(
            PairedControllerEntry(
                address = n,
                secret = secret,
                pairedAtMs = System.currentTimeMillis(),
            ),
        )
        save(context, list)
    }

    fun all(context: Context): List<PairedControllerEntry> {
        migrateIfNeeded(context)
        return loadJsonOnly(context)
    }

    fun hasAny(context: Context): Boolean = all(context).isNotEmpty()

    fun secretForAddress(context: Context, address: String): String? {
        if (address.isBlank()) return null
        val want = normAddr(address)
        return all(context).firstOrNull { normAddr(it.address) == want }?.secret
    }

    fun upsert(context: Context, address: String, secret: String) {
        if (address.isBlank() || secret.isBlank()) return
        val n = normAddr(address)
        val list = loadJsonOnly(context).filter { normAddr(it.address) != n }.toMutableList()
        list.add(
            PairedControllerEntry(
                address = n,
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
