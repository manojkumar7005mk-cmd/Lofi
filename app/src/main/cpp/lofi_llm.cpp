// Uses llama.cpp (llama.h) and mtmd (mtmd.h / mtmd-helper.h) public C APIs.
#include <jni.h>
#include <string>
#include <vector>
#include "llama.h"
#include "mtmd.h"
#include "mtmd-helper.h"

struct H { llama_model* m = nullptr; llama_context* c = nullptr; mtmd_context* v = nullptr; };
static void release(H* h) { if (!h) return; if (h->v) mtmd_free(h->v); if (h->c) llama_free(h->c); if (h->m) llama_model_free(h->m); delete h; }
static std::string S(JNIEnv* e, jstring s) { const char* p = e->GetStringUTFChars(s, nullptr); std::string r(p); e->ReleaseStringUTFChars(s, p); return r; }
// longest prefix that does not end in a partial UTF-8 sequence
static size_t utf8_prefix(const std::string& s) {
    size_t n = s.size();
    for (size_t k = 1; k <= 3 && k <= n; k++) {
        unsigned char ch = s[n - k];
        if ((ch & 0xC0) == 0x80) continue;
        size_t need = ch >= 0xF0 ? 4 : ch >= 0xE0 ? 3 : ch >= 0xC0 ? 2 : 1;
        return need > k ? n - k : n;
    }
    return n;
}

extern "C" JNIEXPORT jlong JNICALL Java_com_lofi4a_core_Native_llmLoad(JNIEnv* e, jclass, jstring model, jstring mmproj, jint nctx) {
    static bool inited = false; if (!inited) { llama_backend_init(); inited = true; }
    H* h = new H();
    llama_model_params mp = llama_model_default_params(); mp.n_gpu_layers = 0;
    h->m = llama_model_load_from_file(S(e, model).c_str(), mp);
    if (!h->m) { release(h); return 0; }
    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = nctx; cp.n_batch = 512; cp.n_threads = 4; cp.n_threads_batch = 4;
    h->c = llama_init_from_model(h->m, cp);
    if (!h->c) { release(h); return 0; }
    if (mmproj) {
        mtmd_context_params vp = mtmd_context_params_default(); vp.use_gpu = false; vp.n_threads = 4;
        h->v = mtmd_init_from_file(S(e, mmproj).c_str(), h->m, vp);
        if (!h->v) { release(h); return 0; }
    }
    return (jlong)h;
}

extern "C" JNIEXPORT void JNICALL Java_com_lofi4a_core_Native_llmFree(JNIEnv*, jclass, jlong hp) { release((H*)hp); }

// returns 0 ok, <0 error. cb.onToken(byte[]) returns false to stop.
extern "C" JNIEXPORT jint JNICALL Java_com_lofi4a_core_Native_llmGenerate(JNIEnv* e, jclass, jlong hp, jobjectArray roles, jobjectArray texts,
                                                                          jbyteArray rgb, jint w, jint ht, jobject cb) {
    H* h = (H*)hp; int n = e->GetArrayLength(roles);
    std::vector<std::string> R, T;
    for (int i = 0; i < n; i++) {
        jstring a = (jstring)e->GetObjectArrayElement(roles, i), b = (jstring)e->GetObjectArrayElement(texts, i);
        R.push_back(S(e, a)); T.push_back(S(e, b)); e->DeleteLocalRef(a); e->DeleteLocalRef(b);
    }
    bool vis = rgb != nullptr && h->v != nullptr;
    if (vis) T[n - 1] = std::string(mtmd_default_marker()) + "\n" + T[n - 1];
    std::vector<llama_chat_message> msgs;
    for (int i = 0; i < n; i++) msgs.push_back({R[i].c_str(), T[i].c_str()});
    const char* tmpl = llama_model_chat_template(h->m, nullptr);
    std::vector<char> buf(8192);
    int len = llama_chat_apply_template(tmpl, msgs.data(), msgs.size(), true, buf.data(), buf.size());
    if (len > (int)buf.size()) { buf.resize(len); len = llama_chat_apply_template(tmpl, msgs.data(), msgs.size(), true, buf.data(), buf.size()); }
    if (len < 0) return -1;
    std::string prompt(buf.data(), len);

    llama_memory_clear(llama_get_memory(h->c), true);
    const llama_vocab* vocab = llama_model_get_vocab(h->m);
    llama_pos npast = 0;
    if (vis) {
        jbyte* px = e->GetByteArrayElements(rgb, nullptr);
        mtmd_bitmap* bmp = mtmd_bitmap_init(w, ht, (const unsigned char*)px);
        mtmd_input_chunks* ch = mtmd_input_chunks_init();
        mtmd_input_text it{prompt.c_str(), true, true};
        const mtmd_bitmap* bms[1] = {bmp};
        int rc = mtmd_tokenize(h->v, ch, &it, bms, 1);
        if (rc == 0) rc = mtmd_helper_eval_chunks(h->v, h->c, ch, 0, 0, 512, true, &npast);
        mtmd_input_chunks_free(ch); mtmd_bitmap_free(bmp); e->ReleaseByteArrayElements(rgb, px, JNI_ABORT);
        if (rc != 0) return -3;
    } else {
        int nt = -llama_tokenize(vocab, prompt.c_str(), prompt.size(), nullptr, 0, true, true);
        std::vector<llama_token> tk(nt);
        if (llama_tokenize(vocab, prompt.c_str(), prompt.size(), tk.data(), nt, true, true) < 0) return -4;
        if (nt >= (int)llama_n_ctx(h->c) - 8) return -5;  // prompt too long
        for (int i = 0; i < nt; i += 512) {
            int cnt = std::min(512, nt - i);
            if (llama_decode(h->c, llama_batch_get_one(tk.data() + i, cnt)) != 0) return -2;
            npast += cnt;
        }
    }
    llama_sampler* s = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(s, llama_sampler_init_top_k(40));
    llama_sampler_chain_add(s, llama_sampler_init_top_p(0.95f, 1));
    llama_sampler_chain_add(s, llama_sampler_init_temp(0.7f));
    llama_sampler_chain_add(s, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));
    jmethodID mid = e->GetMethodID(e->GetObjectClass(cb), "onToken", "([B)Z");
    std::string pend; int rc = 0; const int nctx = llama_n_ctx(h->c);
    for (int i = 0; i < 1024 && npast < nctx - 1; i++) {
        llama_token t = llama_sampler_sample(s, h->c, -1);
        if (llama_vocab_is_eog(vocab, t)) break;
        char pb[256]; int pl = llama_token_to_piece(vocab, t, pb, sizeof pb, 0, true);
        if (pl > 0) pend.append(pb, pl);
        size_t k = utf8_prefix(pend);
        if (k) {
            jbyteArray a = e->NewByteArray(k); e->SetByteArrayRegion(a, 0, k, (const jbyte*)pend.data());
            jboolean go = e->CallBooleanMethod(cb, mid, a); e->DeleteLocalRef(a); pend.erase(0, k);
            if (e->ExceptionCheck()) { e->ExceptionClear(); break; }
            if (!go) break;
        }
        if (llama_decode(h->c, llama_batch_get_one(&t, 1)) != 0) { rc = -2; break; }
        npast++;
    }
    llama_sampler_free(s);
    return rc;
}
