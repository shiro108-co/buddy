package com.buddyv2.app

/**
 * Central registry for all / commands.
 * Single source of truth for autocomplete suggestions and command metadata.
 * EXACTLY 5 ENTRIES: Open apps, Call, WhatsApp, Search, Help
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
     * @property syntax Syntax hint (e.g., "/open <app name>")
     * @property description Brief description
     * @property iconRes Drawable resource for icon
     * @property category Category for grouping
     * @property example Example usage
     * @property trigger The / trigger keyword (e.g., "open")
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

    // ===== EXACTLY 5 COMMANDS =====
    private val commands = listOf(
        // 1. OPEN APPS - single entry, handles app launching
        Command(
            id = "open_app",
            name = "Open app",
            syntax = "/open <app name>",
            description = "Launch an installed application",
            iconRes = android.R.drawable.ic_menu_agenda,
            category = Category.APPS,
            example = "/open chrome",
            trigger = "open"
        ),

        // 2. CALL - single entry, backed by actual contact-call functionality
        Command(
            id = "call_contact",
            name = "Call contact",
            syntax = "/call <contact name>",
            description = "Call a contact from your phonebook",
            iconRes = android.R.drawable.ic_menu_call,
            category = Category.COMMUNICATION,
            example = "/call John Doe",
            trigger = "call"
        ),

        // 3. WHATSAPP - single entry, both "whatsapp" and "message" resolve here
        Command(
            id = "whatsapp_contact",
            name = "WhatsApp contact",
            syntax = "/whatsapp <contact> <message>",
            description = "Send WhatsApp message to a contact",
            iconRes = android.R.drawable.ic_menu_send,
            category = Category.COMMUNICATION,
            example = "/whatsapp John Hello!",
            trigger = "whatsapp"
        ),

        // 4. SEARCH - single entry, aliases: search, google, look up, find
        Command(
            id = "search_web",
            name = "Search web",
            syntax = "/search <query>",
            description = "Search the web via Google",
            iconRes = android.R.drawable.ic_menu_search,
            category = Category.SEARCH,
            example = "/search weather today",
            trigger = "search"
        ),

        // 5. HELP - shows available commands and usage
        Command(
            id = "help",
            name = "Help",
            syntax = "/help [command]",
            description = "Show help for all commands or a specific command",
            iconRes = android.R.drawable.ic_menu_help,
            category = Category.SYSTEM,
            example = "/help open",
            trigger = "help"
        )
    )

    /** All registered commands (exactly 5). */
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

    /**
     * Get help text for a specific command, or general help if null.
     */
    fun getHelp(commandTrigger: String?): String {
        return when (commandTrigger?.lowercase()) {
            "open" -> "Open app\n\nPurpose: Launch an installed application\nSyntax: /open <app name>\nExample: /open chrome"
            "call" -> "Call contact\n\nPurpose: Call a contact from your phonebook\nSyntax: /call <contact name>\nExample: /call John Doe"
            "whatsapp" -> "WhatsApp contact\n\nPurpose: Send WhatsApp message to a contact\nSyntax: /whatsapp <contact> <message>\nExample: /whatsapp John Hello!"
            "search" -> "Search web\n\nPurpose: Search the web via Google\nSyntax: /search <query>\nExample: /search weather today"
            "help" -> "Help\n\nPurpose: Show help for all commands or a specific command\nSyntax: /help [command]\nExample: /help open"
            null -> {
                val sb = StringBuilder("Available commands:\n\n")
                allCommands.forEach { cmd ->
                    sb.append("${cmd.syntax}\n")
                    sb.append("  ${cmd.description}\n")
                    sb.append("  Example: ${cmd.example}\n\n")
                }
                sb.append("Type /help <command> for more details (e.g., /help open)")
                sb.toString()
            }
            else -> "Unknown command: '$commandTrigger'\n\nAvailable commands: ${allCommands.joinToString(", ") { it.trigger }}.\nType /help for all commands."
        }
    }
}