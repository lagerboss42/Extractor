package com.majk.extractor.data.storage

import android.content.Context
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Local captures and manifests under /storage/emulated/0/Extractor/userdata/.
 * Naming: Capture-yymmdd-NN / Manifest-yymmdd-NN (sequence resets at local midnight).
 * Hard-deletes files older than the configured prune window.
 * Loading a capture does not create a new file.
 */
object UserDataStore {

    private val DATE_FMT = DateTimeFormatter.ofPattern("yyMMdd")
    private const val BASE = "/storage/emulated/0/Extractor/userdata"
    private const val CAPTURES = "captures"
    private const val MANIFESTS = "manifests"

    private var context: Context? = null

    fun init(ctx: Context) {
        context = ctx.applicationContext
        ensureDirs()
    }

    private fun baseDir(): File = File(BASE).also { it.mkdirs() }
    private fun capturesDir(): File = File(baseDir(), CAPTURES).also { it.mkdirs() }
    private fun manifestsDir(): File = File(baseDir(), MANIFESTS).also { it.mkdirs() }

    private fun ensureDirs() {
        capturesDir()
        manifestsDir()
    }

    /** Next free sequence number for today (local midnight reset). */
    private fun nextSequence(dir: File, prefix: String, date: String): Int {
        val pattern = Regex("^${Regex.escape(prefix)}-$date-(\\d+)\\.")
        var max = 0
        dir.listFiles()?.forEach { f ->
            val m = pattern.find(f.name)
            if (m != null) {
                val n = m.groupValues[1].toIntOrNull() ?: 0
                if (n > max) max = n
            }
        }
        return max + 1
    }

    /**
     * Save a capture (raw log or pcap content). Returns the file written.
     * Does nothing if content is blank.
     */
    fun saveCapture(content: String): File? {
        if (content.isBlank()) return null
        ensureDirs()
        val date = LocalDate.now().format(DATE_FMT)
        val seq = nextSequence(capturesDir(), "Capture", date)
        val name = "Capture-$date-${"%02d".format(seq)}.txt"
        val file = File(capturesDir(), name)
        file.writeText(content, Charsets.ISO_8859_1)
        pruneOld()
        return file
    }

    /**
     * Save a generated manifest Excel. Returns the destination file.
     * Caller writes the bytes (or we copy from a temp file).
     */
    fun nextManifestFile(): File {
        ensureDirs()
        val date = LocalDate.now().format(DATE_FMT)
        val seq = nextSequence(manifestsDir(), "Manifest", date)
        val name = "Manifest-$date-${"%02d".format(seq)}.xlsx"
        return File(manifestsDir(), name)
    }

    /** Hard-delete files older than pruneDays in both folders. */
    fun pruneOld() {
        val days = com.majk.extractor.data.prefs.AppPreferences.pruneDays
        val cutoff = System.currentTimeMillis() - days.toLong() * 24 * 60 * 60 * 1000
        listOf(capturesDir(), manifestsDir()).forEach { dir ->
            dir.listFiles()?.forEach { f ->
                if (f.isFile && f.lastModified() < cutoff) {
                    try { f.delete() } catch (_: Exception) {}
                }
            }
        }
    }
}
