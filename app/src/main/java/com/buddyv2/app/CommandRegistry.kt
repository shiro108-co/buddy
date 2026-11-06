package com.buddyv2.app

/**
 * Registry of all available commands in Buddy V2.
 * This replaces hardcoded suggestion lists with a single source of truth.
 */
object CommandRegistry {

    data class Command(
        val id: String,
        val name: String,
        val syntax: String,
        val description: String,
        val iconRes: Int,
        val category: Category,
        val example: String
    ) {
        val trigger: String = syntax.split(" ")[0]
    }

    enum class Category(val label: String, val order: Int) {
        APPS("Apps", 1),
        COMMUNICATION("Communication", 2),
        SEARCH("Search", 3),
        SYSTEM("System", 4)
    }

    private val commands = listOf(
        // Apps
        Command(
            id = "open_app",
            name = "Open App",
            syntax = "open <app name>",
            description = "Launch an installed application",
            iconRes = android.R.drawable.ic_menu_add,
            category = Category.APPS,
            example = "open chrome"
        ),
        Command(
            id = "launch_app",
            name = "Launch App",
            syntax = "launch <app name>",
            description = "Launch an installed application (alias)",
            iconRes = android.R.drawable.ic_menu_add,
            category = Category.APPS,
            example = "launch camera"
        ),

        // Communication - Calls
        Command(
            id = "call_contact",
            name = "Call Contact",
            syntax = "call <contact name>",
            description = "Make a phone call to a contact",
            iconRes = android.R.drawable.ic_menu_call,
            category = Category.COMMUNICATION,
            example = "call mom"
        ),
        Command(
            id = "dial_contact",
            name = "Dial Contact",
            syntax = "dial <contact name>",
            description = "Dial a contact (alias for call)",
            iconRes = android.R.drawable.ic_menu_call,
            category = Category.COMMUNICATION,
            example = "dial dad"
        ),
        Command(
            id = "call_number",
            name = "Call Number",
            syntax = "call <phone number>",
            description = "Call a phone number directly",
            iconRes = android.R.drawable.ic_menu_call,
            category = Category.COMMUNICATION,
            example = "call +15551234567"
        ),

        // Communication - WhatsApp
        Command(
            id = "whatsapp_contact",
            name = "WhatsApp Contact",
            syntax = "whatsapp <contact> <message>",
            description = "Send WhatsApp message to a contact",
            iconRes = android.R.drawable.ic_menu_send,
            category = Category.COMMUNICATION,
            example = "whatsapp john hello there"
        ),
        Command(
            id = "message_contact",
            name = "Message Contact",
            syntax = "message <contact> <message>",
            description = "Send WhatsApp message (alias)",
            iconRes = android.R.drawable.ic_menu_send,
            category = Category.COMMUNICATION,
            example = "message sarah meeting at 3"
        ),

        // Search
        Command(
            id = "search_web",
            name = "Search Web",
            syntax = "search <query>",
            description = "Search the web via Google",
            iconRes = android.R.drawable.ic_menu_search,
            category = Category.SEARCH,
            example = "search weather today"
        ),
        Command(
            id = "google_search",
            name = "Google Search",
            syntax = "google <query>",
            description = "Search the web via Google (alias)",
            iconRes = android.R.drawable.ic_menu_search,
            category = Category.SEARCH,
            example = "google android tips"
        ),
        Command(
            id = "lookup_search",
            name = "Look Up",
            syntax = "look up <query>",
            description = "Search the web (alias)",
            iconRes = android.R.drawable.ic_menu_search,
            category = Category.SEARCH,
            example = "look up pizza near me"
        ),
        Command(
            id = "find_search",
            name = "Find",
            syntax = "find <query>",
            description = "Search the web (alias)",
            iconRes = android.R.drawable.ic_menu_search,
            category = Category.SEARCH,
            example = "find best restaurants"
        )
    )

    val allCommands: List<Command> = commands

    fun getCommandsByCategory(): Map<Category, List<Command>> {
        return commands.groupBy { it.category }
    }

    fun filterCommands(query: String): List<Command> {
        if (query.isBlank()) return commands
        val lowerQuery = query.lowercase()
        return commands.filter { cmd ->
            cmd.name.lowercase().contains(lowerQuery) ||
            cmd.syntax.lowercase().contains(lowerQuery) ||
            cmd.description.lowercase().contains(lowerQuery) ||
            cmd.trigger.lowercase().contains(lowerQuery) ||
            cmd.example.lowercase().contains(lowerQuery)
        }
    }

    fun getCommandById(id: String): Command? {
        return commands.find { it.id == id }
    }

    fun getCommandByTrigger(trigger: String): Command? {
        return commands.find { it.trigger.equals(trigger, ignoreCase = true) }
    }
}