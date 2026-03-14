#include "frida-gum.h"

#include <android/log.h>
#include <fcntl.h>
#include <string>
#include <vector>

#define VM_LOG_TAG "core-native"
#define VM_LOGI(...) __android_log_print(ANDROID_LOG_INFO, VM_LOG_TAG, __VA_ARGS__)

namespace {
enum HookId {
    HOOK_OPEN = 1,
    HOOK_OPENAT = 2,
    HOOK_FOPEN = 3,
    HOOK_ACCESS = 4,
    HOOK_STAT = 5
};

GumInterceptor *interceptor = nullptr;
GumInvocationListener *listener = nullptr;
bool hooks_installed = false;
thread_local bool reentry_guard = false;
thread_local std::string rewritten_path;
std::string current_pkg;
int current_user_id = 0;
std::string host_data_dir;

std::string source_prefix_data() {
    return current_pkg.empty() ? "" : "/data/data/" + current_pkg;
}

std::string source_prefix_user0() {
    return current_pkg.empty() ? "" : "/data/user/0/" + current_pkg;
}

std::string target_prefix() {
    return host_data_dir + "/VirtualEnv/data/user/" + std::to_string(current_user_id) + "/" + current_pkg;
}

const char *rewrite_path(const char *path) {
    if (path == nullptr || current_pkg.empty() || host_data_dir.empty()) {
        return path;
    }
    const std::string original(path);
    const auto data_prefix = source_prefix_data();
    const auto user_prefix = source_prefix_user0();
    if (!data_prefix.empty() && original.rfind(data_prefix, 0) == 0) {
        rewritten_path = target_prefix() + original.substr(data_prefix.length());
        return rewritten_path.c_str();
    }
    if (!user_prefix.empty() && original.rfind(user_prefix, 0) == 0) {
        rewritten_path = target_prefix() + original.substr(user_prefix.length());
        return rewritten_path.c_str();
    }
    return path;
}

void on_enter(GumInvocationContext *ic, gpointer user_data) {
    if (reentry_guard) {
        return;
    }
    reentry_guard = true;
    const auto hook_id = static_cast<HookId>(GPOINTER_TO_INT(gum_invocation_context_get_listener_function_data(ic)));
    guint path_index = 0;
    switch (hook_id) {
        case HOOK_OPEN:
        case HOOK_FOPEN:
        case HOOK_ACCESS:
        case HOOK_STAT:
            path_index = 0;
            break;
        case HOOK_OPENAT:
            path_index = 1;
            break;
    }
    const auto *path = static_cast<const char *>(gum_invocation_context_get_nth_argument(ic, path_index));
    const auto *mapped = rewrite_path(path);
    if (mapped != path) {
        gum_invocation_context_replace_nth_argument(ic, path_index, const_cast<char *>(mapped));
        VM_LOGI("redirect[%d]: %s -> %s", hook_id, path, mapped);
    }
    reentry_guard = false;
}

void on_leave(GumInvocationContext *, gpointer) {
}

void attach_hook(const char *export_name, HookId hook_id) {
    const GumAddress address = gum_module_find_global_export_by_name(export_name);
    if (address == 0) {
        VM_LOGI("skip missing export %s", export_name);
        return;
    }
    gum_interceptor_attach(
        interceptor,
        GSIZE_TO_POINTER(address),
        listener,
        GINT_TO_POINTER(hook_id),
        GUM_ATTACH_FLAGS_NONE);
}
}

extern "C" bool vm_native_init_gum();

extern "C" bool vm_native_install_hooks(const char *host_dir, const char *pkg, int user_id) {
    if (!vm_native_init_gum()) {
        return false;
    }
    host_data_dir = host_dir == nullptr ? "" : host_dir;
    current_pkg = pkg == nullptr ? "" : pkg;
    current_user_id = user_id;
    if (hooks_installed) {
        VM_LOGI("IO hooks already installed for %s user %d", current_pkg.c_str(), current_user_id);
        return true;
    }
    interceptor = gum_interceptor_obtain();
    listener = gum_make_call_listener(on_enter, on_leave, nullptr, nullptr);
    gum_interceptor_begin_transaction(interceptor);
    attach_hook("open", HOOK_OPEN);
    attach_hook("openat", HOOK_OPENAT);
    attach_hook("fopen", HOOK_FOPEN);
    attach_hook("access", HOOK_ACCESS);
    attach_hook("stat", HOOK_STAT);
    gum_interceptor_end_transaction(interceptor);
    hooks_installed = true;
    VM_LOGI("Installed IO hooks for %s user %d", current_pkg.c_str(), current_user_id);
    return true;
}

extern "C" void vm_native_remove_hooks() {
    if (!hooks_installed) {
        return;
    }
    if (interceptor != nullptr && listener != nullptr) {
        gum_interceptor_detach(interceptor, listener);
        g_object_unref(listener);
        listener = nullptr;
    }
    if (interceptor != nullptr) {
        g_object_unref(interceptor);
        interceptor = nullptr;
    }
    hooks_installed = false;
    VM_LOGI("Removed IO hooks");
}

extern "C" void vm_native_set_current_vapp(const char *pkg, int user_id) {
    current_pkg = pkg == nullptr ? "" : pkg;
    current_user_id = user_id;
    VM_LOGI("Switching virtual app context to %s user %d", current_pkg.c_str(), current_user_id);
}
