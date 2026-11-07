package com.buddyv2.app

import android.content.Context
import android.util.Log
import com.buddyv2.app.BuildConfig
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * File-based diagnostic logger for voice pipeline troubleshooting.
 * Logs all callbacks with timestamps to a local file that can be exported.
 */
class DiagnosticLogger(private val context: Context) {

    private val logQueue = ConcurrentLinkedQueue<String>()
    private val logFile: File
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
    private var isEnabled = true

    companion object {
        private const val TAG = "DiagnosticLogger"
        private const val LOG_FILE_NAME = "voice_diagnostic.log"
        private const val MAX_LOG_LINES = 5000
    }

    init {
        logFile = File(context.filesDir, LOG_FILE_NAME)
        log("=== DiagnosticLogger initialized ===")
        log("Log file: ${logFile.absolutePath}")
        log("App version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
    }

    /** Enable or disable logging */
    fun setEnabled(enabled: Boolean) {
        isEnabled = enabled
        log("Logging ${if (enabled) "ENABLED" else "DISABLED"}")
    }

    /** Log a diagnostic event with timestamp */
    fun log(message: String) {
        if (!isEnabled) return
        val timestamp = dateFormat.format(Date())
        val line = "[$timestamp] $message"
        logQueue.add(line)

        // Write to file periodically (every 10 entries)
        if (logQueue.size >= 10) {
            flushToFile()
        }

        // Also log to logcat for immediate visibility
        Log.d(TAG, line)
    }

    /** Force flush queue to file */
    fun flushToFile() {
        if (logQueue.isEmpty()) return

        try {
            FileWriter(logFile, true).use { writer ->
                var count = 0
                while (count < 100 && !logQueue.isEmpty()) {
                    val line = logQueue.poll()
                    line?.let { writer.write("$it\n") }
                    count++
                }
            }

            // Trim file if too large
            trimIfNeeded()
        } catch (e: IOException) {
            Log.e(TAG, "Failed to write log file", e)
        }
    }

    /** Trim log file to max lines */
    private fun trimIfNeeded() {
        try {
            val lines = logFile.readLines()
            if (lines.size > MAX_LOG_LINES) {
                val toKeep = lines.drop(lines.size - MAX_LOG_LINES)
                logFile.writeText(toKeep.joinToString("\n") + "\n")
            }
        } catch (e: IOException) {
            Log.e(TAG, "Failed to trim log file", e)
        }
    }

    /** Get log file for sharing */
    fun getLogFile(): File {
        flushToFile()
        return logFile
    }

    /** Get log content as string */
    fun getLogContent(): String {
        flushToFile()
        return try {
            logFile.readText()
        } catch (e: IOException) {
            "Failed to read log file: ${e.message}"
        }
    }

    /** Clear log file */
    fun clear() {
        logQueue.clear()
        try {
            logFile.writeText("")
            log("=== Log cleared ===")
        } catch (e: IOException) {
            Log.e(TAG, "Failed to clear log file", e)
        }
    }

    /** Shutdown logger */
    fun shutdown() {
        flushToFile()
        log("=== DiagnosticLogger shutdown ===")
    }
}