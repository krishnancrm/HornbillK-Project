package com.example.hornbillk

import android.content.Context
import org.json.JSONObject

class DataRepository(context: Context) {
    private val prefs = context.getSharedPreferences("HornbillConnections", Context.MODE_PRIVATE)

    // This list holds just the names (aliases) for the dropdown
    val displayAliases = ArrayList<String>()

    // This map holds the actual full config objects in memory
    private val configMap = HashMap<String, ConnectionConfig>()

    init {
        loadSavedConnections()
    }

    fun saveConnection(config: ConnectionConfig) {
        // 1. Save to Memory
        configMap[config.alias] = config
        if (!displayAliases.contains(config.alias)) {
            displayAliases.add(config.alias)
        }

        // 2. Save to Disk (SharedPreferences) as JSON string
        try {
            val json = JSONObject()
            json.put("alias", config.alias)
            json.put("url", config.cloudflareTunnelUrl)
            json.put("apiKey", config.apiKey)
            json.put("sim", config.piM2mSimNumber)

            // Save the JSON string using the Alias as the key suffix
            prefs.edit().putString("CONFIG_${config.alias}", json.toString()).apply()

            // ⚡ UPDATED: Also update the master list of aliases using the Constant key value
            val aliasSet = HashSet(displayAliases)
            prefs.edit().putStringSet(Constants.CONNECTIONS_PREF_KEY, aliasSet).apply()

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun loadSavedConnections() {
        displayAliases.clear()
        configMap.clear()

        // ⚡ UPDATED: Get list of all saved alias names using the Constant key value
        val savedAliases = prefs.getStringSet(Constants.CONNECTIONS_PREF_KEY, emptySet()) ?: emptySet()

        // 2. Loop through them and reconstruct the objects
        for (alias in savedAliases) {
            displayAliases.add(alias)
            val jsonString = prefs.getString("CONFIG_$alias", null)

            if (jsonString != null) {
                try {
                    val json = JSONObject(jsonString)
                    val config = ConnectionConfig(
                        alias = json.optString("alias"),
                        cloudflareTunnelUrl = json.optString("url"),
                        apiKey = json.optString("apiKey"),
                        piM2mSimNumber = json.optString("sim")
                    )
                    configMap[alias] = config
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
        displayAliases.sort() // Keep dropdown alphabetical
    }

    fun getConnection(alias: String): ConnectionConfig? {
        return configMap[alias]
    }
}