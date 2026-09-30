package com.vivekkaushik.revv.obd

import android.util.Log
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What Revv and the adapter said on recent connection attempts, for troubleshooting a car or an
 * adapter that won't talk. Shown in Settings, echoed to logcat under "RevvObd", and saved to a
 * file per day in [directory], so a test drive without a laptop or internet can be read later.
 */
class ObdLog(
    private val directory: File?,
    private val capacity: Int = 400,
    private val echo: (String) -> Unit = { Log.d(TAG, it) },
) {

    private val _lines = MutableStateFlow(readRecent())
    val lines: StateFlow<List<String>> = _lines.asStateFlow()

    private var writer: BufferedWriter? = null
    private var writerDate: LocalDate? = null

    /** Whether lines also go to the file (Settings › Vehicle › Save adapter logs). */
    var saving: Boolean = true
        @Synchronized set(value) {
            field = value
            if (!value) closeFile()
        }

    @Synchronized
    fun add(text: String) {
        echo(text)
        val now = LocalDateTime.now()
        val line = "${now.format(CLOCK)}  $text"
        _lines.value = (_lines.value + line).takeLast(capacity)
        if (!saving) return
        try {
            writerFor(now.toLocalDate())?.apply {
                write(line)
                newLine()
                // Flushed line by line: the app may be killed, or the car switched off, at any moment.
                flush()
            }
        } catch (e: IOException) {
            closeFile()
        }
    }

    private fun closeFile() {
        runCatching { writer?.close() }
        writer = null
        writerDate = null
    }

    /** Also records any crash of the app, then lets it crash as it would have. */
    fun recordCrashes() = synchronized(ObdLog::class.java) {
        if (crashesRecorded) return
        crashesRecorded = true
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { add("Revv crashed on thread ${thread.name}:\n${error.stackTraceToString()}") }
            previous?.uncaughtException(thread, error)
        }
    }

    private fun writerFor(date: LocalDate): BufferedWriter? {
        if (directory == null) return null
        if (date != writerDate || writer == null) {
            runCatching { writer?.close() }
            directory.mkdirs()
            // Appends: a restart of the app mid-drive keeps what was already written today.
            writer = FileOutputStream(File(directory, fileName(date)), true).bufferedWriter()
            writerDate = date
            prune()
        }
        return writer
    }

    /** Keeps the newest few days of logs; a head unit's storage is small. */
    private fun prune() {
        val logs = directory?.listFiles { file -> file.name.startsWith(PREFIX) && file.name.endsWith(SUFFIX) } ?: return
        logs.sortedByDescending { it.name }.drop(KEEP_FILES).forEach { it.delete() }
    }

    /** The end of the newest saved log, so the Settings view survives a restart of the app. */
    private fun readRecent(): List<String> {
        val newest = directory?.listFiles { file -> file.name.startsWith(PREFIX) && file.name.endsWith(SUFFIX) }
            ?.maxByOrNull { it.name } ?: return emptyList()
        return runCatching { newest.readLines().takeLast(capacity) }.getOrDefault(emptyList())
    }

    companion object {
        private const val TAG = "RevvObd"
        private const val PREFIX = "obd-"
        private const val SUFFIX = ".log"
        private const val KEEP_FILES = 14
        private val CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")

        @Volatile
        private var crashesRecorded = false

        fun fileName(date: LocalDate) = "$PREFIX$date$SUFFIX"
    }
}
