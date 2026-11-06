package com.buddyv2.app

import android.content.ActivityNotFoundException
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.ContactsContract
import android.provider.Settings
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.util.Log
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.RecyclerView
import com.buddyv2.app.databinding.ActivityMainBinding
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var pendingCallNumber: String? = null
    private var pendingWhatsAppContact: String? = null
    private var pendingWhatsAppMessage: String? = null

    private val permissions = arrayOf(
        android.Manifest.permission.CALL_PHONE,
        android.Manifest.permission.SYSTEM_ALERT_WINDOW,
        android.Manifest.permission.READ_CONTACTS
    )

    companion object {
        private const val TAG = "BuddyV2Text"
    }

    private val PERMISSION_REQUEST_CODE = 100
    private val OVERLAY_PERMISSION_REQUEST_CODE = 101
    private val CALL_PERMISSION_REQUEST_CODE = 102
    private val CONTACTS_PERMISSION_REQUEST_CODE = 103

    // Autocomplete popup
    private var autocompletePopup: CommandAutocompletePopup? = null
    private var isSlashTriggerActive = false
    private var slashStartPosition = -1
    private var slashCursorPosition = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Setup WindowInsets handling for keyboard (IME) positioning
        setupWindowInsets()

        setupTextInput()
        setupClickListeners()
        checkPermissions()
    }

    private fun setupWindowInsets() {
        val rootView = binding.root
        val inputContainer = binding.inputContainer

        // Set up listener for IME (keyboard) insets changes
        ViewCompat.setOnApplyWindowInsetsListener(rootView) { view, windowInsets ->
            val imeInsets = windowInsets.getInsets(WindowInsetsCompat.Type.ime())
            val systemBarsInsets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            val navBarsInsets = windowInsets.getInsets(WindowInsetsCompat.Type.navigationBars())

            // Apply bottom padding to input container to keep it above keyboard/nav bar
            val bottomInset = maxOf(imeInsets.bottom, navBarsInsets.bottom)
            inputContainer.setPadding(
                inputContainer.paddingLeft,
                inputContainer.paddingTop,
                inputContainer.paddingRight,
                bottomInset
            )

            // Also update the root view padding to handle system bars
            view.setPadding(
                view.paddingLeft,
                view.paddingTop,
                view.paddingRight,
                maxOf(view.paddingBottom, systemBarsInsets.bottom)
            )

            // Request layout to apply changes
            inputContainer.requestLayout()
            view.requestLayout()

            windowInsets
        }
    }

    private fun setupTextInput() {
        // Set initial status
        binding.statusText.text = getString(R.string.enter_command)
        binding.commandText.visibility = View.GONE

        // Set up text change listener on the input for / autocomplete
        binding.commandInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                handleSlashTrigger(s, start, before, count)
            }

            override fun afterTextChanged(s: Editable?) {
                // Enable send button when there's text
                binding.sendButton.isEnabled = s?.trim()?.isNotEmpty() == true
                binding.sendButton.alpha = if (binding.sendButton.isEnabled) 1f else 0.5f
            }
        })

        // Initially disable send button
        binding.sendButton.isEnabled = false
        binding.sendButton.alpha = 0.5f

        // Handle enter key on keyboard
        binding.commandInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                submitCommand()
                true
            } else {
                false
            }
        }

        // Handle keyboard navigation for autocomplete (Up/Down/Enter/Back)
        binding.commandInput.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false

            when (keyCode) {
                KeyEvent.KEYCODE_BACK -> {
                    if (autocompletePopup?.isShowing == true) {
                        autocompletePopup?.dismiss()
                        return@setOnKeyListener true
                    }
                }
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (autocompletePopup?.isShowing == true) {
                        val direction = if (keyCode == KeyEvent.KEYCODE_DPAD_UP) -1 else 1
                        autocompletePopup?.moveSelection(direction)
                        return@setOnKeyListener true
                    }
                }
                KeyEvent.KEYCODE_ENTER -> {
                    if (autocompletePopup?.isShowing == true) {
                        autocompletePopup?.selectCurrent()
                        return@setOnKeyListener true
                    }
                }
            }
            false
        }
    }

    private fun handleSlashTrigger(text: CharSequence?, start: Int, before: Int, count: Int) {
        val input = text.toString()

        // Check if we're in a / trigger
        if (!isSlashTriggerActive) {
            // Look for / that was just typed (count == 1 means one char added)
            if (count == 1 && start < input.length && input[start] == '/') {
                // Check if / is at start or preceded by space
                if (start == 0 || input[start - 1].isWhitespace()) {
                    activateSlashTrigger(start, start + 1, "")
                }
            }
        } else {
            // We're in a / trigger, check if we should update or dismiss
            val slashIndex = slashStartPosition
            val cursorPos = binding.commandInput.selectionStart

            // If / was deleted or we moved before it, dismiss
            if (slashIndex >= input.length || input[slashIndex] != '/' || cursorPos <= slashIndex) {
                deactivateSlashTrigger()
                return
            }

            // Extract query after /
            val queryStart = slashIndex + 1
            val query = if (cursorPos > queryStart) {
                input.substring(queryStart, cursorPos.coerceAtMost(input.length))
            } else {
                ""
            }

            // Check for space - if user types space after /, could be end of trigger
            // But we allow spaces in multi-word commands like "open chrome"
            // Dismiss on non-word characters that aren't space (like punctuation)
            if (cursorPos > queryStart && cursorPos <= input.length) {
                val lastChar = input[cursorPos - 1]
                if (!lastChar.isLetterOrDigit() && lastChar != ' ' && lastChar != '_' && lastChar != '-') {
                    // Don't dismiss immediately, let the filter handle it
                }
            }

            updateSlashTrigger(query, cursorPos)
        }
    }

    private fun activateSlashTrigger(slashPos: Int, cursorPos: Int, query: String) {
        isSlashTriggerActive = true
        slashStartPosition = slashPos
        slashCursorPosition = cursorPos

        autocompletePopup = CommandAutocompletePopup(
            context = this,
            anchorView = binding.commandInput,
            onCommandSelected = { command, slashPosition, cursorPosition ->
                insertCommandSyntax(command, slashPosition, cursorPosition)
            },
            onDismiss = { deactivateSlashTrigger() }
        )
        autocompletePopup?.show(slashPos, cursorPos, query)
    }

    private fun updateSlashTrigger(query: String, cursorPos: Int) {
        slashCursorPosition = cursorPos
        autocompletePopup?.updateQuery(query)
    }

    private fun deactivateSlashTrigger() {
        isSlashTriggerActive = false
        slashStartPosition = -1
        slashCursorPosition = -1
        autocompletePopup?.dismiss()
        autocompletePopup = null
    }

    private fun insertCommandSyntax(command: CommandRegistry.Command, slashPosition: Int, cursorPosition: Int) {
        val input = binding.commandInput
        val currentText = input.text.toString()

        // Calculate the range to replace: from / to cursor
        val replaceStart = slashPosition
        val replaceEnd = cursorPosition.coerceAtMost(currentText.length)

        // Insert only the command trigger with / prefix (e.g., "/search")
        // The user will then type their query after the command
        val afterText = if (replaceEnd < currentText.length) {
            currentText.substring(replaceEnd)
        } else {
            ""
        }

        val trigger = "/" + command.trigger
        val newText = currentText.substring(0, replaceStart) + trigger + " " + afterText
        input.setText(newText)

        // Position cursor after the inserted trigger
        val newCursorPos = replaceStart + trigger.length + 1
        input.setSelection(newCursorPos.coerceAtMost(newText.length))

        deactivateSlashTrigger()
    }

    private fun setupClickListeners() {
        binding.sendButton.setOnClickListener { submitCommand() }
    }

    private fun checkPermissions() {
        val missingPermissions = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missingPermissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missingPermissions.toTypedArray(), PERMISSION_REQUEST_CODE)
        }

        // Check overlay permission for Android 23+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            startActivityForResult(intent, OVERLAY_PERMISSION_REQUEST_CODE)
        }
    }

    private fun submitCommand() {
        val input = binding.commandInput.text.toString()
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return

        // Dismiss autocomplete if showing
        deactivateSlashTrigger()

        // Clear input
        binding.commandInput.setText("")

        // Show feedback
        binding.commandText.text = "Sent: $trimmed"
        binding.commandText.visibility = View.VISIBLE

        speak("Processing: $trimmed")
        processTextCommand(trimmed)
    }

    private fun processTextCommand(command: String) {
        val trimmed = command.trim()
        val lowerCommand = trimmed.lowercase(Locale.getDefault())

        // Check if command starts with / (slash command)
        if (trimmed.startsWith("/")) {
            processSlashCommand(trimmed)
        } else {
            // Backward compatibility: process plain-text commands (without /)
            processPlainCommand(lowerCommand)
        }
    }

    private fun processSlashCommand(command: String) {
        // Remove leading / and split into trigger and args
        val withoutSlash = command.substring(1).trim()
        val parts = withoutSlash.split(" ", limit = 2)
        val trigger = parts[0].lowercase(Locale.getDefault())
        val args = if (parts.size > 1) parts[1] else ""

        when (trigger) {
            "open" -> extractAppName(args)?.let { openApp(it) }
            "call" -> extractContactName(args)?.let { resolveAndCall(it) }
            "whatsapp" -> extractContactAndMessage(args)?.let { (contact, message) -> resolveAndSendWhatsApp(contact, message) }
            "search" -> extractSearchQuery(args)?.let { searchWeb(it) }
            "help" -> showHelp(args)
            else -> {
                val helpText = CommandRegistry.getHelp(trigger)
                speak(helpText)
                Toast.makeText(this, helpText, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun processPlainCommand(lowerCommand: String) {
        // Backward compatibility: handle plain-text commands without /
        when {
            lowerCommand.contains("open") -> extractAppName(lowerCommand)?.let { openApp(it) }
            lowerCommand.contains("call") -> extractContactName(lowerCommand)?.let { resolveAndCall(it) }
            lowerCommand.contains("whatsapp") || lowerCommand.contains("message") -> {
                extractContactAndMessage(lowerCommand)?.let { (contact, message) -> resolveAndSendWhatsApp(contact, message) }
            }
            // Check for search commands - handle all aliases: search, google, look up, find
            lowerCommand.contains("search") || lowerCommand.contains("google") || lowerCommand.contains("look up") || lowerCommand.contains("find") -> {
                extractSearchQuery(lowerCommand)?.let { searchWeb(it) }
            }
            else -> {
                speak("Command not recognized. Try: /open app name, /call contact name, /whatsapp contact message, /search query, or /help")
                Toast.makeText(this, "Command not recognized. Type /help for available commands.", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun showHelp(topic: String) {
        val helpText = if (topic.isBlank()) {
            CommandRegistry.getHelp(null)
        } else {
            CommandRegistry.getHelp(topic.trim())
        }
        speak(helpText)
        Toast.makeText(this, helpText, Toast.LENGTH_LONG).show()
    }

    private fun extractAppName(command: String): String? {
        val patterns = listOf(
            "open ".toRegex(),
            "launch ".toRegex(),
            "start ".toRegex()
        )
        for (pattern in patterns) {
            val match = pattern.find(command)
            if (match != null) {
                val after = command.substring(match.range.last + 1).trim()
                if (after.isNotEmpty()) return after
            }
        }
        // Also try if the command is just the app name (e.g., "chrome")
        val trimmed = command.trim()
        if (trimmed.isNotEmpty() && !trimmed.contains(" ")) {
            return trimmed
        }
        return null
    }

    private fun extractContactName(command: String): String? {
        val patterns = listOf(
            "call ".toRegex(),
            "dial ".toRegex(),
            "phone ".toRegex()
        )
        for (pattern in patterns) {
            val match = pattern.find(command)
            if (match != null) {
                val after = command.substring(match.range.last + 1).trim()
                if (after.isNotEmpty()) return after
            }
        }
        // Also try if the command is just the contact name
        val trimmed = command.trim()
        if (trimmed.isNotEmpty() && !trimmed.contains(" ")) {
            return trimmed
        }
        return null
    }

    private fun extractPhoneNumber(command: String): String? {
        val patterns = listOf(
            "call ".toRegex(),
            "dial ".toRegex(),
            "phone ".toRegex()
        )
        for (pattern in patterns) {
            val match = pattern.find(command)
            if (match != null) {
                val after = command.substring(match.range.last + 1).trim()
                val phoneNumber = after.filter { it.isDigit() || it in "+*#" }
                if (phoneNumber.isNotEmpty()) return phoneNumber
            }
        }
        return null
    }

    private fun extractContactAndMessage(command: String): Pair<String, String>? {
        val whatsappMatch = "whatsapp\\s+(.+?)\\s*(?:,|\\s+)(?:tell\\s+(?:him|her|them)\\s+)?(.+)".toRegex().find(command)
        val messageMatch = "message\\s+(.+?)\\s*(?:,|\\s+)(?:tell\\s+(?:him|her|them)\\s+)?(.+)".toRegex().find(command)
        val simpleWhatsAppMatch = "whatsapp\\s+(.+?)\\s+(.+)".toRegex().find(command)
        val simpleMessageMatch = "message\\s+(.+?)\\s+(.+)".toRegex().find(command)

        return whatsappMatch?.let { Pair(it.groupValues[1], it.groupValues[2]) }
            ?: messageMatch?.let { Pair(it.groupValues[1], it.groupValues[2]) }
            ?: simpleWhatsAppMatch?.let { Pair(it.groupValues[1], it.groupValues[2]) }
            ?: simpleMessageMatch?.let { Pair(it.groupValues[1], it.groupValues[2]) }
    }

    private fun extractSearchQuery(command: String): String? {
        val patterns = listOf(
            "search\\s+for\\s+(.+)".toRegex(),
            "search\\s+(.+)".toRegex(),
            "google\\s+(.+)".toRegex(),
            "look\\s+up\\s+(.+)".toRegex(),
            "find\\s+(.+)".toRegex()
        )
        for (pattern in patterns) {
            val match = pattern.find(command)
            if (match != null) {
                return match.groupValues[1].trim()
            }
        }
        // Also try if the command is just the query
        val trimmed = command.trim()
        if (trimmed.isNotEmpty()) {
            return trimmed
        }
        return null
    }

    private fun openApp(appName: String) {
        val packageManager = packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val activities = packageManager.queryIntentActivities(intent, 0)

        val targetActivity = activities.find { resolveInfo ->
            resolveInfo.loadLabel(packageManager).toString().lowercase(Locale.getDefault())
                .contains(appName.lowercase(Locale.getDefault()))
        }

        targetActivity?.let {
            val launchIntent = packageManager.getLaunchIntentForPackage(it.activityInfo.packageName)
            launchIntent?.let { startActivity(it) }
            speak("Opening ${it.loadLabel(packageManager)}")
        } ?: run {
            speak("App '$appName' not found")
            Toast.makeText(this, "App '$appName' not found", Toast.LENGTH_LONG).show()
        }
    }

    // ===== CONTACT RESOLUTION =====

    private fun resolveAndCall(contactName: String) {
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            pendingCallNumber = null
            ActivityCompat.requestPermissions(this, arrayOf(android.Manifest.permission.READ_CONTACTS), CONTACTS_PERMISSION_REQUEST_CODE)
            speak("Need contacts permission to find $contactName")
            return
        }

        val phoneNumber = findContactPhoneNumber(contactName)
        when {
            phoneNumber == null -> {
                speak("Contact '$contactName' not found")
                Toast.makeText(this, getString(R.string.contact_not_found, contactName), Toast.LENGTH_LONG).show()
            }
            phoneNumber == "AMBIGUOUS" -> {
                speak("Multiple contacts match '$contactName'. Please be more specific.")
                Toast.makeText(this, getString(R.string.multiple_contacts_found, contactName), Toast.LENGTH_LONG).show()
            }
            else -> makeCall(phoneNumber)
        }
    }

    private fun resolveAndSendWhatsApp(contactName: String, message: String) {
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            pendingWhatsAppContact = contactName
            pendingWhatsAppMessage = message
            ActivityCompat.requestPermissions(this, arrayOf(android.Manifest.permission.READ_CONTACTS), CONTACTS_PERMISSION_REQUEST_CODE)
            speak("Need contacts permission to find $contactName")
            return
        }

        val phoneNumber = findContactPhoneNumber(contactName)
        when {
            phoneNumber == null -> {
                speak("Contact '$contactName' not found")
                Toast.makeText(this, getString(R.string.contact_not_found, contactName), Toast.LENGTH_LONG).show()
            }
            phoneNumber == "AMBIGUOUS" -> {
                speak("Multiple contacts match '$contactName'. Please be more specific.")
                Toast.makeText(this, getString(R.string.multiple_contacts_found, contactName), Toast.LENGTH_LONG).show()
            }
            else -> sendWhatsApp(phoneNumber, message)
        }
    }

    /**
     * Finds a contact's phone number by display name.
     * Returns: phone number string, "AMBIGUOUS" if multiple matches, null if not found.
     */
    private fun findContactPhoneNumber(contactName: String): String? {
        val contentResolver: ContentResolver = contentResolver
        val normalizedQuery = contactName.lowercase(Locale.getDefault()).trim()

        // Query contacts by display name (case-insensitive partial match)
        val projection = arrayOf(
            ContactsContract.Contacts._ID,
            ContactsContract.Contacts.DISPLAY_NAME,
            ContactsContract.Contacts.HAS_PHONE_NUMBER
        )
        val selection = "${ContactsContract.Contacts.DISPLAY_NAME} LIKE ?"
        val selectionArgs = arrayOf("%$contactName%")

        val cursor = contentResolver.query(
            ContactsContract.Contacts.CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            null
        )

        val matchingContacts = mutableListOf<Pair<String, String>>() // (contactId, displayName)

        cursor?.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(c.getColumnIndexOrThrow(ContactsContract.Contacts._ID))
                val name = c.getString(c.getColumnIndexOrThrow(ContactsContract.Contacts.DISPLAY_NAME))
                val hasPhone = c.getString(c.getColumnIndexOrThrow(ContactsContract.Contacts.HAS_PHONE_NUMBER)) == "1"
                if (hasPhone) {
                    matchingContacts.add(id to name)
                }
            }
        }

        // Filter for best matches (exact > prefix > contains)
        val exactMatches = matchingContacts.filter { it.second.lowercase(Locale.getDefault()) == normalizedQuery }
        val prefixMatches = matchingContacts.filter { it.second.lowercase(Locale.getDefault()).startsWith(normalizedQuery) }
        val containsMatches = matchingContacts.filter { normalizedQuery in it.second.lowercase(Locale.getDefault()) }

        val bestMatches = if (exactMatches.isNotEmpty()) exactMatches
        else if (prefixMatches.isNotEmpty()) prefixMatches
        else containsMatches

        return when {
            bestMatches.isEmpty() -> null
            bestMatches.size > 1 -> "AMBIGUOUS"
            else -> getPhoneNumberForContact(bestMatches[0].first)
        }
    }

    private fun getPhoneNumberForContact(contactId: String): String? {
        val contentResolver: ContentResolver = contentResolver
        val projection = arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER)
        val selection = "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?"
        val selectionArgs = arrayOf(contactId)

        val cursor = contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            null
        )

        return cursor?.use { c ->
            if (c.moveToFirst()) {
                val number = c.getString(c.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER))
                normalizePhoneNumber(number)
            } else null
        }
    }

    /**
     * Normalizes phone number to include country code format for WhatsApp.
     * Keeps +, digits only. Ensures it starts with + for wa.me links.
     */
    private fun normalizePhoneNumber(rawNumber: String): String {
        val digitsOnly = rawNumber.filter { it.isDigit() || it == '+' }
        return if (digitsOnly.startsWith("+")) digitsOnly else "+$digitsOnly"
    }

    // ===== ACTION EXECUTION =====

    private fun makeCall(phoneNumber: String) {
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) {
            val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$phoneNumber"))
            try {
                startActivity(intent)
                speak("Calling $phoneNumber")
            } catch (e: ActivityNotFoundException) {
                speak("Cannot make calls on this device")
                Toast.makeText(this, "Cannot make calls", Toast.LENGTH_SHORT).show()
            }
        } else {
            pendingCallNumber = phoneNumber
            ActivityCompat.requestPermissions(this, arrayOf(android.Manifest.permission.CALL_PHONE), CALL_PERMISSION_REQUEST_CODE)
        }
    }

    private fun sendWhatsApp(phoneNumber: String, message: String) {
        val packageManager = packageManager
        val whatsappPackage = if (packageManager.getLaunchIntentForPackage("com.whatsapp") != null) {
            "com.whatsapp"
        } else if (packageManager.getLaunchIntentForPackage("com.whatsapp.w4b") != null) {
            "com.whatsapp.w4b"
        } else {
            speak("WhatsApp not installed")
            Toast.makeText(this, R.string.whatsapp_not_installed, Toast.LENGTH_SHORT).show()
            return
        }

        // Use wa.me link with phone number (including country code) and prefilled message
        val encodedMessage = Uri.encode(message)
        val cleanNumber = phoneNumber.replace("+", "").replace(" ", "").replace("-", "")
        val url = "https://wa.me/$cleanNumber?text=$encodedMessage"
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            setPackage(whatsappPackage)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        try {
            startActivity(intent)
            speak("Opening WhatsApp for $phoneNumber")
        } catch (e: ActivityNotFoundException) {
            speak("Could not open WhatsApp")
            Toast.makeText(this, "Could not open WhatsApp", Toast.LENGTH_SHORT).show()
        }
    }

    private fun searchWeb(query: String) {
        val encodedQuery = Uri.encode(query)
        val url = "https://www.google.com/search?q=$encodedQuery"
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        try {
            startActivity(intent)
            speak("Searching for $query")
        } catch (e: ActivityNotFoundException) {
            speak("No browser available")
            Toast.makeText(this, "No browser available", Toast.LENGTH_SHORT).show()
        }
    }

    // ===== PERMISSION HANDLING =====

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            CALL_PERMISSION_REQUEST_CODE -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    pendingCallNumber?.let { makeCall(it) }
                    pendingCallNumber = null
                } else {
                    speak("Call permission denied")
                    Toast.makeText(this, R.string.call_permission_denied, Toast.LENGTH_SHORT).show()
                }
            }
            CONTACTS_PERMISSION_REQUEST_CODE -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    // Retry pending operations
                    pendingWhatsAppContact?.let { contact ->
                        pendingWhatsAppMessage?.let { message ->
                            resolveAndSendWhatsApp(contact, message)
                        }
                    }
                    pendingWhatsAppContact = null
                    pendingWhatsAppMessage = null
                } else {
                    speak("Contacts permission denied")
                    Toast.makeText(this, R.string.permission_contacts_denied, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun speak(text: String) {
        // TextToSpeech removed - using Toast for feedback
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        autocompletePopup?.dismiss()
        super.onDestroy()
    }

    override fun onBackPressed() {
        if (autocompletePopup?.isShowing == true) {
            autocompletePopup?.dismiss()
        } else {
            super.onBackPressed()
        }
    }
}