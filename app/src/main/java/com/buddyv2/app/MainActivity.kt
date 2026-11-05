package com.buddyv2.app

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.ContactsContract
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.buddyv2.app.databinding.ActivityMainBinding
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var speechRecognizer: SpeechRecognizer? = null
    private var textToSpeech: TextToSpeech? = null
    private var audioManager: AudioManager? = null
    private var isListening = false
    private var isProcessing = false
    private var isBluetoothMicAvailable = false

    private val permissions = arrayOf(
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.CALL_PHONE,
        Manifest.permission.SYSTEM_ALERT_WINDOW,
        Manifest.permission.READ_CONTACTS
    )

    private val bluetoothPermissions = arrayOf(
        Manifest.permission.BLUETOOTH_CONNECT
    )

    companion object {
        private const val TAG = "BuddyV2Voice"
        const val MAX_LISTENING_DURATION_MS = 4000
        const val SILENCE_TIMEOUT_MS = 1500
    }

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

        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        checkBluetoothMicAvailability()
        initTextToSpeech()
        initSpeechRecognizer()
        setupClickListeners()
        checkPermissions()
    }

    private fun checkBluetoothMicAvailability() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val hasBluetoothConnect = ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
            if (hasBluetoothConnect) {
                val devices = audioManager?.getDevices(AudioManager.GET_DEVICES_INPUTS)
                isBluetoothMicAvailable = devices?.any { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || it.type == AudioDeviceInfo.TYPE_BLE_HEADSET || it.type == AudioDeviceInfo.TYPE_BLE_SPEAKER } == true
                Log.d(TAG, "Bluetooth mic available: $isBluetoothMicAvailable, devices: ${devices?.joinToString { it.type.toString() }}")
            } else {
                isBluetoothMicAvailable = false
                Log.d(TAG, "Bluetooth CONNECT permission not granted")
            }
        } else {
            // Legacy Bluetooth permissions (Android 11 and below)
            val hasBluetooth = ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH) == PackageManager.PERMISSION_GRANTED
            val hasBluetoothAdmin = ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADMIN) == PackageManager.PERMISSION_GRANTED
            isBluetoothMicAvailable = hasBluetooth && hasBluetoothAdmin
        }
    }

    private fun routeAudioToBluetoothMic(): Boolean {
        if (!isBluetoothMicAvailable || audioManager == null) return false
        val devices = audioManager!!.getDevices(AudioManager.GET_DEVICES_INPUTS)
        val btDevice = devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || it.type == AudioDeviceInfo.TYPE_BLE_HEADSET || it.type == AudioDeviceInfo.TYPE_BLE_SPEAKER }
        return btDevice?.let { audioManager!!.setCommunicationDevice(it); true } ?: false
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
                    Log.d(TAG, "onReadyForSpeech")
                    runOnUiThread { binding.statusText.text = getString(R.string.listening) }
                }

                override fun onBeginningOfSpeech() {
                    Log.d(TAG, "onBeginningOfSpeech - speech started")
                }
                override fun onRmsChanged(rmsdB: Float) {
                    // Log.d(TAG, "onRmsChanged: $rmsdB")
                }
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {
                    Log.d(TAG, "onEndOfSpeech - speech ended, finalizing...")
                    runOnUiThread { binding.statusText.text = getString(R.string.processing) }
                }

                override fun onError(error: Int) {
                    val (errorType, userMessage) = when (error) {
                        SpeechRecognizer.ERROR_AUDIO -> Pair("AUDIO", "Microphone unavailable. Check earbuds connection.")
                        SpeechRecognizer.ERROR_CLIENT -> Pair("CLIENT", "Client error. Try again.")
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> Pair("PERMISSION", "Microphone permission denied.")
                        SpeechRecognizer.ERROR_NETWORK -> Pair("NETWORK", "Network error. Check connection.")
                        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> Pair("NETWORK_TIMEOUT", "Network timeout. Try again.")
                        SpeechRecognizer.ERROR_NO_MATCH -> Pair("NO_MATCH", "No speech detected. Speak clearly.")
                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> Pair("BUSY", "Recognizer busy. Wait and retry.")
                        SpeechRecognizer.ERROR_SERVER -> Pair("SERVER", "Server error. Try again later.")
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> Pair("TIMEOUT", "Listening timed out. Tap mic and speak.")
                        else -> Pair("UNKNOWN_$error", "Recognition error ($error). Try again.")
                    }
                    Log.w(TAG, "onError: type=$errorType, message=$userMessage")
                    runOnUiThread {
                        binding.statusText.text = getString(R.string.tap_mic_to_speak)
                        isListening = false
                        isProcessing = false
                        binding.micButton.setImageResource(android.R.drawable.ic_btn_speak_now)
                        Toast.makeText(this@MainActivity, userMessage, Toast.LENGTH_LONG).show()
                    }
                }

                override fun onResults(results: Bundle) {
                    Log.d(TAG, "onResults received")
                    // Guard against duplicate processing
                    if (isProcessing) {
                        Log.w(TAG, "Duplicate onResults ignored")
                        return
                    }
                    isProcessing = true

                    val matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val command = matches?.firstOrNull()?.trim()

                    if (command.isNullOrEmpty()) {
                        Log.w(TAG, "Empty recognition result")
                        runOnUiThread {
                            binding.statusText.text = getString(R.string.tap_mic_to_speak)
                            isListening = false
                            isProcessing = false
                            binding.micButton.setImageResource(android.R.drawable.ic_btn_speak_now)
                            Toast.makeText(this@MainActivity, "No speech detected. Tap mic and try again.", Toast.LENGTH_SHORT).show()
                        }
                        isProcessing = false
                        return
                    }

                    Log.d(TAG, "Recognized command: $command")
                    runOnUiThread { processVoiceCommand(command!!) }

                    // Reset UI after processing
                    runOnUiThread {
                        binding.statusText.text = getString(R.string.tap_mic_to_speak)
                        isListening = false
                        isProcessing = false
                        binding.micButton.setImageResource(android.R.drawable.ic_btn_speak_now)
                    }
                }

                override fun onPartialResults(partialResults: Bundle) {
                    val partial = partialResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    if (partial != null) Log.d(TAG, "Partial: $partial")
                }
                override fun onEvent(eventType: Int, params: Bundle?) {
                    Log.d(TAG, "onEvent: $eventType")
                }
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

        // Check Bluetooth permissions for Android 12+ (for earbud microphone)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val missingBluetooth = bluetoothPermissions.filter {
                ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
            }
            if (missingBluetooth.isNotEmpty()) {
                ActivityCompat.requestPermissions(this, missingBluetooth.toTypedArray(), PERMISSION_REQUEST_CODE + 10)
            }
        }

        // Check overlay permission for Android 23+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            startActivityForResult(intent, OVERLAY_PERMISSION_REQUEST_CODE)
        }
    }

    private fun toggleListening() {
        if (isListening || isProcessing) {
            stopListening()
        } else {
            startListening()
        }
    }

    private fun startListening() {
        // Re-check Bluetooth mic availability in case it changed
        checkBluetoothMicAvailability()

        speechRecognizer?.let { recognizer ->
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak a command...")
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                // Max listening duration: 4 seconds total
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, SILENCE_TIMEOUT_MS)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, SILENCE_TIMEOUT_MS)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 200)
            }

            // Route audio to Bluetooth mic if available
            if (isBluetoothMicAvailable) {
                val routed = routeAudioToBluetoothMic()
                Log.d(TAG, "Bluetooth mic routing: $routed")
            }

            try {
                recognizer.startListening(intent)
                isListening = true
                isProcessing = false
                binding.micButton.setImageResource(android.R.drawable.ic_media_pause)
                binding.statusText.text = getString(R.string.listening)
                Log.d(TAG, "startListening: maxDuration=${MAX_LISTENING_DURATION_MS}ms, silenceTimeout=${SILENCE_TIMEOUT_MS}ms")
            } catch (e: Exception) {
                Log.e(TAG, "startListening error", e)
                Toast.makeText(this, "Error starting voice recognition: ${e.message}", Toast.LENGTH_SHORT).show()
                resetToIdle()
            }
        }
    }

    private fun resetToIdle() {
        runOnUiThread {
            binding.statusText.text = getString(R.string.tap_mic_to_speak)
            isListening = false
            isProcessing = false
            binding.micButton.setImageResource(android.R.drawable.ic_btn_speak_now)
        }
    }

    private fun stopListening() {
        speechRecognizer?.stopListening()
        resetToIdle()
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
            PERMISSION_REQUEST_CODE + 10 -> {
                // Bluetooth permissions result
                val allGranted = grantResults.all { it == PackageManager.PERMISSION_GRANTED }
                if (allGranted) {
                    checkBluetoothMicAvailability()
                    Toast.makeText(this, "Bluetooth microphone enabled", Toast.LENGTH_SHORT).show()
                } else {
                    isBluetoothMicAvailable = false
                    Toast.makeText(this, "Bluetooth permission denied. Using phone microphone.", Toast.LENGTH_LONG).show()
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