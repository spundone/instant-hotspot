package com.spandan.instanthotspot.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class HostPendingPairSnapshot(
    val nonce: String,
    val candidateSecret: String,
    val code: String,
    val controllerPublicKeyB64: String,
    val hostPublicKeyB64: String,
    val createdAtMs: Long,
)

object HostPairingPersistence {
    private const val KEY = "ble_pending_pair_snapshots_v1"

    fun load(context: Context): List<HostPendingPairSnapshot> {
        val json = context.getSharedPreferences("instant_hotspot_prefs", Context.MODE_PRIVATE)
            .getString(KEY, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(json)
            val out = ArrayList<HostPendingPairSnapshot>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(
                    HostPendingPairSnapshot(
                        nonce = o.getString("nonce"),
                        candidateSecret = o.getString("candidateSecret"),
                        code = o.getString("code"),
                        controllerPublicKeyB64 = o.getString("controllerPublicKeyB64"),
                        hostPublicKeyB64 = o.getString("hostPublicKeyB64"),
                        createdAtMs = o.getLong("createdAtMs"),
                    ),
                )
            }
            out
        }.getOrDefault(emptyList())
    }

    fun replaceAll(context: Context, list: List<HostPendingPairSnapshot>) {
        val arr = JSONArray()
        for (e in list) {
            val o = JSONObject()
            o.put("nonce", e.nonce)
            o.put("candidateSecret", e.candidateSecret)
            o.put("code", e.code)
            o.put("controllerPublicKeyB64", e.controllerPublicKeyB64)
            o.put("hostPublicKeyB64", e.hostPublicKeyB64)
            o.put("createdAtMs", e.createdAtMs)
            arr.put(o)
        }
        context.getSharedPreferences("instant_hotspot_prefs", Context.MODE_PRIVATE)
            .edit()
            .putString(KEY, arr.toString())
            .commit()
    }

    fun clear(context: Context) {
        context.getSharedPreferences("instant_hotspot_prefs", Context.MODE_PRIVATE)
            .edit()
            .remove(KEY)
            .commit()
    }
}
