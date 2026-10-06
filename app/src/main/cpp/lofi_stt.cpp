// Uses whisper.cpp public C API (whisper.h).
#include <jni.h>
#include <string>
#include "whisper.h"

extern "C" JNIEXPORT jlong JNICALL Java_com_lofi4a_core_Native_sttLoad(JNIEnv* e, jclass, jstring path) {
    const char* p = e->GetStringUTFChars(path, nullptr);
    whisper_context_params cp = whisper_context_default_params(); cp.use_gpu = false;
    whisper_context* c = whisper_init_from_file_with_params(p, cp);
    e->ReleaseStringUTFChars(path, p);
    return (jlong)c;
}
extern "C" JNIEXPORT void JNICALL Java_com_lofi4a_core_Native_sttFree(JNIEnv*, jclass, jlong h) { if (h) whisper_free((whisper_context*)h); }
// returns UTF-8 bytes (null on failure)
extern "C" JNIEXPORT jbyteArray JNICALL Java_com_lofi4a_core_Native_sttRun(JNIEnv* e, jclass, jlong h, jfloatArray pcm) {
    whisper_context* c = (whisper_context*)h;
    whisper_full_params wp = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    wp.n_threads = 4; wp.language = "auto"; wp.translate = false; wp.no_context = true;
    wp.print_progress = wp.print_realtime = wp.print_special = wp.print_timestamps = false;
    jfloat* d = e->GetFloatArrayElements(pcm, nullptr);
    int rc = whisper_full(c, wp, d, e->GetArrayLength(pcm));
    e->ReleaseFloatArrayElements(pcm, d, JNI_ABORT);
    if (rc != 0) return nullptr;
    std::string out;
    for (int i = 0; i < whisper_full_n_segments(c); i++) out += whisper_full_get_segment_text(c, i);
    jbyteArray a = e->NewByteArray(out.size()); e->SetByteArrayRegion(a, 0, out.size(), (const jbyte*)out.data());
    return a;
}
