package com.buddyv2.app

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/** Persistent, private conversation context stored only in this app's SharedPreferences. */
class ConversationContext(context: Context) {
    companion object {
        private const val TAG = "ConversationContext"
        private const val MAX_HISTORY = 10
        private const val PREFS = "buddy_conversation"
    }

    data class Exchange(val userInput: String, val buddyResponse: String, val timestamp: Long = System.currentTimeMillis())

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val history = mutableListOf<Exchange>()
    private var lastMatchedContact: String? = null
    private var lastAction: String? = null

    init {
        runCatching {
            val saved = JSONArray(prefs.getString("history", "[]"))
            for (i in 0 until saved.length()) {
                val item = saved.getJSONObject(i)
                history.add(Exchange(item.optString("user"), item.optString("response"), item.optLong("time")))
            }
            lastMatchedContact = prefs.getString("last_contact", null)
            lastAction = prefs.getString("last_action", null)
        }.onFailure { Log.w(TAG, "Could not restore conversation context", it) }
    }

    @Synchronized fun addExchange(userInput: String, buddyResponse: String) {
        history.add(Exchange(userInput, buddyResponse))
        while (history.size > MAX_HISTORY) history.removeAt(0)
        persist()
    }

    @Synchronized fun getRecentExchanges(count: Int): List<Exchange> = history.takeLast(count.coerceAtLeast(0))
    @Synchronized fun getAllHistory(): List<Exchange> = history.toList()

    @Synchronized fun clear() {
        history.clear()
        lastMatchedContact = null
        lastAction = null
        prefs.edit().remove("history").remove("last_contact").remove("last_action").apply()
    }

    @Synchronized fun setLastMatchedContact(contactName: String?) {
        lastMatchedContact = contactName
        prefs.edit().putString("last_contact", contactName).apply()
    }
    @Synchronized fun getLastMatchedContact(): String? = lastMatchedContact

    @Synchronized fun setLastAction(action: String?) {
        lastAction = action
        prefs.edit().putString("last_action", action).apply()
    }
    @Synchronized fun getLastAction(): String? = lastAction

    @Synchronized fun buildContextSummary(): String {
        if (history.isEmpty()) return ""
        return buildString {
            append("Recent conversation:\n")
            history.takeLast(5).forEach {
                append("User: ${it.userInput}\nBuddy: ${it.buddyResponse}\n")
            }
        }
    }

    private fun persist() {
        val json = JSONArray()
        history.forEach { json.put(JSONObject().put("user", it.userInput).put("response", it.buddyResponse).put("time", it.timestamp)) }
        prefs.edit().putString("history", json.toString()).apply()
        Log.d(TAG, "Saved conversation context (${history.size} exchanges)")
    }
}
