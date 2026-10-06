package com.lofi4a.core

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.ConnectivityManager
import android.net.Network
import android.net.Uri
import androidx.compose.runtime.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*

data class Msg(val role: String, val text: String, val img: Boolean = false)

class ChatVM(app: Application) : AndroidViewModel(app) {
    val dl = Downloader(app)
    val msgs = mutableStateListOf<Msg>()
    var input by mutableStateOf(""); var image by mutableStateOf<Bitmap?>(null)
    var busy by mutableStateOf(false); var recording by mutableStateOf(false)
    var note by mutableStateOf<String?>(null); var online by mutableStateOf(true)
    private var job: Job? = null
    private val rec = Recorder()

    init {
        val cm = app.getSystemService(ConnectivityManager::class.java)
        online = cm.activeNetwork != null
        cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(n: Network) { online = true }
            override fun onLost(n: Network) { online = false }
        })
    }

    fun download(s: ModelSpec) = dl.start(viewModelScope, s)

    fun send() {
        val t = input.trim(); if (t.isEmpty() || busy) return
        val img = image; input = ""; image = null; note = null
        msgs += Msg("user", t, img != null)
        val history = msgs.map { it.role to it.text }
        msgs += Msg("assistant", ""); busy = true
        job = viewModelScope.launch {
            val sb = StringBuilder()
            try { Engines.chat(dl, history, img) { sb.append(it); msgs[msgs.lastIndex] = Msg("assistant", sb.toString()); true } }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { note = e.message }
            finally { if (sb.isEmpty()) msgs[msgs.lastIndex] = Msg("assistant", "(no response)"); busy = false }
        }
    }
    fun stop() { job?.cancel() }

    fun toggleMic() {
        if (busy) return
        if (!recording) { try { rec.start(); recording = true } catch (e: Exception) { note = e.message }; return }
        recording = false; val pcm = rec.stop(); busy = true
        job = viewModelScope.launch {
            try { input = (input + " " + Engines.transcribe(dl, pcm)).trim() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { note = e.message }
            finally { busy = false }
        }
    }

    fun attach(u: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val bm = getApplication<Application>().contentResolver.openInputStream(u)!!.use { BitmapFactory.decodeStream(it) }
                val sc = 768f / maxOf(bm.width, bm.height)
                image = if (sc < 1f) Bitmap.createScaledBitmap(bm, (bm.width * sc).toInt(), (bm.height * sc).toInt(), true) else bm
            } catch (e: Exception) { note = "Could not read that image." }
        }
    }

    override fun onCleared() { GlobalScope.launch { Engines.shutdown() } }
}
