package com.gp5.launcher.core.data

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import org.json.JSONArray
import org.json.JSONObject

/** DataStore preferences as JSON with a type tag per value, for settings backup and restore. */
internal object PrefsJson {
    fun write(p: Preferences): JSONObject = JSONObject().apply {
        for ((key, value) in p.asMap()) {
            val (t, v) = when (value) {
                is Boolean -> "b" to value
                is Int -> "i" to value
                is Long -> "l" to value
                is Double -> "d" to value
                is Float -> "f" to value.toDouble()
                is String -> "s" to value
                is Set<*> -> "set" to JSONArray(value.map { it.toString() })
                else -> continue
            }
            put(key.name, JSONObject().put("t", t).put("v", v))
        }
    }

    fun read(json: JSONObject, into: MutablePreferences) {
        into.clear()
        for (name in json.keys()) {
            val o = json.optJSONObject(name) ?: continue
            when (o.optString("t")) {
                "b" -> into[booleanPreferencesKey(name)] = o.getBoolean("v")
                "i" -> into[intPreferencesKey(name)] = o.getInt("v")
                "l" -> into[longPreferencesKey(name)] = o.getLong("v")
                "d" -> into[doublePreferencesKey(name)] = o.getDouble("v")
                "f" -> into[floatPreferencesKey(name)] = o.getDouble("v").toFloat()
                "s" -> into[stringPreferencesKey(name)] = o.getString("v")
                "set" -> into[stringSetPreferencesKey(name)] = o.getJSONArray("v").let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }
            }
        }
    }
}
