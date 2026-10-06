package com.lofi4a.core

data class ModelSpec(val id: String, val label: String, val file: String, val url: String, val sha256: String)

/**
 * CONFIG: url + sha256 are intentionally BLANK (never invented). Fill them from each model's official
 * download page. Downloads refuse to start until both are set.
 *  - Gemma 3 1B Instruct GGUF Q4_K_M  - LFM2-VL 450M GGUF + matching mmproj GGUF  - Whisper Base (ggml-base.bin)
 */
object Catalog {
    val gemma = ModelSpec("gemma", "Text: Gemma 3 1B Instruct (Q4_K_M)", "gemma-3-1b-it-Q4_K_M.gguf", "", "")
    val lfm = ModelSpec("lfm", "Vision: LFM2-VL 450M", "lfm2-vl-450m.gguf", "", "")
    val mmproj = ModelSpec("mmproj", "Vision projector (mmproj)", "lfm2-vl-450m-mmproj.gguf", "", "")
    val whisper = ModelSpec("whisper", "Speech: Whisper Base", "ggml-base.bin", "", "")
    val all = listOf(gemma, lfm, mmproj, whisper)
}
