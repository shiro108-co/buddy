package com.buddyv2.app

import android.Manifest
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
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.buddyv2.app.databinding.ActivityMainBinding
import java.util.Locale
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var speechRecognizer: SpeechRecognizer? = null
    private var textToSpeech: TextToSpeech? = null
    private var isListening = false

    private val permissions = arrayOf(
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.CALL_PHONE,
        Manifest.permission.SYSTEM_ALERT_WINDOW,
        Manifest.permission.READ_CONTACTS
    )

    private val PERMISSION_REQUEST_CODE = 100
    private val OVERLAY_PERMISSION_REQUEST_CODE = 101
    private val CALL_PERMISSION_REQUEST_CODE = 102
    private val CONTACTS_PERMISSION_REQUEST_CODE = 103

    // Pending operations waiting for permissions
    private var pendingCallNumber: String? = null
    private var pendingWhatsAppContact: String? = null
    private var pendingWhatsAppMessage: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        initTextToSpeech()
        initSpeechRecognizer()
        setupClickListeners()
        checkPermissions()
    }

    private fun initTextToSpeech() {
        textToSpeech = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                textToSpeech?.language = Locale.getDefault()
            }
        }
    }

    private fun initSpeechRecognizer() {
        if (SpeechRecognizer.isRecognitionAvailable(this)) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
            speechRecognizer?.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    runOnUiThread { binding.statusText.text = getString(R.string.listening) }
                }

                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {
                    runOnUiThread { binding.statusText.text = getString(R.string.processing) }
                }

                override fun onError(error: Int) {
                    runOnUiThread {
                        binding.statusText.text = getString(R.string.tap_mic_to_speak)
                        isListening = false
                        binding.micButton.setImageResource(android.R.drawable.ic_btn_speak_now)
                    }
                }

                override fun onResults(results: Bundle) {
                    val matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    matches?.firstOrNull()?.let { command ->
                        runOnUiThread { processVoiceCommand(command) }
                    }
                    runOnUiThread {
                        binding.statusText.text = getString(R.string.tap_mic_to_speak)
                        isListening = false
                        binding.micButton.setImageResource(android.R.drawable.ic_btn_speak_now)
                    }
                }

                override fun onPartialResults(partialResults: Bundle) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        } else {
            binding.statusText.text = "Voice recognition not available"
            binding.micButton.isEnabled = false
        }
    }

    private fun setupClickListeners() {
        binding.micButton.setOnClickListener { toggleListening() }
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

    private fun toggleListening() {
        if (isListening) {
            stopListening()
        } else {
            startListening()
        }
    }

    private fun startListening() {
        speechRecognizer?.let { recognizer ->
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak a command...")
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                // Increase listening duration by 6 seconds (default is typically ~5-10s)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 8000)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 8000)
            }
            try {
                recognizer.startListening(intent)
                isListening = true
                binding.micButton.setImageResource(android.R.drawable.ic_media_pause)
                binding.statusText.text = getString(R.string.listening)
            } catch (e: Exception) {
                Toast.makeText(this, "Error starting voice recognition: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun stopListening() {
        speechRecognizer?.stopListening()
        isListening = false
        binding.micButton.setImageResource(android.R.drawable.ic_btn_speak_now)
        binding.statusText.text = getString(R.string.tap_mic_to_speak)
    }

    private fun processVoiceCommand(command: String) {
        val lowerCommand = command.lowercase(Locale.getDefault())
        binding.commandText.text = "Heard: $command"
        binding.commandText.visibility = android.view.View.VISIBLE

        speak("Processing: $command")

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
        // Pattern: "whatsapp [contact] [message]" or "message [contact] [message]"
        // Also handle "whatsapp Rahul, tell him I'm coming" format
        val whatsappMatch = "whatsapp\\s+(.+?)\\s*(?:,|\\s+)(?:tell\\s+(?:him|her|them)\\s+)?(.+)".toRegex().find(command)
        val messageMatch = "message\\s+(.+?)\\s*(?:,|\\s+)(?:tell\\s+(?:him|her|them)\\s+)?(.+)".toRegex().find(command)

        // Fallback to simple space-separated
        val simpleWhatsAppMatch = "whatsapp\\s+(.+?)\\s+(.+)".toRegex().find(command)
        val simpleMessageMatch = "message\\s+(.+?)\\s+(.+)".toRegex().find(command)

        return whatsappMatch?.let { (it.groupValues[1], it.groupValues[2]) }
            ?: messageMatch?.let { (it.groupValues[1], it.groupValues[2]) }
            ?: simpleWhatsAppMatch?.let { (it.groupValues[1], it.groupValues[2]) }
            ?: simpleMessageMatch?.let { (it.groupValues[1], it.groupValues[2]) }
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
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            pendingCallNumber = null // We'll resolve after permission
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.READ_CONTACTS), CONTACTS_PERMISSION_REQUEST_CODE)
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
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            pendingWhatsAppContact = contactName
            pendingWhatsAppMessage = message
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.READ_CONTACTS), CONTACTS_PERMISSION_REQUEST_CODE)
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
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) {
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
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CALL_PHONE), CALL_PERMISSION_REQUEST_CODE)
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
        textToSpeech?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "utterance")
    }

    override fun onDestroy() {
        speechRecognizer?.destroy()
        textToSpeech?.shutdown()
        super.onDestroy()
    }
}