package com.lofi4a.core

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(b: Bundle?) { super.onCreate(b); setContent { MaterialTheme { App() } } }
}

@Composable fun App(vm: ChatVM = viewModel()) {
    var tab by remember { mutableStateOf(0) }
    fun ok(vararg s: ModelSpec) = if (s.all { vm.dl.ready(it) }) "✓" else "✗"
    Scaffold(topBar = { Column(Modifier.statusBarsPadding()) {
        TabRow(tab) { listOf("Chat", "Models", "About").forEachIndexed { i, t -> Tab(tab == i, { tab = i }, text = { Text(t) }) } }
        Text("${if (vm.online) "Online (not required)" else "Offline"} · Text ${ok(Catalog.gemma)} · Vision ${ok(Catalog.lfm, Catalog.mmproj)} · Speech ${ok(Catalog.whisper)}",
            Modifier.padding(8.dp), style = MaterialTheme.typography.labelMedium)
    } }) { p -> Box(Modifier.padding(p)) { when (tab) { 0 -> ChatScreen(vm); 1 -> ModelsScreen(vm); else -> AboutScreen() } } }
}

@Composable fun ChatScreen(vm: ChatVM) {
    val ctx = LocalContext.current
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { it?.let(vm::attach) }
    val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { if (it) vm.toggleMic() }
    val list = rememberLazyListState()
    LaunchedEffect(vm.msgs.size, vm.msgs.lastOrNull()?.text) { if (vm.msgs.isNotEmpty()) list.scrollToItem(vm.msgs.lastIndex) }
    Column(Modifier.fillMaxSize().imePadding()) {
        if (vm.msgs.isEmpty()) Text("I’m LoFi-4A Core.", Modifier.weight(1f).padding(16.dp), style = MaterialTheme.typography.titleMedium)
        else LazyColumn(Modifier.weight(1f).padding(horizontal = 12.dp), state = list, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(vm.msgs.size) { i ->
                val m = vm.msgs[i]; val me = m.role == "user"
                Box(Modifier.fillMaxWidth(), contentAlignment = if (me) Alignment.CenterEnd else Alignment.CenterStart) {
                    Surface(color = if (me) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(16.dp)) {
                        Text((if (m.img) "🖼 " else "") + m.text.ifEmpty { "…" }, Modifier.padding(10.dp))
                    }
                }
            }
        }
        vm.note?.let { Text(it, Modifier.padding(8.dp), color = MaterialTheme.colorScheme.error) }
        vm.image?.let { Row(verticalAlignment = Alignment.CenterVertically) { Image(it.asImageBitmap(), null, Modifier.size(56.dp).padding(4.dp)); TextButton({ vm.image = null }) { Text("Remove") } } }
        Row(Modifier.padding(8.dp).navigationBarsPadding(), verticalAlignment = Alignment.CenterVertically) {
            TextButton({ pick.launch("image/*") }, enabled = !vm.busy) { Text("📎") }
            TextButton({
                if (ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) vm.toggleMic()
                else perm.launch(Manifest.permission.RECORD_AUDIO)
            }, enabled = !vm.busy) { Text(if (vm.recording) "⏹" else "🎤") }
            OutlinedTextField(vm.input, { vm.input = it }, Modifier.weight(1f), placeholder = { Text("Message") }, maxLines = 4)
            Spacer(Modifier.width(6.dp))
            if (vm.busy) Button(vm::stop) { Text("Stop") } else Button(vm::send, enabled = !vm.recording) { Text("Send") }
        }
    }
}

@Composable fun ModelsScreen(vm: ChatVM) {
    LazyColumn(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        items(Catalog.all) { s ->
            val st = vm.dl.states[s.id] ?: DlState.Idle
            Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) {
                Text(s.label, fontWeight = FontWeight.Bold)
                Text(when (st) {
                    DlState.Idle -> "Not downloaded"; DlState.Verifying -> "Verifying SHA-256…"; DlState.Done -> "Ready (offline)"
                    is DlState.Progress -> "Downloading ${if (st.total > 0) st.done * 100 / st.total else 0}%"
                    is DlState.Error -> "Error: ${st.msg}"
                })
                if (st is DlState.Progress && st.total > 0) LinearProgressIndicator(progress = { st.done.toFloat() / st.total }, modifier = Modifier.fillMaxWidth())
                Row {
                    when (st) {
                        is DlState.Progress, DlState.Verifying -> Button({ vm.dl.cancel(s) }) { Text("Cancel") }
                        DlState.Done -> { OutlinedButton({ vm.dl.delete(s) }) { Text("Delete") }; Spacer(Modifier.width(8.dp))
                            Button({ vm.dl.delete(s); vm.download(s) }) { Text("Redownload") } }
                        else -> Button({ vm.download(s) }) { Text(if (st is DlState.Error) "Retry" else "Download") }
                    }
                }
            } }
        }
    }
}

@Composable fun AboutScreen() {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("LoFi-4A Core", style = MaterialTheme.typography.headlineSmall)
        Text("Designed and developed by Manoj Kumar, a student.")
        Text("Backend Models & Technologies", fontWeight = FontWeight.Bold)
        Text("• Text: Gemma 3 1B Instruct GGUF (Q4_K_M)\n• Vision: LFM2-VL 450M GGUF + mmproj\n• Speech: Whisper Base\n• Runtimes: llama.cpp (+ mtmd), whisper.cpp, ggml\n• Kotlin, Jetpack Compose, JNI/C++")
        Text("Licenses & Attributions", fontWeight = FontWeight.Bold)
        Text("• Gemma: Gemma Terms of Use & Prohibited Use Policy (ai.google.dev/gemma/terms), © Google\n• LFM2-VL: LFM Open License v1.0, © Liquid AI\n• Whisper: MIT, © OpenAI\n• llama.cpp, whisper.cpp, ggml: MIT, © ggml authors\n• AndroidX / Jetpack Compose / Kotlin: Apache 2.0")
    }
}
