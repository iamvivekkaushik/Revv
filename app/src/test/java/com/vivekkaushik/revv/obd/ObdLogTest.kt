package com.vivekkaushik.revv.obd

import java.io.File
import java.nio.file.Files
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ObdLogTest {

    private val folder: File = Files.createTempDirectory("obdlog").toFile()

    private fun log() = ObdLog(folder, capacity = 3, echo = {})

    @Test
    fun linesAreSavedAsTheyCome() {
        log().add("> ATZ   < ELM327 v2.1")
        val saved = File(folder, ObdLog.fileName(LocalDate.now())).readLines()
        assertEquals(1, saved.size)
        assertTrue(saved.single(), saved.single().endsWith("> ATZ   < ELM327 v2.1"))
    }

    @Test
    fun aRestartKeepsTodaysLogAndShowsItsEnd() {
        log().apply {
            add("one")
            add("two")
        }
        val restarted = log()
        restarted.add("three")
        restarted.add("four")
        assertEquals(4, File(folder, ObdLog.fileName(LocalDate.now())).readLines().size)
        // The view keeps only the newest few lines.
        assertEquals(listOf("two", "three", "four"), restarted.lines.value.map { it.substringAfter("  ") })
    }

    @Test
    fun withSavingOff_nothingIsWrittenButTheViewStillFills() {
        val log = log().apply { saving = false }
        log.add("> ATZ")
        assertTrue(folder.list().isNullOrEmpty())
        assertEquals(1, log.lines.value.size)
    }

    @Test
    fun onlyTheNewestFortnightIsKept() {
        folder.mkdirs()
        val today = LocalDate.now()
        (1..20).forEach { File(folder, ObdLog.fileName(today.minusDays(it.toLong()))).writeText("old\n") }
        log().add("new")
        val kept = folder.list()!!.sorted()
        assertEquals(14, kept.size)
        assertEquals(ObdLog.fileName(today), kept.last())
    }
}
