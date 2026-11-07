package com.buddyv2.app

import android.util.Log

/**
 * Parses natural language input and maps to existing Buddy commands.
 * Reuses the existing command handlers in MainActivity.
 */
class IntentParser(
    private val conversationContext: ConversationContext
) {

    companion object {
        private const val TAG = "IntentParser"
    }

    sealed class Intent {
        data class OpenApp(val appName: String) : Intent()
        data class CallContact(val contactName: String) : Intent()
        data class WhatsAppMessage(val contactName: String, val message: String) : Intent()
        data class SearchWeb(val query: String) : Intent()
        data class Help(val topic: String?) : Intent()
        data class Unknown(val originalText: String) : Intent()
        object StopSpeaking : Intent()
        object RepeatLast : Intent()
    }

    fun parse(input: String): Intent {
        val trimmed = input.trim()
        val lower = trimmed.lowercase()

        Log.d(TAG, "Parsing: '$trimmed'")

        // Check for stop speaking command
        if (isStopCommand(lower)) {
            return Intent.StopSpeaking
        }

        // Check for repeat last response
        if (isRepeatCommand(lower)) {
            return Intent.RepeatLast
        }

        // Try to match known command patterns
        return when {
            isOpenCommand(lower) -> parseOpenCommand(trimmed, lower)
            isCallCommand(lower) -> parseCallCommand(trimmed, lower)
            isWhatsAppCommand(lower) -> parseWhatsAppCommand(trimmed, lower)
            isSearchCommand(lower) -> parseSearchCommand(trimmed, lower)
            isHelpCommand(lower) -> parseHelpCommand(lower)
            else -> {
                // Try to infer from context for follow-ups
                inferFromContext(trimmed, lower)
            }
        }
    }

    private fun isStopCommand(input: String): Boolean {
        val stopPatterns = listOf("stop", "shut up", "quiet", "cancel", "never mind")
        return stopPatterns.any { input.contains(it) }
    }

    private fun isRepeatCommand(input: String): Boolean {
        val repeatPatterns = listOf("repeat", "say again", "what did you say", "say that again")
        return repeatPatterns.any { input.contains(it) }
    }

    private fun isOpenCommand(input: String): Boolean {
        val openPatterns = listOf("open", "launch", "start", "run")
        return openPatterns.any { pattern ->
            input.startsWith(pattern + " ") || input == pattern
        }
    }

    private fun isCallCommand(input: String): Boolean {
        val callPatterns = listOf("call", "dial", "phone", "ring")
        return callPatterns.any { pattern ->
            input.startsWith(pattern + " ") || input == pattern
        }
    }

    private fun isWhatsAppCommand(input: String): Boolean {
        val whatsappPatterns = listOf("whatsapp", "message", "text", "send message", "send whatsapp")
        return whatsappPatterns.any { pattern ->
            input.startsWith(pattern + " ") || input == pattern
        }
    }

    private fun isSearchCommand(input: String): Boolean {
        val searchPatterns = listOf("search", "google", "look up", "find", "search for", "look for")
        return searchPatterns.any { pattern ->
            input.startsWith(pattern + " ") || input == pattern
        }
    }

    private fun isHelpCommand(input: String): Boolean {
        val helpPatterns = listOf("help", "what can you do", "commands", "how does this work")
        return helpPatterns.any { pattern ->
            input == pattern || input.startsWith(pattern + " ")
        }
    }

    private fun parseOpenCommand(original: String, lower: String): Intent {
        val patterns = listOf("open ", "launch ", "start ", "run ")
        for (pattern in patterns) {
            if (lower.startsWith(pattern)) {
                val appName = original.substring(pattern.length).trim()
                if (appName.isNotEmpty()) {
                    return Intent.OpenApp(appName)
                }
            }
        }
        if (lower == "open" || lower == "launch" || lower == "start" || lower == "run") {
            return Intent.OpenApp(original)
        }
        return Intent.OpenApp(original)
    }

    private fun parseCallCommand(original: String, lower: String): Intent {
        val patterns = listOf("call ", "dial ", "phone ", "ring ")
        for (pattern in patterns) {
            if (lower.startsWith(pattern)) {
                val contactName = original.substring(pattern.length).trim()
                if (contactName.isNotEmpty()) {
                    return Intent.CallContact(contactName)
                }
            }
        }
        if (lower == "call" || lower == "dial" || lower == "phone" || lower == "ring") {
            return Intent.CallContact(original)
        }
        return Intent.CallContact(original)
    }

    private fun parseWhatsAppCommand(original: String, lower: String): Intent {
        val patterns = listOf("whatsapp ", "message ", "text ", "send message ", "send whatsapp ")
        for (pattern in patterns) {
            if (lower.startsWith(pattern)) {
                val remaining = original.substring(pattern.length).trim()
                return Intent.WhatsAppMessage(contactName = remaining, message = "")
            }
        }
        if (lower == "whatsapp" || lower == "message" || lower == "text") {
            return Intent.WhatsAppMessage(contactName = original, message = "")
        }
        return Intent.WhatsAppMessage(contactName = original, message = "")
    }

    private fun parseSearchCommand(original: String, lower: String): Intent {
        val patterns = listOf("search for ", "search ", "google ", "look up ", "find ", "look for ")
        for (pattern in patterns) {
            if (lower.startsWith(pattern)) {
                val query = original.substring(pattern.length).trim()
                if (query.isNotEmpty()) {
                    return Intent.SearchWeb(query)
                }
            }
        }
        if (lower == "search" || lower == "google" || lower == "look up" || lower == "find") {
            return Intent.SearchWeb(original)
        }
        return Intent.SearchWeb(original)
    }

    private fun parseHelpCommand(lower: String): Intent {
        val patterns = listOf("help ", "what can you do ", "commands ", "how does this work ")
        for (pattern in patterns) {
            if (lower.startsWith(pattern)) {
                val topic = lower.substring(pattern.length).trim()
                return Intent.Help(topic.ifBlank { null })
            }
        }
        return Intent.Help(null)
    }

    private fun inferFromContext(original: String, lower: String): Intent {
        val lastAction = conversationContext.getLastAction()
        val lastContact = conversationContext.getLastMatchedContact()

        if (lower.contains("call him") || lower.contains("call her") || lower.contains("call them")) {
            lastContact?.let { return Intent.CallContact(it) }
        }

        if (lower.contains("message him") || lower.contains("message her") || lower.contains("text him") || lower.contains("text her")) {
            lastContact?.let { return Intent.WhatsAppMessage(it, "") }
        }

        if (lower.contains("search for that") || lower.contains("look that up") || lower.contains("google that")) {
        }

        return Intent.SearchWeb(original)
    }
}