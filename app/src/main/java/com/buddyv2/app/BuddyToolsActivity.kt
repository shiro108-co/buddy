package com.buddyv2.app

import android.app.*
import android.content.*
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
import android.os.*
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
import java.net.URLEncoder
import java.util.Locale

class BuddyLocalStore(context: Context) : SQLiteOpenHelper(context, "buddy_local.db", null, 2) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE history(id INTEGER PRIMARY KEY AUTOINCREMENT, command TEXT NOT NULL, created INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE aliases(name TEXT PRIMARY KEY, command TEXT NOT NULL)" )
        db.execSQL("CREATE TABLE documents(id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, content TEXT NOT NULL, indexed INTEGER NOT NULL)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) db.execSQL("CREATE TABLE IF NOT EXISTS documents(id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, content TEXT NOT NULL, indexed INTEGER NOT NULL)")
    }
    fun indexDocument(name: String, content: String) {
        writableDatabase.execSQL("INSERT INTO documents(name,content,indexed) VALUES(?,?,?)", arrayOf(name, content, System.currentTimeMillis()))
    }
    fun searchDocuments(query: String): List<String> {
        val needle = "%$query%"
        return readableDatabase.rawQuery("SELECT name,content FROM documents WHERE content LIKE ? ORDER BY indexed DESC LIMIT 20", arrayOf(needle)).use { c ->
            buildList {
                while (c.moveToNext()) {
                    val name = c.getString(0)
                    val content = c.getString(1)
                    val line = content.lineSequence().firstOrNull { it.contains(query, ignoreCase = true) } ?: content.take(180)
                    add("$name: ${line.trim().take(240)}")
                }
            }
        }
    }
    fun log(command: String) { writableDatabase.execSQL("INSERT INTO history(command,created) VALUES(?,?)", arrayOf(command, System.currentTimeMillis())) }
    fun history(): List<String> = readableDatabase.rawQuery("SELECT command,datetime(created/1000,'unixepoch','localtime') FROM history ORDER BY id DESC LIMIT 100", null).use { c ->
        buildList { while (c.moveToNext()) add("${c.getString(1)}  •  ${c.getString(0)}") }
    }
    fun clearHistory() { writableDatabase.delete("history", null, null) }
    fun saveAlias(name: String, command: String) { writableDatabase.execSQL("INSERT OR REPLACE INTO aliases(name,command) VALUES(?,?)", arrayOf(name.lowercase(Locale.ROOT), command)) }
    fun aliases(): List<Pair<String,String>> = readableDatabase.rawQuery("SELECT name,command FROM aliases ORDER BY name", null).use { c -> buildList { while(c.moveToNext()) add(c.getString(0) to c.getString(1)) } }
    fun clearAliases() { writableDatabase.delete("aliases", null, null) }
}

