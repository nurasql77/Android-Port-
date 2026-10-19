#include <jni.h>

JNIEXPORT jstring JNICALL
Java_net_openbgi_android_MainActivity_nativeStatus(JNIEnv *env, jclass clazz) {
    (void)clazz;
    return (*env)->NewStringUTF(env,
        "OpenBGI Android native shell\n\n"
        "NDK/ARM64 layer is working.\n"
        "Next: integrate the OpenBGI C engine and implement src/os/android/."
    );
}
