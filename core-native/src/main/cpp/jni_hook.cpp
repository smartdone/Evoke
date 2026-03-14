#include <jni.h>

extern "C" bool vm_native_init_gum();
extern "C" bool vm_native_install_hooks(const char *host_dir, const char *pkg, int user_id);
extern "C" void vm_native_remove_hooks();
extern "C" void vm_native_set_current_vapp(const char *pkg, int user_id);

extern "C" JNIEXPORT jboolean JNICALL
Java_com_smartdone_vm_core_nativeengine_NativeEngine_nativeInitGum(JNIEnv *, jobject) {
    return vm_native_init_gum();
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_smartdone_vm_core_nativeengine_NativeEngine_nativeInstallIoHooks(
    JNIEnv *env,
    jobject,
    jstring host_data_dir,
    jstring vapp_pkg_name,
    jint user_id
) {
    const char *host_dir = env->GetStringUTFChars(host_data_dir, nullptr);
    const char *pkg = env->GetStringUTFChars(vapp_pkg_name, nullptr);
    const bool result = vm_native_install_hooks(host_dir, pkg, user_id);
    env->ReleaseStringUTFChars(host_data_dir, host_dir);
    env->ReleaseStringUTFChars(vapp_pkg_name, pkg);
    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_com_smartdone_vm_core_nativeengine_NativeEngine_nativeRemoveIoHooks(JNIEnv *, jobject) {
    vm_native_remove_hooks();
}

extern "C" JNIEXPORT void JNICALL
Java_com_smartdone_vm_core_nativeengine_NativeEngine_nativeSetCurrentVApp(
    JNIEnv *env,
    jobject,
    jstring pkg_name,
    jint user_id
) {
    const char *pkg = env->GetStringUTFChars(pkg_name, nullptr);
    vm_native_set_current_vapp(pkg, user_id);
    env->ReleaseStringUTFChars(pkg_name, pkg);
}
