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
     * Command parameter description.
     */
    data class Parameter(
        val name: String,
        val description: String
    )

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
     * @property parameters List of parameters with descriptions
     */
    data class Command(
        val id: String,
        val name: String,
        val syntax: String,
        val description: String,
        val iconRes: Int,
        val category: Category,
        val example: String,
        val trigger: String = id,
        val parameters: List<Parameter> = emptyList()
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
            trigger = "open",
            parameters = listOf(
                Parameter("App name", "Name of the installed application to launch (e.g., Chrome, WhatsApp, Settings)")
            )
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
            trigger = "call",
            parameters = listOf(
                Parameter("Contact name", "Saved contact's name as it appears in your phonebook (e.g., John, Jane Smith)")
            )
        ),

        // 3. WHATSAPP - single entry, both "whatsapp" and "message" resolve here
        Command(
            id = "whatsapp_contact",
            name = "WhatsApp",
            syntax = "/whatsapp <contact> <message>",
            description = "Send WhatsApp message to a contact",
            iconRes = android.R.drawable.ic_menu_send,
            category = Category.COMMUNICATION,
            example = "/whatsapp Abhishek Sharma Hello, how are you?",
            trigger = "whatsapp",
            parameters = listOf(
                Parameter("Contact", "Saved contact's name (supports multi-word names, e.g., Abhishek Sharma, John Smith)"),
                Parameter("Message", "Text message to send (can contain spaces, punctuation, emoji)")
            )
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
            trigger = "search",
            parameters = listOf(
                Parameter("Query", "Search terms (e.g., weather today, nearby restaurants, how to bake bread)")
            )
        ),

        // 5. HELP - shows available commands and usage
        Command(
            id = "help",
            name = "Help",
            syntax = "/help [command]",
            description = "Show help for all commands or a specific command",
            iconRes = android.R.drawable.ic_menu_help,
            category = Category.SYSTEM,
            example = "/help whatsapp",
            trigger = "help",
            parameters = listOf(
                Parameter("Command (optional)", "Command name to get detailed help for (e.g., open, call, whatsapp, search)")
            )
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
            "open" -> formatCommandHelp(allCommands.find { it.trigger == "open" }!!)
            "call" -> formatCommandHelp(allCommands.find { it.trigger == "call" }!!)
            "whatsapp" -> formatCommandHelp(allCommands.find { it.trigger == "whatsapp" }!!)
            "search" -> formatCommandHelp(allCommands.find { it.trigger == "search" }!!)
            "help" -> formatCommandHelp(allCommands.find { it.trigger == "help" }!!)
            null -> formatGeneralHelp()
            else -> "Unknown command: '$commandTrigger'\n\nAvailable commands: ${allCommands.joinToString(", ") { it.trigger }}.\nType /help for all commands."
        }
    }

    private fun formatCommandHelp(cmd: Command): String {
        val sb = StringBuilder()
        sb.append("Command: ${cmd.name}\n")
        sb.append("Syntax: ${cmd.syntax}\n")
        sb.append("Example: ${cmd.example}\n")
        sb.append("Description: ${cmd.description}\n")
        if (cmd.parameters.isNotEmpty()) {
            sb.append("Parameters:\n")
            cmd.parameters.forEach { param ->
                sb.append("- ${param.name}: ${param.description}\n")
            }
        }
        return sb.toString()
    }

    private fun formatGeneralHelp(): String {
        val sb = StringBuilder("Available commands:\n\n")
        allCommands.forEach { cmd ->
            sb.append("Command: ${cmd.name}\n")
            sb.append("Syntax: ${cmd.syntax}\n")
            sb.append("Example: ${cmd.example}\n")
            sb.append("Description: ${cmd.description}\n")
            if (cmd.parameters.isNotEmpty()) {
                sb.append("Parameters:\n")
                cmd.parameters.forEach { param ->
                    sb.append("  - ${param.name}: ${param.description}\n")
                }
            }
            sb.append("\n")
        }
        sb.append("Type /help <command> for more details (e.g., /help whatsapp)")
        return sb.toString()
    }
}