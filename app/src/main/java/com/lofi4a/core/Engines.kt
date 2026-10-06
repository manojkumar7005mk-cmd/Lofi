package com.lofi4a.core

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread

fun interface TokenCb { fun onToken(b: ByteArray): Boolean }

object Native {
    init { System.loadLibrary("lofi_llm"); System.loadLibrary("lofi_stt") }
    @JvmStatic external fun llmLoad(model: String, mmproj: String?, nCtx: Int): Long
    @JvmStatic external fun llmFree(h: Long)
    @JvmStatic external fun llmGenerate(h: Long, roles: Array<String>, texts: Array<String>, rgb: ByteArray?, w: Int, ht: Int, cb: TokenCb): Int
    @JvmStatic external fun sttLoad(path: String): Long
    @JvmStatic external fun sttFree(h: Long)
    @JvmStatic external fun sttRun(h: Long, pcm: FloatArray): ByteArray?
}

const val SYSTEM_PROMPT = "You are LoFi-4A Core, a private offline assistant created by Manoj Kumar, a student. " +
    "Introduce yourself as LoFi-4A Core. Never state the names of the underlying models."

/** Only ONE model is ever resident; switching unloads the previous one and frees native memory. */
object Engines {
    private val mutex = Mutex()
    private var kind: String? = null
    private var handle = 0L

    private fun unload() {
        if (handle != 0L) Native.llmFree(handle)
        handle = 0; kind = null
    }
    suspend fun shutdown() = mutex.withLock { unload() }

    private fun need(dl: Downloader, vararg s: ModelSpec) =
        s.firstOrNull { !dl.ready(it) }?.let { error("Download \"${it.label}\" in the Models tab first.") }

    suspend fun chat(dl: Downloader, hist: List<Pair<String, String>>, img: Bitmap?, onTok: (String) -> Boolean) {
        val job = currentCoroutineContext()[Job]!!
        mutex.withLock { withContext(Dispatchers.IO) {
            val want = if (img != null) "vlm" else "llm"
            if (kind != want) {
                unload()
                val h = if (want == "vlm") { need(dl, Catalog.lfm, Catalog.mmproj); Native.llmLoad(dl.path(Catalog.lfm), dl.path(Catalog.mmproj), 4096) }
                        else { need(dl, Catalog.gemma); Native.llmLoad(dl.path(Catalog.gemma), null, 4096) }
                if (h == 0L) error("Could not load the model. Delete and re-download it in Models.")
                handle = h; kind = want
            }
            val roles = (listOf("system") + hist.map { it.first }).toTypedArray()
            val texts = (listOf(SYSTEM_PROMPT) + hist.map { it.second }).toTypedArray()
            var rgb: ByteArray? = null; var w = 0; var ht = 0
            if (img != null) {
                w = img.width; ht = img.height
                val px = IntArray(w * ht); img.getPixels(px, 0, w, 0, 0, w, ht)
                rgb = ByteArray(w * ht * 3)
                for (i in px.indices) { rgb[i * 3] = (px[i] shr 16).toByte(); rgb[i * 3 + 1] = (px[i] shr 8).toByte(); rgb[i * 3 + 2] = px[i].toByte() }
            }
            val rc = Native.llmGenerate(handle, roles, texts, rgb, w, ht) { b -> onTok(String(b, Charsets.UTF_8)) && job.isActive }
            if (rc < 0) error(if (rc == -5) "Conversation too long. Start a new chat." else "Generation failed ($rc).")
        } }
    }

    suspend fun transcribe(dl: Downloader, pcm: FloatArray): String = mutex.withLock { withContext(Dispatchers.IO) {
        need(dl, Catalog.whisper)
        unload()
        val h = Native.sttLoad(dl.path(Catalog.whisper))
        if (h == 0L) error("Could not load the speech model.")
        try { String(Native.sttRun(h, pcm) ?: error("Transcription failed."), Charsets.UTF_8).trim() }
        finally { Native.sttFree(h) }
    } }
}

class Recorder {
    @Volatile private var run = false
    private var th: Thread? = null
    private val pcm = ByteArrayOutputStream()

    @SuppressLint("MissingPermission")
    fun start() {
        pcm.reset()
        val n = maxOf(AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT), 4096)
        val r = AudioRecord(MediaRecorder.AudioSource.MIC, 16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, n)
        if (r.state != AudioRecord.STATE_INITIALIZED) { r.release(); error("Microphone unavailable.") }
        r.startRecording(); run = true
        th = thread {
            val b = ByteArray(n)
            while (run && pcm.size() < 16000 * 2 * 60) { val k = r.read(b, 0, b.size); if (k > 0) pcm.write(b, 0, k) else if (k < 0) break }
            r.stop(); r.release()
        }
    }
    fun stop(): FloatArray {
        run = false; th?.join()
        val sb = ByteBuffer.wrap(pcm.toByteArray()).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        return FloatArray(sb.remaining()) { sb.get(it) / 32768f }
    }
}
