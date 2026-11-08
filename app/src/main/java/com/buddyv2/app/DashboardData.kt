package com.buddyv2.app

import android.app.ActivityManager
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Environment
import android.os.StatFs
import android.provider.Settings
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

object DashboardData {
    data class NewsItem(val title: String, val source: String)
    data class Snapshot(val battery: String, val cpu: String, val ram: String, val storage: String,
                        val wifi: String, val bluetooth: String, val volume: String, val brightness: String)

    fun snapshot(context: Context): Snapshot {
        val batteryIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val battery = if (level >= 0 && scale > 0) "${level * 100 / scale}%" else "Unavailable"

        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memory = ActivityManager.MemoryInfo().also { activityManager.getMemoryInfo(it) }
        val totalRam = memory.totalMem / 1_073_741_824.0
        val usedRam = (memory.totalMem - memory.availMem) / 1_073_741_824.0
        val ram = String.format(Locale.getDefault(), "%.1f / %.1f GB (%.0f%%)", usedRam, totalRam,
            if (memory.totalMem > 0) (memory.totalMem - memory.availMem) * 100.0 / memory.totalMem else 0.0)

        val stat = StatFs(Environment.getDataDirectory().path)
        val totalStorage = stat.totalBytes
        val usedStorage = totalStorage - stat.availableBytes
        val storage = String.format(Locale.getDefault(), "%.1f / %.1f GB (%.0f%%)",
            usedStorage / 1_073_741_824.0, totalStorage / 1_073_741_824.0,
            if (totalStorage > 0) usedStorage * 100.0 / totalStorage else 0.0)

        val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivity.activeNetwork
        val caps = network?.let { connectivity.getNetworkCapabilities(it) }
        val wifi = when {
            caps == null -> "Disconnected"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Connected"
            else -> "Connected (non-Wi-Fi)"
        }

        val bluetooth = try {
            if (BluetoothAdapter.getDefaultAdapter()?.isEnabled == true) "On" else "Off"
        } catch (_: SecurityException) { "Permission required" }

        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val maxVolume = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val volume = "${audio.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / maxVolume}%"
        val brightness = try { "${Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS) * 100 / 255}%" }
            catch (_: Exception) { "Unavailable" }

        return Snapshot(battery, cpuUsage(), ram, storage, wifi, bluetooth, volume, brightness)
    }

    private fun cpuTimes(): Pair<Long, Long>? = try {
        val values = java.io.File("/proc/stat").bufferedReader().use { it.readLine().trim().split(Regex("\\s+")).drop(1).map(String::toLong) }
        Pair(values[3] + (values.getOrNull(4) ?: 0L), values.sum())
    } catch (_: Exception) { null }

    private fun cpuUsage(): String {
        val first = cpuTimes() ?: return "Unavailable"
        try { Thread.sleep(250) } catch (_: InterruptedException) { Thread.currentThread().interrupt(); return "Unavailable" }
        val second = cpuTimes() ?: return "Unavailable"
        val total = second.second - first.second
        val idle = second.first - first.first
        return if (total > 0) "${((total - idle) * 100 / total).coerceIn(0, 100)}%" else "Unavailable"
    }

    fun fetchNews(): List<NewsItem> {
        val connection = (URL("https://feeds.bbci.co.uk/news/world/rss.xml").openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000
            readTimeout = 8000
            setRequestProperty("User-Agent", "BuddyAndroid/1.0")
        }
        return try {
            connection.inputStream.use { input ->
                val parser = Xml.newPullParser()
                parser.setInput(input, null)
                val items = mutableListOf<NewsItem>()
                var inItem = false
                var title = ""
                var event = parser.eventType
                while (event != XmlPullParser.END_DOCUMENT && items.size < 5) {
                    when (event) {
                        XmlPullParser.START_TAG -> when (parser.name) {
                            "item" -> { inItem = true; title = "" }
                            "title" -> if (inItem) title = parser.nextText().trim()
                        }
                        XmlPullParser.END_TAG -> if (parser.name == "item" && inItem) {
                            if (title.isNotBlank()) items.add(NewsItem(title, "BBC News"))
                            inItem = false
                        }
                    }
                    event = parser.next()
                }
                items
            }
        } finally { connection.disconnect() }
    }
}
