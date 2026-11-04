package com.buddyv2.app

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.buddyv2.app.databinding.ActivityMainBinding
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var speechRecognizer: SpeechRecognizer? = null
    private var textToSpeech: TextToSpeech? = null
    private var isListening = false

    private val permissions = arrayOf(
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.CALL_PHONE,
        Manifest.permission.SYSTEM_ALERT_WINDOW
    )

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
                    runOnUiThread { binding.statusText.text = "Listening..." }
                }

                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {
                    runOnUiThread { binding.statusText.text = "Processing..." }
                }

                override fun onError(error: Int) {
                    runOnUiThread {
                        binding.statusText.text = "Tap mic to speak"
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
                        binding.statusText.text = "Tap mic to speak"
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
        binding.openAppButton.setOnClickListener { showAppPicker() }
        binding.callButton.setOnClickListener { showCallDialog() }
        binding.whatsappButton.setOnClickListener { showWhatsAppDialog() }
        binding.searchButton.setOnClickListener { showSearchDialog() }
    }

    private fun checkPermissions() {
        val missingPermissions = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missingPermissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missingPermissions.toTypedArray(), 100)
        }
        // Check overlay permission for Android 23+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            startActivityForResult(intent, 101)
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
            }
            try {
                recognizer.startListening(intent)
                isListening = true
                binding.micButton.setImageResource(android.R.drawable.ic_media_pause)
                binding.statusText.text = "Listening..."
            } catch (e: Exception) {
                Toast.makeText(this, "Error starting voice recognition: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun stopListening() {
        speechRecognizer?.stopListening()
        isListening = false
        binding.micButton.setImageResource(android.R.drawable.ic_btn_speak_now)
        binding.statusText.text = "Tap mic to speak"
    }

    private fun processVoiceCommand(command: String) {
        val lowerCommand = command.lowercase(Locale.getDefault())
        binding.commandText.text = "Heard: $command"

        speak("Processing: $command")

        when {
            lowerCommand.contains("open") -> extractAppName(lowerCommand)?.let { openApp(it) }
            lowerCommand.contains("call") -> extractPhoneNumber(lowerCommand)?.let { makeCall(it) }
            lowerCommand.contains("whatsapp") || lowerCommand.contains("message") -> {
                extractContactAndMessage(lowerCommand)?.let { (contact, message) -> sendWhatsApp(contact, message) }
            }
            lowerCommand.contains("search") || lowerCommand.contains("google") || lowerCommand.contains("look up") -> {
                extractSearchQuery(lowerCommand)?.let { searchWeb(it) }
            }
            else -> {
                speak("Command not recognized. Try: open app name, call number, whatsapp contact message, or search query")
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
                // Extract digits, +, *, # from the command
                val phoneNumber = after.filter { it.isDigit() || it in "+*#" }
                if (phoneNumber.isNotEmpty()) return phoneNumber
            }
        }
        return null
    }

    private fun extractContactAndMessage(command: String): Pair<String, String>? {
    val patterns = listOf(
        "whatsapp\\s+(.+?)\\s+(.+)".toRegex(),
        "message\\s+(.+?)\\s+(.+)".toRegex()
    )
    return patterns.firstNotNullOfOrNull { pattern ->
        pattern.find(command)?.let {
            it.groupValues[1] to it.groupValues[2]
        }
    }
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
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CALL_PHONE), 102)
        }
    }

    private fun sendWhatsApp(contact: String, message: String) {
        val packageManager = packageManager
        val whatsappPackage = if (packageManager.getLaunchIntentForPackage("com.whatsapp") != null) {
            "com.whatsapp"
        } else if (packageManager.getLaunchIntentForPackage("com.whatsapp.w4b") != null) {
            "com.whatsapp.w4b"
        } else {
            speak("WhatsApp not installed")
            Toast.makeText(this, "WhatsApp not installed", Toast.LENGTH_SHORT).show()
            return
        }

        // Try to find contact - for simplicity, we'll use the web intent with phone number
        // In a real app, you'd query contacts
        val encodedMessage = Uri.encode(message)
        val url = "https://wa.me/?text=$encodedMessage"
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            setPackage(whatsappPackage)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        try {
            startActivity(intent)
            speak("Opening WhatsApp to send message to $contact")
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

    private fun showAppPicker() {
        val packageManager = packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val activities = packageManager.queryIntentActivities(intent, 0)

        val appNames = activities.map { it.loadLabel(packageManager).toString() }.sorted()
        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Select App to Open")
            .setItems(appNames.toTypedArray()) { _, which ->
                val selected = activities[which]
                val launchIntent = packageManager.getLaunchIntentForPackage(selected.activityInfo.packageName)
                launchIntent?.let { startActivity(it) }
                speak("Opening ${selected.loadLabel(packageManager)}")
            }
            .create()
        dialog.show()
    }

    private fun showCallDialog() {
        val input = android.widget.EditText(this).apply {
            hint = "Phone number"
            inputType = android.text.InputType.TYPE_CLASS_PHONE
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Make Call")
            .setView(input)
            .setPositiveButton("Call") { _, _ ->
                val number = input.text.toString().trim()
                if (number.isNotEmpty()) makeCall(number)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showWhatsAppDialog() {
        val contactInput = android.widget.EditText(this).apply { hint = "Contact name or number" }
        val messageInput = android.widget.EditText(this).apply { hint = "Message" }
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            addView(contactInput)
            addView(messageInput)
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Send WhatsApp Message")
            .setView(layout)
            .setPositiveButton("Send") { _, _ ->
                val contact = contactInput.text.toString().trim()
                val message = messageInput.text.toString().trim()
                if (contact.isNotEmpty() && message.isNotEmpty()) sendWhatsApp(contact, message)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showSearchDialog() {
        val input = android.widget.EditText(this).apply { hint = "Search query" }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Search Web")
            .setView(input)
            .setPositiveButton("Search") { _, _ ->
                val query = input.text.toString().trim()
                if (query.isNotEmpty()) searchWeb(query)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun speak(text: String) {
        textToSpeech?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "utterance")
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 102 && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            // Retry call if permission granted
        }
    }

    override fun onDestroy() {
        speechRecognizer?.destroy()
        textToSpeech?.shutdown()
        super.onDestroy()
    }
}