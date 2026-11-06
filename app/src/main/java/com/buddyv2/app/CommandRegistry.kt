package com.buddyv2.app

/**
 * Central registry for all @ commands.
 * Single source of truth for autocomplete suggestions and command metadata.
 * EXACTLY 4 ENTRIES: Open apps, Call, WhatsApp, Search
 */
object CommandRegistry {

    /**
     * Command category for grouping in autocomplete.
     */
    enum class Category(val label: String, val colorRes: Int) {
        APPS("Apps", R.color.category_apps),
        COMMUNICATION("Communication", R.color.category_communication),
        SEARCH("Search", R.color.category_search),
        SYSTEM("System", R.color.category_system)
    }

    /**
     * Command definition.
     * @property id Unique identifier
     * @property name Display name (e.g., "Open app")
     * @property syntax Syntax hint (e.g., "open <app name>")
     * @property description Brief description
     * @property iconRes Drawable resource for icon
     * @property category Category for grouping
     * @property example Example usage
     * @property trigger The @ trigger keyword (e.g., "open")
     */
    data class Command(
        val id: String,
        val name: String,
        val syntax: String,
        val description: String,
        val iconRes: Int,
        val category: Category,
        val example: String,
        val trigger: String = id
    )

    // ===== EXACTLY 4 COMMANDS =====
    private val commands = listOf(
        // 1. OPEN APPS - single entry, handles app launching
        Command(
            id = "open_app",
            name = "Open app",
            syntax = "open <app name>",
            description = "Launch an installed application",
            iconRes = android.R.drawable.ic_menu_agenda,
            category = Category.APPS,
            example = "@open chrome",
            trigger = "open"
        ),

        // 2. CALL - single entry, backed by actual contact-call functionality
        Command(
            id = "call_contact",
            name = "Call contact",
            syntax = "call <contact name>",
            description = "Call a contact from your phonebook",
            iconRes = android.R.drawable.ic_menu_call,
            category = Category.COMMUNICATION,
            example = "@call John Doe",
            trigger = "call"
        ),

        // 3. WHATSAPP - single entry, both "whatsapp" and "message" resolve here
        Command(
            id = "whatsapp_contact",
            name = "WhatsApp contact",
            syntax = "whatsapp <contact> <message>",
            description = "Send WhatsApp message to a contact",
            iconRes = android.R.drawable.ic_menu_send,
            category = Category.COMMUNICATION,
            example = "@whatsapp John Hello!",
            trigger = "whatsapp"
        ),

        // 4. SEARCH - single entry, aliases: search, google, look up, find
        Command(
            id = "search_web",
            name = "Search web",
            syntax = "search <query>",
            description = "Search the web via Google",
            iconRes = android.R.drawable.ic_menu_search,
            category = Category.SEARCH,
            example = "@search weather today",
            trigger = "search"
        )
    )

    /** All registered commands (exactly 4). */
    val allCommands: List<Command> = commands

    /**
     * Filter commands by query string.
     * Matches against name, syntax, description, trigger, and example.
     */
    fun filterCommands(query: String): List<Command> {
        if (query.isBlank()) return allCommands

        val lowerQuery = query.lowercase()
        return allCommands.filter { cmd ->
            cmd.name.lowercase().contains(lowerQuery) ||
                    cmd.syntax.lowercase().contains(lowerQuery) ||
                    cmd.description.lowercase().contains(lowerQuery) ||
                    cmd.trigger.lowercase().contains(lowerQuery) ||
                    cmd.example.lowercase().contains(lowerQuery)
        }
    }

    /**
     * Find command by trigger keyword.
     */
    fun findByTrigger(trigger: String): Command? {
        return allCommands.find { it.trigger == trigger.lowercase() }
    }
}