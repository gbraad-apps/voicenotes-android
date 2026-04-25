#include <jni.h>
#include <android/log.h>
#include <stdlib.h>
#include <sys/sysinfo.h>
#include <string.h>
#include <stdbool.h>
#include <pthread.h>
#include "whisper.h"

#define TAG "WhisperJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  TAG, __VA_ARGS__)
#define UNUSED(x) (void)(x)

static bool g_should_abort = false;
static pthread_mutex_t g_abort_mutex = PTHREAD_MUTEX_INITIALIZER;

static JavaVM *g_jvm = NULL;
static jobject g_callback = NULL;
static jmethodID g_onNewSegmentMethod = NULL;
static jmethodID g_onProgressMethod = NULL;
static jmethodID g_onCompleteMethod = NULL;

// ── Input-stream loader ──────────────────────────────────────────────────────

struct input_stream_context {
    size_t offset;
    JNIEnv *env;
    jobject input_stream;
    jmethodID mid_available;
    jmethodID mid_read;
};

static size_t input_stream_read(void *ctx, void *output, size_t read_size) {
    struct input_stream_context *is = (struct input_stream_context *)ctx;
    jint avail = (*is->env)->CallIntMethod(is->env, is->input_stream, is->mid_available);
    jint to_copy = (jint)(read_size < (size_t)avail ? read_size : (size_t)avail);
    jbyteArray arr = (*is->env)->NewByteArray(is->env, to_copy);
    jint n_read = (*is->env)->CallIntMethod(is->env, is->input_stream, is->mid_read, arr, 0, to_copy);
    jbyte *bytes = (*is->env)->GetByteArrayElements(is->env, arr, NULL);
    memcpy(output, bytes, to_copy);
    (*is->env)->ReleaseByteArrayElements(is->env, arr, bytes, JNI_ABORT);
    (*is->env)->DeleteLocalRef(is->env, arr);
    is->offset += to_copy;
    return to_copy;
}

static bool input_stream_eof(void *ctx) {
    struct input_stream_context *is = (struct input_stream_context *)ctx;
    return (*is->env)->CallIntMethod(is->env, is->input_stream, is->mid_available) <= 0;
}

static void input_stream_close(void *ctx) { UNUSED(ctx); }

// ── Callbacks ────────────────────────────────────────────────────────────────

static void new_segment_callback(struct whisper_context *ctx, struct whisper_state *state,
                                  int n_new, void *user_data) {
    UNUSED(state); UNUSED(user_data);
    JNIEnv *env;
    (*g_jvm)->AttachCurrentThread(g_jvm, &env, NULL);
    int total = whisper_full_n_segments(ctx);
    for (int i = n_new; i > 0; i--) {
        int seg = total - i;
        const char *text = whisper_full_get_segment_text(ctx, seg);
        int64_t t0 = whisper_full_get_segment_t0(ctx, seg);
        int64_t t1 = whisper_full_get_segment_t1(ctx, seg);
        jstring jtext = (*env)->NewStringUTF(env, text);
        float no_speech_prob = whisper_full_get_segment_no_speech_prob(ctx, seg);
        (*env)->CallVoidMethod(env, g_callback, g_onNewSegmentMethod,
                (jlong)t0, (jlong)t1, jtext, (jfloat)no_speech_prob);
        (*env)->DeleteLocalRef(env, jtext);
    }
}

static void progress_callback(struct whisper_context *ctx, struct whisper_state *state,
                               int progress, void *user_data) {
    UNUSED(ctx); UNUSED(state); UNUSED(user_data);
    JNIEnv *env;
    (*g_jvm)->AttachCurrentThread(g_jvm, &env, NULL);
    (*env)->CallVoidMethod(env, g_callback, g_onProgressMethod, (jint)progress);
}

static bool abort_callback(void *user_data) {
    UNUSED(user_data);
    bool should_abort;
    pthread_mutex_lock(&g_abort_mutex);
    should_abort = g_should_abort;
    pthread_mutex_unlock(&g_abort_mutex);
    return should_abort;
}

// ── JNI exports ─────────────────────────────────────────────────────────────

JNIEXPORT jlong JNICALL
Java_nl_gbraad_transcribe_WhisperLib_initContextFromInputStream(
        JNIEnv *env, jclass clazz, jobject input_stream) {
    UNUSED(clazz);
    struct input_stream_context is_ctx = {0};
    is_ctx.env = env;
    is_ctx.input_stream = input_stream;
    jclass cls = (*env)->GetObjectClass(env, input_stream);
    is_ctx.mid_available = (*env)->GetMethodID(env, cls, "available", "()I");
    is_ctx.mid_read = (*env)->GetMethodID(env, cls, "read", "([BII)I");

    struct whisper_model_loader loader = {
        .context = &is_ctx,
        .read    = input_stream_read,
        .eof     = input_stream_eof,
        .close   = input_stream_close,
    };
    struct whisper_context_params params = whisper_context_default_params();
    params.use_gpu = false;
    return (jlong)whisper_init_with_params(&loader, params);
}

JNIEXPORT jlong JNICALL
Java_nl_gbraad_transcribe_WhisperLib_initContext(
        JNIEnv *env, jclass clazz, jstring model_path_str) {
    UNUSED(clazz);
    const char *path = (*env)->GetStringUTFChars(env, model_path_str, NULL);
    LOGI("initContext: loading model from %s", path);
    struct whisper_context_params params = whisper_context_default_params();
    params.use_gpu = false;
    struct whisper_context *ctx = whisper_init_from_file_with_params(path, params);
    LOGI("initContext: done, ctx=%p", (void*)ctx);
    (*env)->ReleaseStringUTFChars(env, model_path_str, path);
    return (jlong)ctx;
}

