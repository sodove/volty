// Thin JNI bridge: one DenoiseState per handle; only the speech probability is used by the app,
// the denoised frame is written to a caller-owned scratch buffer and ignored.
#include <jni.h>
#include <stdint.h>
#include "rnnoise.h"

JNIEXPORT jlong JNICALL
Java_ru_sodovaya_volty_data_voice_RnnoiseVad_nativeCreate(JNIEnv *env, jclass cls) {
    return (jlong)(intptr_t)rnnoise_create(NULL);
}

JNIEXPORT jint JNICALL
Java_ru_sodovaya_volty_data_voice_RnnoiseVad_nativeFrameSize(JNIEnv *env, jclass cls) {
    return rnnoise_get_frame_size();
}

// `in` holds one frame of samples in the int16 range (RNNoise's convention), `out` is scratch.
JNIEXPORT jfloat JNICALL
Java_ru_sodovaya_volty_data_voice_RnnoiseVad_nativeProcess(JNIEnv *env, jclass cls, jlong handle,
                                                            jfloatArray in, jfloatArray out) {
    DenoiseState *st = (DenoiseState *)(intptr_t)handle;
    if (st == NULL) return 0.0f;
    jfloat *pin = (*env)->GetPrimitiveArrayCritical(env, in, NULL);
    jfloat *pout = (*env)->GetPrimitiveArrayCritical(env, out, NULL);
    float p = 0.0f;
    if (pin != NULL && pout != NULL) p = rnnoise_process_frame(st, pout, pin);
    if (pout != NULL) (*env)->ReleasePrimitiveArrayCritical(env, out, pout, 0);
    if (pin != NULL) (*env)->ReleasePrimitiveArrayCritical(env, in, pin, JNI_ABORT);
    return p;
}

JNIEXPORT void JNICALL
Java_ru_sodovaya_volty_data_voice_RnnoiseVad_nativeDestroy(JNIEnv *env, jclass cls, jlong handle) {
    DenoiseState *st = (DenoiseState *)(intptr_t)handle;
    if (st != NULL) rnnoise_destroy(st);
}
