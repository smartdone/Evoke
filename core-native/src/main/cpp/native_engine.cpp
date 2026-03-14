#include "frida-gum.h"

#include <android/log.h>

#include <mutex>

#define VM_LOG_TAG "core-native"
#define VM_LOGI(...) __android_log_print(ANDROID_LOG_INFO, VM_LOG_TAG, __VA_ARGS__)
#define VM_LOGW(...) __android_log_print(ANDROID_LOG_WARN, VM_LOG_TAG, __VA_ARGS__)

namespace {
std::once_flag gum_once;
bool gum_ready = false;

gboolean log_module(GumModule *module, gpointer) {
    static int logged = 0;
    if (logged < 12) {
        const auto *range = gum_module_get_range(module);
        VM_LOGI("module[%d]: %s @ 0x%lx", logged, gum_module_get_name(module),
                static_cast<unsigned long>(range->base_address));
    }
    logged++;
    return TRUE;
}
}

extern "C" bool vm_native_init_gum() {
    std::call_once(gum_once, [] {
        gum_init_embedded();
        gum_ready = true;
        gum_process_enumerate_modules(log_module, nullptr);
        const GumAddress open_address = gum_module_find_global_export_by_name("open");
        const GumAddress openat_address = gum_module_find_global_export_by_name("openat");
        if (open_address == 0 || openat_address == 0) {
            VM_LOGW("Failed to resolve libc exports: open=%p openat=%p",
                    reinterpret_cast<void *>(open_address),
                    reinterpret_cast<void *>(openat_address));
        } else {
            VM_LOGI("Resolved exports: open=%p openat=%p",
                    reinterpret_cast<void *>(open_address),
                    reinterpret_cast<void *>(openat_address));
        }
    });
    return gum_ready;
}