class BuddyToolsActivity : AppCompatActivity() {
    private lateinit var store: BuddyLocalStore
    private lateinit var root: LinearLayout
    private var tts: TextToSpeech? = null
    private val pad get() = (16 * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = BuddyLocalStore(this)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 301)
        tts = TextToSpeech(this) { if (it == TextToSpeech.SUCCESS) tts?.language = Locale.getDefault() }
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad,pad,pad,pad); setBackgroundColor(0xFF101116.toInt()) }
        val scroll = ScrollView(this).apply { addView(root) }
        setContentView(scroll)
        text("BUDDY TOOLS", 24, true)
        text("Local-first utilities. No account, API key or cloud storage.", 13)
        section("Knowledge")
        val query = EditText(this).apply { hint = "Ask Wikipedia or search the web"; setSingleLine(true); setTextColor(-1); setHintTextColor(0xFF999999.toInt()) }
        root.addView(query, match())
        button("Search and answer") {
            val q = query.text.toString().trim()
            if (q.isNotEmpty()) {
                text("Searching Wikipedia…", 13)
                Thread {
                    val answer = runCatching {
                        val slug = URLEncoder.encode(q.replace(' ', '_'), "UTF-8")
                        val conn = java.net.URL("https://en.wikipedia.org/api/rest_v1/page/summary/$slug").openConnection() as java.net.HttpURLConnection
                        conn.connectTimeout = 7000; conn.readTimeout = 7000
                        try {
                            val json = org.json.JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                            json.optString("extract").ifBlank { "No summary found. Try a web search." } to json.optString("content_urls").let { runCatching { org.json.JSONObject(it).getJSONObject("desktop").getString("page") }.getOrDefault("https://en.wikipedia.org/wiki/Special:Search?search=$slug") }
                        } finally { conn.disconnect() }
                    }.getOrElse { "Could not retrieve an answer. Check your internet connection." to "" }
                    runOnUiThread { text(answer.first, 14); if (answer.second.isNotBlank()) button("Open source") { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(answer.second))) }; store.log("Knowledge: $q") }
                }.start()
            }
        }
        button("Open web search") { val q=query.text.toString().trim(); if(q.isNotEmpty()) startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://html.duckduckgo.com/html/?q="+Uri.encode(q)))) }
        section("Reminders")
        val reminder = EditText(this).apply { hint = "Reminder text"; setTextColor(-1); setHintTextColor(0xFF999999.toInt()) }
        val minutes = EditText(this).apply { hint = "Minutes from now"; inputType = 2; setTextColor(-1); setHintTextColor(0xFF999999.toInt()) }
        val repeat = CheckBox(this).apply { text = "Repeat at this interval"; setTextColor(-1) }
        root.addView(reminder, match()); root.addView(minutes, match()); root.addView(repeat, match())
        button("Set reminder") {
            val msg=reminder.text.toString().trim(); val delay=minutes.text.toString().toLongOrNull()
            if(msg.isBlank() || delay==null || delay<1) toast("Enter reminder text and a delay of at least 1 minute")
            else { scheduleReminder(msg, delay, repeat.isChecked); toast(if(repeat.isChecked) "Repeating reminder scheduled" else "Reminder scheduled"); store.log("Reminder: $msg") }
        }
        section("Custom commands")
        val alias = EditText(this).apply { hint = "Shortcut name"; setTextColor(-1); setHintTextColor(0xFF999999.toInt()) }
        val action = EditText(this).apply { hint = "Command or URL to open"; setTextColor(-1); setHintTextColor(0xFF999999.toInt()) }
        root.addView(alias, match()); root.addView(action, match())
        button("Save shortcut") { val a=alias.text.toString().trim(); val c=action.text.toString().trim(); if(a.isBlank()||c.isBlank()) toast("Enter both fields") else { store.saveAlias(a,c); toast("Shortcut saved locally") } }
        store.aliases().forEach { (a,c) -> button("Run /$a") { runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(c))) }.onFailure { startActivity(Intent(Intent.ACTION_WEB_SEARCH).putExtra(android.app.SearchManager.QUERY, c)) } } }
        section("Phone intelligence")
        val stat = android.os.StatFs(android.os.Environment.getDataDirectory().path)
        val total = stat.totalBytes / 1_073_741_824.0
        val free = stat.availableBytes / 1_073_741_824.0
        val battery = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val batteryPct = battery?.let { it.getIntExtra(BatteryManager.EXTRA_LEVEL, 0) * 100 / it.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1) } ?: -1
        val cm = getSystemService(CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val net = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }
        val network = when {
            net == null -> "Offline"
            net.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            net.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) -> "Mobile data"
            else -> "Connected"
        }
        text("Device: ${Build.MANUFACTURER} ${Build.MODEL}\\nAndroid: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\\nBattery: ${if (batteryPct >= 0) "$batteryPct%" else "Unavailable"}\\nNetwork: $network\\nStorage: ${"%.1f".format(total-free)} / ${"%.1f".format(total)} GB used\\nApp version: ${packageManager.getPackageInfo(packageName,0).versionName}", 14)
        section("Accessibility")
        button("Read latest local history aloud") { val items=store.history().take(5); tts?.speak(if(items.isEmpty()) "No history yet" else items.joinToString(". "), TextToSpeech.QUEUE_FLUSH, null, "buddy-history") }
        button("Stop speech") { tts?.stop() }
        section("Privacy and local data")
        text("History, shortcuts and reminders are stored on this device. Wikipedia lookup and web search send the search query to the selected service.", 13)
        button("View recent command history") { val items=store.history(); text(if(items.isEmpty()) "No saved history" else items.joinToString("\n"), 12) }
        button("Delete command history") { store.clearHistory(); toast("History deleted") }
        button("Delete custom shortcuts") { store.clearAliases(); toast("Shortcuts deleted"); recreate() }
        section("File search")
        text("Choose a folder or file through Android's system picker. Buddy only sees items you select; no broad storage permission is needed.", 13)
        button("Choose text file to index") { openFilePicker() }
        val fileQuery = EditText(this).apply { hint = "Search indexed documents"; setTextColor(-1); setHintTextColor(0xFF999999.toInt()) }
        root.addView(fileQuery, match())
        button("Search my selected files") {
            val q = fileQuery.text.toString().trim()
            if (q.isBlank()) toast("Enter a search phrase")
            else {
                val matches = store.searchDocuments(q)
                text(if (matches.isEmpty()) "No matches in indexed files" else matches.joinToString("\\n\\n"), 13)
            }
        }
        text("Text files only (TXT, MD, CSV, JSON, XML and similar). Selected content is indexed locally; PDFs and office documents are not parsed.", 12)
        section("Permission dashboard")
        button("Check permissions") { showPermissionDashboard() }
        button("Open Android app permissions") { startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }
        button("Back") { finish() }
    }

    private fun openFilePicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "text/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        startActivityForResult(intent, 410)
    }

    @Deprecated("Uses the platform document picker for Android 7 compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 410 && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            val name = contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            } ?: uri.lastPathSegment ?: "Selected file"
            text("Selected: $name\nType: ${contentResolver.getType(uri) ?: "unknown"}", 14)
            store.log("Selected file: $name")
        }
    }

    private fun showPermissionDashboard() {
        val checks = listOf(
            "Microphone" to android.Manifest.permission.RECORD_AUDIO,
            "Contacts" to android.Manifest.permission.READ_CONTACTS,
            "Phone calls" to android.Manifest.permission.CALL_PHONE,
            "Notifications" to android.Manifest.permission.POST_NOTIFICATIONS
        )
        val lines = checks.map { (label, permission) ->
            val supported = Build.VERSION.SDK_INT >= 33 || permission != android.Manifest.permission.POST_NOTIFICATIONS
            val granted = supported && checkSelfPermission(permission) == android.content.pm.PackageManager.PERMISSION_GRANTED
            "$label: ${if (!supported) "Not applicable" else if (granted) "Allowed" else "Not granted"}"
        }.toMutableList()
        lines += "Modify system settings: ${if (Build.VERSION.SDK_INT >= 23 && android.provider.Settings.System.canWrite(this)) "Allowed" else "Not granted"}"
        lines += "Display over other apps: ${if (Build.VERSION.SDK_INT >= 23 && android.provider.Settings.canDrawOverlays(this)) "Allowed" else "Not granted"}"
        lines += "Usage access: ${if (Build.VERSION.SDK_INT >= 21 && (getSystemService(APP_OPS_SERVICE) as android.app.AppOpsManager).checkOpNoThrow("android:get_usage_stats", android.os.Process.myUid(), packageName) == android.app.AppOpsManager.MODE_ALLOWED) "Allowed" else "Not granted"}"
        text(lines.joinToString("\n"), 14)
        button("Manage all permissions") {
            startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
        button("Open special app access") {
            startActivity(Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
    }

    private fun scheduleReminder(message: String, delayMinutes: Long, repeat: Boolean) {
        val intent=Intent(this, ReminderReceiver::class.java).putExtra("message", message)
        val id=(System.currentTimeMillis() % Int.MAX_VALUE).toInt()
        val pending=PendingIntent.getBroadcast(this,id,intent,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val alarm = getSystemService(ALARM_SERVICE) as AlarmManager
        val first = System.currentTimeMillis() + delayMinutes * 60_000
        if (repeat) alarm.setInexactRepeating(AlarmManager.RTC_WAKEUP, first, delayMinutes * 60_000, pending)
        else alarm.set(AlarmManager.RTC_WAKEUP, first, pending)
    }
    private fun text(value:String,size:Int=14,bold:Boolean=false) { root.addView(TextView(this).apply { text=value; textSize=size.toFloat(); setTextColor(-1); if(bold)setTypeface(null,1); setPadding(0,pad/2,0,pad/2) },match()) }
    private fun section(value:String) { text(value,18,true) }
    private fun button(value:String, action:()->Unit) { root.addView(Button(this).apply { text=value; setOnClickListener { action() } },match()) }
    private fun match()=LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT)
    private fun toast(s:String)=Toast.makeText(this,s,Toast.LENGTH_SHORT).show()
    override fun onDestroy() { tts?.stop(); tts?.shutdown(); super.onDestroy() }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val manager=context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if(Build.VERSION.SDK_INT>=26) manager.createNotificationChannel(NotificationChannel("buddy_reminders","Buddy reminders",NotificationManager.IMPORTANCE_DEFAULT))
        val notification=NotificationCompat.Builder(context,"buddy_reminders")
            .setSmallIcon(android.R.drawable.ic_popup_reminder).setContentTitle("Buddy reminder")
            .setContentText(intent.getStringExtra("message") ?: "Reminder").setAutoCancel(true).build()
        manager.notify((System.currentTimeMillis()%Int.MAX_VALUE).toInt(),notification)
    }
}
