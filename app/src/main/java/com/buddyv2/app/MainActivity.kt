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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleObserver
import androidx.lifecycle.OnLifecycleEvent
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
    private val MIC_PERMISSION_REQUEST_CODE = 200

    // Autocomplete popup
    private var autocompletePopup: CommandAutocompletePopup? = null
    private var isSlashTriggerActive = false
    private var slashStartPosition = -1
    private var slashCursorPosition = -1

    // Voice components
    private lateinit var conversationContext: ConversationContext
    private lateinit var intentParser: IntentParser
    private lateinit var voiceUIController: VoiceUIController
    private lateinit var voiceController: VoiceController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Setup WindowInsets handling for keyboard (IME) positioning
        setupWindowInsets()

        // Initialize voice components
        initializeVoice()

        setupTextInput()
        setupClickListeners()
        checkPermissions()
    }

    private fun initializeVoice() {
        conversationContext = ConversationContext()
        intentParser = IntentParser(conversationContext)
        voiceUIController = VoiceUIController(
            context = this,
            micButton = binding.micButton,
            statusText = binding.statusText,
            commandText = binding.commandText,
            commandInput = binding.commandInput
        )

        voiceController = VoiceController(
            activity = this,
            uiController = voiceUIController,
            intentParser = intentParser,
            onIntentParsed = { intent -> handleVoiceIntent(intent) },
            onTextRecognized = { text -> /* partial results shown in UI */ },
            onResultReady = { message -> onVoiceResult(message) },
            onError = { error -> onVoiceError(error) }
        )

        // Register lifecycle observer for voice controller
        lifecycle.addObserver(voiceController)

        // Set up mic button click listener
        binding.micButton.setOnClickListener { voiceController.onMicButtonClick() }
    }

    private fun handleVoiceIntent(intent: IntentParser.Intent) {
        // Mark that a voice action is pending so result will be reported to voice controller
        voiceController.markVoiceActionPending()

        when (intent) {
            is IntentParser.Intent.OpenApp -> {
                conversationContext.setLastAction("open")
                extractAppName(intent.appName)?.let { openApp(it) }
            }
            is IntentParser.Intent.CallContact -> {
                conversationContext.setLastAction("call")
                extractContactName(intent.contactName)?.let { resolveAndCall(it) }
            }
            is IntentParser.Intent.WhatsAppMessage -> {
                conversationContext.setLastAction("whatsapp")
                // Use contact-aware parsing for the full remaining text
                extractContactAndMessage(intent.contactName)?.let { (contact, message) ->
                    resolveAndSendWhatsApp(contact, message)
                }
            }
            is IntentParser.Intent.SearchWeb -> {
                conversationContext.setLastAction("search")
                extractSearchQuery(intent.query)?.let { searchWeb(it) }
            }
            is IntentParser.Intent.Help -> {
                showHelp(intent.topic ?: "")
            }
            IntentParser.Intent.StopSpeaking -> {
                voiceController.stopSpeaking()
            }
            IntentParser.Intent.RepeatLast -> {
                // Could implement repeat last response
                onVoiceError("Repeat not yet implemented")
            }
            is IntentParser.Intent.Unknown -> {
                // Treat unknown as search query
                extractSearchQuery(intent.originalText)?.let { searchWeb(it) }
            }
        }
    }

    private fun onVoiceResult(message: String) {
        // Add to conversation context
        // Note: We need the original recognized text for context
        // For now, we'll add the result message
        conversationContext.addExchange("", message)
        showResult(message)
    }

    private fun onVoiceError(error: String) {
        showError(error)
        // Return to idle state after error
        voiceUIController.setState(VoiceUIController.VoiceState.IDLE)
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
        binding.statusText.text = getString(R.string.tap_mic_to_speak)
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

        // Show "Sent:" feedback in commandText
        binding.commandText.text = "Sent: $trimmed"
        binding.commandText.visibility = View.VISIBLE

        // Process command (no temporary "Processing..." message)
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
                showResult(helpText)
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
                val msg = "Command not recognized. Try: /open app name, /call contact name, /whatsapp contact message, /search query, or /help"
                showResult(msg)
            }
        }
    }

    private fun showHelp(topic: String) {
        val helpText = if (topic.isBlank()) {
            CommandRegistry.getHelp(null)
        } else {
            CommandRegistry.getHelp(topic.trim())
        }
        // Display help as conversation response under "Sent: /help" message
        showResult(helpText)
    }

    private fun showResult(message: String) {
        // Display result centered in commandText (replaces temporary status)
        binding.commandText.text = message
        binding.commandText.visibility = View.VISIBLE
        // No Toast - result is shown in the conversation area
        // Report to voice controller if a voice action is pending
        voiceController.reportVoiceResult(message)
    }

    private fun showError(message: String) {
        // Display error centered in commandText
        binding.commandText.text = message
        binding.commandText.visibility = View.VISIBLE
        // Also show as Toast for immediate visibility of errors
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        // Report to voice controller if a voice action is pending
        voiceController.reportVoiceResult(message)
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

    /**
     * Extracts contact name and message from a WhatsApp command using contact-aware parsing.
     * Matches the longest valid contact name from the start of the input against saved contacts.
     * Everything after the matched contact name becomes the message.
     * Returns null if no contact matches (triggers contact-not-found error upstream).
     */
    private fun extractContactAndMessage(command: String): Pair<String, String>? {
        val input = command.trim()

        // Remove "whatsapp" or "message" prefix if present
        var remaining = input
        if (remaining.lowercase().startsWith("whatsapp ")) {
            remaining = remaining.substring("whatsapp ".length).trim()
        } else if (remaining.lowercase().startsWith("message ")) {
            remaining = remaining.substring("message ".length).trim()
        }

        if (remaining.isBlank()) return null

        // Get all contact names with phone numbers
        val contacts = loadContactsWithPhoneNumbers()
        if (contacts.isEmpty()) return null

        // Normalize input for matching: lowercase, collapse whitespace
        val normalizedInput = normalizeForMatching(remaining)

        // Build normalization map to translate normalized positions back to original
        val normalizationMap = buildNormalizationMap(remaining)

        // Find the longest contact name that matches the start of the input
        var bestMatch: String? = null
        var bestMatchLength = 0

        for (contact in contacts) {
            val normalizedContact = normalizeForMatching(contact)
            if (normalizedInput.startsWith(normalizedContact)) {
                // Check if it's a word boundary (next char is space or end)
                val contactLen = normalizedContact.length
                val isBoundary = contactLen == normalizedInput.length || normalizedInput[contactLen].isWhitespace()
                if (isBoundary && contactLen > bestMatchLength) {
                    bestMatch = contact
                    bestMatchLength = contactLen
                }
            }
        }

        // If we found a match, split into contact and message
        bestMatch?.let { matchedContact ->
            // Find the actual end position in the original (non-normalized) remaining string
            // using the normalization map to handle whitespace differences correctly
            val contactEndPos = findOriginalPositionForNormalizedLength(
                remaining, bestMatchLength, normalizationMap
            )

            val message = if (contactEndPos > 0 && contactEndPos < remaining.length) {
                remaining.substring(contactEndPos).trim()
            } else {
                ""
            }

            // Store for conversation context
            conversationContext.setLastMatchedContact(matchedContact)

            return Pair(matchedContact, message)
        }

        // No contact matched
        return null
    }

    /**
     * Loads all contacts that have phone numbers.
     * Returns list of display names.
     */
    private fun loadContactsWithPhoneNumbers(): List<String> {
        val contentResolver: ContentResolver = contentResolver
        val contacts = mutableListOf<String>()

        val projection = arrayOf(
            ContactsContract.Contacts._ID,
            ContactsContract.Contacts.DISPLAY_NAME,
            ContactsContract.Contacts.HAS_PHONE_NUMBER
        )

        val cursor = contentResolver.query(
            ContactsContract.Contacts.CONTENT_URI,
            projection,
            "${ContactsContract.Contacts.HAS_PHONE_NUMBER} = ?",
            arrayOf("1"),
            null
        )

        cursor?.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(c.getColumnIndexOrThrow(ContactsContract.Contacts.DISPLAY_NAME))
                if (name.isNotBlank()) {
                    contacts.add(name.trim())
                }
            }
        }

        return contacts
    }

    /**
     * Normalizes string for contact matching: lowercase, collapse whitespace, trim.
     */
    private fun normalizeForMatching(input: String): String {
        return input.lowercase(Locale.getDefault())
            .replace("\\s+".toRegex(), " ")
            .trim()
    }

    /**
     * Builds a mapping from normalized string indices to original string indices.
     * Accounts for trim (leading/trailing whitespace removal) and whitespace collapsing.
     * Returns a list where each index in the normalized string maps to the corresponding
     * index in the original string (the position of that character in the original).
     */
    private fun buildNormalizationMap(original: String): List<Int> {
        val map = mutableListOf<Int>()
        var origPos = 0
        var inWhitespace = false

        // Skip leading whitespace (trim)
        while (origPos < original.length && original[origPos].isWhitespace()) {
            origPos++
        }

        while (origPos < original.length) {
            val c = original[origPos]
            if (c.isWhitespace()) {
                if (!inWhitespace) {
                    // First whitespace in a sequence -> maps to a single space in normalized
                    map.add(origPos)
                    inWhitespace = true
                }
                // Consecutive whitespace doesn't add to normalized string
            } else {
                // Non-whitespace character -> maps to itself in normalized
                map.add(origPos)
                inWhitespace = false
            }
            origPos++
        }

        // Handle trailing whitespace trim - if we ended on whitespace, the last entry
        // corresponds to a space that would be trimmed, so remove it
        if (map.isNotEmpty() && inWhitespace) {
            map.removeAt(map.size - 1)
        }

        return map
    }

    /**
     * Finds the position in the original string where the normalized prefix of given length ends.
     * Uses the normalization map to translate normalized position to original position.
     */
    private fun findOriginalPositionForNormalizedLength(
        original: String,
        normalizedLength: Int,
        normalizationMap: List<Int>
    ): Int {
        if (normalizedLength <= 0) return 0
        if (normalizedLength >= normalizationMap.size) return original.length

        // The normalizedLength-th character in normalized (0-indexed) maps to this position
        val origIndex = normalizationMap[normalizedLength - 1]

        // Return position AFTER this character in original
        var pos = origIndex + 1

        // Skip any trailing whitespace in original that was collapsed
        while (pos < original.length && original[pos].isWhitespace()) {
            pos++
        }

        return pos
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
            showResult("Opened ${it.loadLabel(packageManager)}")
        } ?: run {
            showError("App '$appName' not found")
        }
    }

    // ===== CONTACT RESOLUTION =====

    private fun resolveAndCall(contactName: String) {
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            pendingCallNumber = null
            ActivityCompat.requestPermissions(this, arrayOf(android.Manifest.permission.READ_CONTACTS), CONTACTS_PERMISSION_REQUEST_CODE)
            showError("Need contacts permission to find $contactName")
            return
        }

        val phoneNumber = findContactPhoneNumber(contactName)
        when {
            phoneNumber == null -> {
                showError("Contact '$contactName' not found")
            }
            phoneNumber == "AMBIGUOUS" -> {
                showError("Multiple contacts match '$contactName'. Please be more specific.")
            }
            else -> makeCall(phoneNumber)
        }
    }

    private fun resolveAndSendWhatsApp(contactName: String, message: String) {
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            pendingWhatsAppContact = contactName
            pendingWhatsAppMessage = message
            ActivityCompat.requestPermissions(this, arrayOf(android.Manifest.permission.READ_CONTACTS), CONTACTS_PERMISSION_REQUEST_CODE)
            showError("Need contacts permission to find $contactName")
            return
        }

        val phoneNumber = findContactPhoneNumber(contactName)
        when {
            phoneNumber == null -> {
                showError("Contact '$contactName' not found")
            }
            phoneNumber == "AMBIGUOUS" -> {
                showError("Multiple contacts match '$contactName'. Please be more specific.")
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
                showResult("Calling $phoneNumber")
            } catch (e: ActivityNotFoundException) {
                showError("Cannot make calls on this device")
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
            showError("WhatsApp not installed")
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
            showResult("Opening WhatsApp for $phoneNumber")
        } catch (e: ActivityNotFoundException) {
            showError("Could not open WhatsApp")
        }
    }

    private fun searchWeb(query: String) {
        val encodedQuery = Uri.encode(query)
        val url = "https://www.google.com/search?q=$encodedQuery"
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        try {
            startActivity(intent)
            showResult("Searching for $query")
        } catch (e: ActivityNotFoundException) {
            showError("No browser available")
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
                    showError("Call permission denied")
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
                    showError("Contacts permission denied")
                }
            }
            MIC_PERMISSION_REQUEST_CODE -> {
                // Voice controller handles its own permission result
                voiceController.onRequestPermissionsResult(requestCode, grantResults)
            }
        }
    }

    override fun onDestroy() {
        autocompletePopup?.dismiss()
        voiceUIController.cleanup()
        super.onDestroy()
    }

    override fun onBackPressed() {
        if (autocompletePopup?.isShowing == true) {
            autocompletePopup?.dismiss()
        } else if (voiceUIController.getState() != VoiceUIController.VoiceState.IDLE) {
            voiceController.cancelAll()
        } else {
            super.onBackPressed()
        }
    }
}