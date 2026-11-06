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
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.buddyv2.app.databinding.ActivityMainBinding
import java.util.Locale
import java.util.Random

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

    // Command suggestions for the animated suggestion area
    private val commandSuggestions = listOf(
        "open chrome",
        "call mom",
        "whatsapp john hello",
        "search weather today",
        "open camera",
        "call dad",
        "message sarah meeting at 3",
        "search pizza near me",
        "open maps",
        "call work",
        "whatsapp group meeting tomorrow",
        "search android tips"
    )

    private var currentSuggestionIndex = 0
    private var suggestionAnimation: Animation? = null
    private val random = Random()

    // Suggestion carousel adapter
    private lateinit var suggestionAdapter: SuggestionAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupTextInput()
        setupSuggestionCarousel()
        setupClickListeners()
        checkPermissions()
        startSuggestionRotation()
    }

    private fun setupTextInput() {
        // Set initial status
        binding.statusText.text = getString(R.string.enter_command)
        binding.commandText.visibility = View.GONE

        // Set up text change listener on the input
        binding.commandInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
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
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEND) {
                submitCommand()
                true
            } else {
                false
            }
        }
    }

    private fun setupSuggestionCarousel() {
        suggestionAdapter = SuggestionAdapter(commandSuggestions) { suggestion ->
            // When a suggestion is clicked, populate the input field
            binding.commandInput.setText(suggestion)
            binding.commandInput.setSelection(suggestion.length)
        }
        binding.suggestionCarousel.adapter = suggestionAdapter
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

    private fun startSuggestionRotation() {
        // Rotate suggestions every 4 seconds with animation
        val runnable = object : Runnable {
            override fun run() {
                runOnUiThread { rotateSuggestion() }
                binding.root.postDelayed(this, 4000)
            }
        }
        binding.root.postDelayed(runnable, 4000)
    }

    private fun rotateSuggestion() {
        val nextIndex = (currentSuggestionIndex + 1) % commandSuggestions.size

        // Fade out current suggestion
        val fadeOut = AlphaAnimation(1f, 0f)
        fadeOut.duration = 300
        fadeOut.setAnimationListener(object : Animation.AnimationListener {
            override fun onAnimationStart(animation: Animation?) {}
            override fun onAnimationEnd(animation: Animation?) {
                // Update text
                currentSuggestionIndex = nextIndex
                suggestionAdapter.notifyItemChanged(currentSuggestionIndex)

                // Scroll to the new suggestion
                binding.suggestionCarousel.smoothScrollToPosition(currentSuggestionIndex)

                // Fade in new suggestion
                val fadeIn = AlphaAnimation(0f, 1f)
                fadeIn.duration = 300
                binding.suggestionCarousel.startAnimation(fadeIn)
            }
            override fun onAnimationRepeat(animation: Animation?) {}
        })
        binding.suggestionCarousel.startAnimation(fadeOut)
    }

    private fun submitCommand() {
        val input = binding.commandInput.text.toString()
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return

        // Clear input
        binding.commandInput.setText("")

        // Show feedback
        binding.commandText.text = "Heard: $trimmed"
        binding.commandText.visibility = View.VISIBLE

        speak("Processing: $trimmed")
        processTextCommand(trimmed)
    }

    private fun processTextCommand(command: String) {
        val lowerCommand = command.lowercase(Locale.getDefault())

        when {
            lowerCommand.contains("open") -> extractAppName(lowerCommand)?.let { openApp(it) }
            lowerCommand.contains("call") -> extractContactName(lowerCommand)?.let { resolveAndCall(it) }
            lowerCommand.contains("whatsapp") || lowerCommand.contains("message") -> {
                extractContactAndMessage(lowerCommand)?.let { (contact, message) -> resolveAndSendWhatsApp(contact, message) }
            }
            lowerCommand.contains("search") || lowerCommand.contains("google") || lowerCommand.contains("look up") -> {
                extractSearchQuery(lowerCommand)?.let { searchWeb(it) }
            }
            else -> {
                speak("Command not recognized. Try: open app name, call contact name, whatsapp contact message, or search query")
                Toast.makeText(this, "Command not recognized", Toast.LENGTH_LONG).show()
            }
        }
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
            "look up\\s+(.+)".toRegex(),
            "find\\s+(.+)".toRegex()
        )
        for (pattern in patterns) {
            val match = pattern.find(command)
            if (match != null) {
                return match.groupValues[1].trim()
            }
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
        super.onDestroy()
    }

    // ===== SUGGESTION ADAPTER =====

    inner class SuggestionAdapter(
        private val suggestions: List<String>,
        private val onClick: (String) -> Unit
    ) : RecyclerView.Adapter<SuggestionAdapter.SuggestionViewHolder>() {

        inner class SuggestionViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val textView: TextView = view.findViewById(android.R.id.text1)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SuggestionViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(
                android.R.layout.simple_list_item_1, parent, false
            )
            // Style the suggestion items
            val textView = view.findViewById<TextView>(android.R.id.text1)
            textView.textSize = 14f
            textView.setTextColor(ContextCompat.getColor(parent.context, R.color.primary_text))
            textView.setPadding(24, 16, 24, 16)
            textView.gravity = android.view.Gravity.CENTER_VERTICAL
            textView.setBackgroundResource(R.drawable.suggestion_item_bg)
            return SuggestionViewHolder(view)
        }

        override fun onBindViewHolder(holder: SuggestionViewHolder, position: Int) {
            val suggestion = suggestions[position]
            holder.textView.text = suggestion
            holder.itemView.setOnClickListener { onClick(suggestion) }

            // Add subtle animation on bind
            holder.itemView.alpha = 0f
            holder.itemView.animate()
                .alpha(1f)
                .setDuration(300)
                .setStartDelay(position * 50L)
                .start()
        }

        override fun getItemCount() = suggestions.size
    }
}