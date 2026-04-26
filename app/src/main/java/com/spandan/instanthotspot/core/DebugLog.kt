package com.spandan.instanthotspot.core

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object DebugLog {
    private const val PREFS_NAME = "instant_hotspot_prefs"
    private const val KEY_DEBUG_LOG = "debug_log"
    private const val MAX_LINES = 120

    @Synchronized
    fun append(context: Context, tag: String, message: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val existing = prefs.getString(KEY_DEBUG_LOG, "").orEmpty()
        val timestamp = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        val line = "[$timestamp][$tag] ${message.trim()}"
        val updated = (existing.lines().filter { it.isNotBlank() } + line)
            .takeLast(MAX_LINES)
            .joinToString("\n")
        prefs.edit().putString(KEY_DEBUG_LOG, updated).apply()
    }

    fun read(context: Context): String {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_DEBUG_LOG, "")
            .orEmpty()
            .ifBlank { "No debug events yet." }
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_DEBUG_LOG)
            .apply()
    }
}
