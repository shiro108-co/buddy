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

class BuddyLocalStore(context: Context) : SQLiteOpenHelper(context, "buddy_local.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE history(id INTEGER PRIMARY KEY AUTOINCREMENT, command TEXT NOT NULL, created INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE aliases(name TEXT PRIMARY KEY, command TEXT NOT NULL)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}
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
        root.addView(reminder, match()); root.addView(minutes, match())
        button("Set reminder") {
            val msg=reminder.text.toString().trim(); val delay=minutes.text.toString().toLongOrNull()
            if(msg.isBlank() || delay==null || delay<1) toast("Enter reminder text and a delay of at least 1 minute")
            else { scheduleReminder(msg, delay); toast("Reminder scheduled"); store.log("Reminder: $msg") }
        }
        section("Custom commands")
        val alias = EditText(this).apply { hint = "Shortcut name"; setTextColor(-1); setHintTextColor(0xFF999999.toInt()) }
        val action = EditText(this).apply { hint = "Command or URL to open"; setTextColor(-1); setHintTextColor(0xFF999999.toInt()) }
        root.addView(alias, match()); root.addView(action, match())
        button("Save shortcut") { val a=alias.text.toString().trim(); val c=action.text.toString().trim(); if(a.isBlank()||c.isBlank()) toast("Enter both fields") else { store.saveAlias(a,c); toast("Shortcut saved locally") } }
        store.aliases().forEach { (a,c) -> text("/$a  →  $c", 13) }
        section("Phone intelligence")
        val stat = android.os.StatFs(android.os.Environment.getDataDirectory().path)
        val total = stat.totalBytes / 1_073_741_824.0; val free = stat.availableBytes / 1_073_741_824.0
        text("Device: ${Build.MANUFACTURER} ${Build.MODEL}\nAndroid: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\nStorage: ${"%.1f".format(total-free)} / ${"%.1f".format(total)} GB used\nApp version: ${packageManager.getPackageInfo(packageName,0).versionName}", 14)
        section("Accessibility")
        button("Read latest local history aloud") { val items=store.history().take(5); tts?.speak(if(items.isEmpty()) "No history yet" else items.joinToString(". "), TextToSpeech.QUEUE_FLUSH, null, "buddy-history") }
        button("Stop speech") { tts?.stop() }
        section("Privacy and local data")
        text("History, shortcuts and reminders are stored on this device. Wikipedia lookup and web search send the search query to the selected service.", 13)
        button("View recent command history") { val items=store.history(); text(if(items.isEmpty()) "No saved history" else items.joinToString("\n"), 12) }
        button("Delete command history") { store.clearHistory(); toast("History deleted") }
        button("Delete custom shortcuts") { store.clearAliases(); toast("Shortcuts deleted"); recreate() }
        button("Open Android app permissions") { startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }
        button("Back") { finish() }
    }

    private fun scheduleReminder(message: String, delayMinutes: Long) {
        val intent=Intent(this, ReminderReceiver::class.java).putExtra("message", message)
        val id=(System.currentTimeMillis() % Int.MAX_VALUE).toInt()
        val pending=PendingIntent.getBroadcast(this,id,intent,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        (getSystemService(ALARM_SERVICE) as AlarmManager).set(AlarmManager.RTC_WAKEUP,System.currentTimeMillis()+delayMinutes*60_000,pending)
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