JNIEXPORT void JNICALL
Java_nl_gbraad_transcribe_WhisperLib_freeContext(
        JNIEnv *env, jclass clazz, jlong context_ptr) {
    UNUSED(env); UNUSED(clazz);
    whisper_free((struct whisper_context *)context_ptr);
}

JNIEXPORT void JNICALL
Java_nl_gbraad_transcribe_WhisperLib_stopTranscription(JNIEnv *env, jclass clazz) {
    UNUSED(env); UNUSED(clazz);
    pthread_mutex_lock(&g_abort_mutex);
    g_should_abort = true;
    pthread_mutex_unlock(&g_abort_mutex);
}

JNIEXPORT void JNICALL
Java_nl_gbraad_transcribe_WhisperLib_resetAbort(JNIEnv *env, jclass clazz) {
    UNUSED(env); UNUSED(clazz);
    pthread_mutex_lock(&g_abort_mutex);
    g_should_abort = false;
    pthread_mutex_unlock(&g_abort_mutex);
}

JNIEXPORT void JNICALL
Java_nl_gbraad_transcribe_WhisperLib_fullTranscribe(
        JNIEnv *env, jclass clazz, jlong context_ptr, jint num_threads,
        jfloatArray audio_data, jstring language, jobject callback) {
    UNUSED(clazz);

    // Note: g_should_abort is reset only via stopTranscription()+resetAbort() calls,
    // not here — so a stopTranscription() call persists across chunks.
    if (g_jvm == NULL) (*env)->GetJavaVM(env, &g_jvm);
    if (g_callback != NULL) (*env)->DeleteGlobalRef(env, g_callback);
    g_callback = (*env)->NewGlobalRef(env, callback);

    jclass cb_cls = (*env)->GetObjectClass(env, g_callback);
    g_onNewSegmentMethod = (*env)->GetMethodID(env, cb_cls, "onNewSegment", "(JJLjava/lang/String;F)V");
    g_onProgressMethod   = (*env)->GetMethodID(env, cb_cls, "onProgress",   "(I)V");
    g_onCompleteMethod   = (*env)->GetMethodID(env, cb_cls, "onComplete",   "()V");

    struct whisper_context *ctx = (struct whisper_context *)context_ptr;
    jfloat *samples = (*env)->GetFloatArrayElements(env, audio_data, NULL);
    jsize n_samples = (*env)->GetArrayLength(env, audio_data);

    const char *lang = "auto";
    if (language != NULL) lang = (*env)->GetStringUTFChars(env, language, NULL);

    struct whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_BEAM_SEARCH);
    params.print_realtime   = false;
    params.print_progress   = false;
    params.print_timestamps = false;
    params.print_special    = false;
    params.translate        = false;
    params.language         = lang;
    params.n_threads        = num_threads;
    params.no_context       = true;
    params.single_segment   = false;
    params.beam_search.beam_size = 5;
    params.new_segment_callback           = new_segment_callback;
    params.new_segment_callback_user_data = NULL;
    params.progress_callback              = progress_callback;
    params.progress_callback_user_data    = NULL;
    // NOTE: abort_callback is intentionally NOT set.
    // In ggml v1.7.x, returning true from abort_callback triggers ggml_abort()
    // which calls abort() and kills the entire process. Stopping is handled at
    // the Java level via whisperExecutor.shutdownNow() instead.
    params.abort_callback                 = NULL;
    params.abort_callback_user_data       = NULL;

    whisper_reset_timings(ctx);
    LOGI("Starting transcription (lang=%s, threads=%d, samples=%d)", lang, num_threads, n_samples);

    int result = whisper_full(ctx, params, samples, n_samples);

    if (language != NULL) (*env)->ReleaseStringUTFChars(env, language, lang);
    (*env)->ReleaseFloatArrayElements(env, audio_data, samples, JNI_ABORT);

    if (result == 0) {
        (*env)->CallVoidMethod(env, g_callback, g_onCompleteMethod);
    } else {
        LOGW("whisper_full failed (code %d)", result);
    }

    (*env)->DeleteGlobalRef(env, g_callback);
    g_callback = NULL;
}

JNIEXPORT jint JNICALL
Java_nl_gbraad_transcribe_WhisperLib_getTextSegmentCount(
        JNIEnv *env, jclass clazz, jlong context_ptr) {
    UNUSED(env); UNUSED(clazz);
    return whisper_full_n_segments((struct whisper_context *)context_ptr);
}

JNIEXPORT jstring JNICALL
Java_nl_gbraad_transcribe_WhisperLib_getTextSegment(
        JNIEnv *env, jclass clazz, jlong context_ptr, jint index) {
    UNUSED(clazz);
    return (*env)->NewStringUTF(env,
        whisper_full_get_segment_text((struct whisper_context *)context_ptr, index));
}

JNIEXPORT jstring JNICALL
Java_nl_gbraad_transcribe_WhisperLib_getSystemInfo(JNIEnv *env, jclass clazz) {
    UNUSED(clazz);
    return (*env)->NewStringUTF(env, whisper_print_system_info());
}
