package com.buddyv2.app

import android.util.Log

/**
 * Maintains conversational context for multi-turn voice interactions.
 * Stores recent exchanges to enable follow-up understanding.
 */
class ConversationContext {

    companion object {
        private const val TAG = "ConversationContext"
        private const val MAX_HISTORY = 10
    }

    data class Exchange(
        val userInput: String,
        val buddyResponse: String,
        val timestamp: Long = System.currentTimeMillis()
    )

    private val history = mutableListOf<Exchange>()
    private var lastMatchedContact: String? = null
    private var lastAction: String? = null

    fun addExchange(userInput: String, buddyResponse: String) {
        history.add(Exchange(userInput, buddyResponse))
        if (history.size > MAX_HISTORY) {
            history.removeAt(0)
        }
        Log.d(TAG, "Added exchange. History size: ${history.size}")
    }

    fun getRecentExchanges(count: Int): List<Exchange> {
        return history.takeLast(count)
    }

    fun getAllHistory(): List<Exchange> {
        return history.toList()
    }

    fun clear() {
        history.clear()
        lastMatchedContact = null
        lastAction = null
        Log.d(TAG, "Conversation context cleared")
    }

    fun setLastMatchedContact(contactName: String?) {
        lastMatchedContact = contactName
    }

    fun getLastMatchedContact(): String? {
        return lastMatchedContact
    }

    fun setLastAction(action: String?) {
        lastAction = action
    }

    fun getLastAction(): String? {
        return lastAction
    }

    /**
     * Builds a context string for the intent parser to understand follow-ups.
     */
    fun buildContextSummary(): String {
        if (history.isEmpty()) return ""

        val sb = StringBuilder("Recent conversation:\n")
        for (exchange in history.takeLast(5)) {
            sb.append("User: ${exchange.userInput}\n")
            sb.append("Buddy: ${exchange.buddyResponse}\n")
        }
        return sb.toString()
    }
}