package org.siros.wwwallet.util

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import org.siros.wwwallet.BuildConfig
import timber.log.Timber
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class FileLoggingTree(
    context: Context,
    private val maxFileSizeBytes: Long = 1024 * 1024, // 1MB High Watermark
) : Timber.DebugTree() {
    companion object {
        private const val LOG_FILE_NAME = "app.log"

        private val HIGH_PRIORITY = arrayOf(Log.WARN, Log.ERROR, Log.ASSERT)

        fun readLog(context: Context): List<String> = File(context.filesDir, LOG_FILE_NAME).readLines()
    }

    private val logFile = File(context.filesDir, LOG_FILE_NAME)
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    override fun log(
        priority: Int,
        tag: String?,
        message: String,
        t: Throwable?,
    ) {
        // Also write to Android logcat through Timber's built-in `DebugTree`,
        // if priority is high enough or if debug build.
        if (BuildConfig.DEBUG || HIGH_PRIORITY.contains(priority)) {
            super.log(priority, tag, message, t)
        }

        val timestamp = dateFormat.format(Date())
        val level = priorityToString(priority)
        val logTag = if (tag.isNullOrBlank()) "" else "[$tag] "
        val formattedMessage = "$timestamp $level $logTag$message"

        writeLogToFile(formattedMessage, t)
    }

    @SuppressLint("LogNotTimber")
    private fun writeLogToFile(
        message: String,
        throwable: Throwable?,
    ) {
        try {
            // Trigger truncation if we hit the High Watermark
            if (logFile.length() > maxFileSizeBytes) {
                truncateFile()
            }

            FileWriter(logFile, true).use { fw ->
                PrintWriter(fw).use { out ->
                    out.println(message)
                    if (throwable != null) {
                        out.println(Log.getStackTraceString(throwable))
                        out.println("----------------------------------------------------------------")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("FileLoggingTree", "Failed to write log", e)
        }
    }

    /**
     * Reduce the log file to ~50% of its size, removing *oldest* log lines.
     */
    private fun truncateFile() {
        try {
            val allLines = logFile.readLines()
            val keptLines = allLines.drop(allLines.size / 2)

            logFile.writeText(keptLines.joinToString("\n") + "\n")
        } catch (_: Exception) {
            // If truncation fails, just delete it to prevent disk fill-up.
            logFile.delete()
        }
    }

    private fun priorityToString(priority: Int): String =
        when (priority) {
            Log.VERBOSE -> "VERBOSE"
            Log.DEBUG -> "DEBUG"
            Log.INFO -> "INFO"
            Log.WARN -> "WARN"
            Log.ERROR -> "ERROR"
            Log.ASSERT -> "ASSERT"
            else -> "UNKNOWN"
        }
}
