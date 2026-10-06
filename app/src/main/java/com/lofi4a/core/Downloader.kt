package com.lofi4a.core

import android.content.Context
import androidx.compose.runtime.mutableStateMapOf
import kotlinx.coroutines.*
import java.io.*
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

sealed interface DlState {
    object Idle : DlState
    data class Progress(val done: Long, val total: Long) : DlState
    object Verifying : DlState
    object Done : DlState
    data class Error(val msg: String) : DlState
}

class Downloader(ctx: Context) {
    private val dir = File(ctx.filesDir, "models").apply { mkdirs() }   // app-private files/models/
    val states = mutableStateMapOf<String, DlState>()
    private val jobs = mutableMapOf<String, Job>()
    init { Catalog.all.forEach { states[it.id] = if (file(it).exists()) DlState.Done else DlState.Idle } }

    fun file(s: ModelSpec) = File(dir, s.file)
    fun path(s: ModelSpec) = file(s).absolutePath
    fun ready(s: ModelSpec) = states[s.id] == DlState.Done

    fun start(scope: CoroutineScope, s: ModelSpec) {
        if (jobs[s.id]?.isActive == true) return
        jobs[s.id] = scope.launch(Dispatchers.IO) {
            try { download(s); states[s.id] = DlState.Done }
            catch (e: CancellationException) { states[s.id] = DlState.Idle; throw e }
            catch (e: Exception) { states[s.id] = DlState.Error(e.message ?: "Download failed") }
        }
    }
    fun cancel(s: ModelSpec) { jobs[s.id]?.cancel() }       // partial .download file is kept so Retry resumes
    fun delete(s: ModelSpec) {
        jobs[s.id]?.cancel(); file(s).delete(); File(dir, s.file + ".download").delete(); states[s.id] = DlState.Idle
    }

    private suspend fun download(s: ModelSpec) {
        if (s.url.isBlank() || s.sha256.isBlank()) throw IOException("URL / SHA-256 not configured for this model (see Models.kt).")
        val tmp = File(dir, s.file + ".download")
        var have = tmp.length()
        val c = URL(s.url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000; c.readTimeout = 30000
        if (have > 0) c.setRequestProperty("Range", "bytes=$have-")
        val code = c.responseCode
        if (code == 416) { tmp.delete(); throw IOException("Resume rejected by server. Press Retry.") }
        if (code != 200 && code != 206) throw IOException("Server returned HTTP $code")
        if (code == 200) have = 0
        val total = have + c.contentLengthLong
        var done = have
        c.inputStream.use { inp ->
            FileOutputStream(tmp, code == 206).use { out ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val n = inp.read(buf); if (n < 0) break
                    out.write(buf, 0, n); done += n
                    states[s.id] = DlState.Progress(done, total)
                }
            }
        }
        states[s.id] = DlState.Verifying
        val md = MessageDigest.getInstance("SHA-256")
        tmp.inputStream().use { i ->
            val b = ByteArray(1 shl 16)
            while (true) { currentCoroutineContext().ensureActive(); val n = i.read(b); if (n < 0) break; md.update(b, 0, n) }
        }
        val hex = md.digest().joinToString("") { "%02x".format(it) }
        if (!hex.equals(s.sha256, true)) { tmp.delete(); throw IOException("SHA-256 mismatch. File deleted; press Retry.") }
        Files.move(tmp.toPath(), file(s).toPath(), StandardCopyOption.ATOMIC_MOVE)
    }
}
