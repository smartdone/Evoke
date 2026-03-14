cmake_minimum_required(VERSION 3.22.1)
project("core-native")

set(FRIDA_GUM_ROOT ${CMAKE_CURRENT_SOURCE_DIR}/third_party/frida-gum/${ANDROID_ABI})

add_library(frida-gum STATIC IMPORTED)
set_target_properties(frida-gum PROPERTIES
    IMPORTED_LOCATION ${FRIDA_GUM_ROOT}/libfrida-gum.a
)

add_library(core-native SHARED
    native_engine.cpp
    io_redirect.cpp
    jni_hook.cpp)

find_library(log_lib log)
find_library(dl_lib dl)
find_library(m_lib m)
find_library(z_lib z)

target_include_directories(core-native PRIVATE
    ${FRIDA_GUM_ROOT}
)

target_compile_options(core-native PRIVATE
    -fvisibility=hidden
    -fvisibility-inlines-hidden
)

target_link_options(core-native PRIVATE
    -Wl,--gc-sections
    -Wl,--exclude-libs,ALL
    -Wl,-z,max-page-size=16384
    -Wl,-z,common-page-size=16384
)

target_link_libraries(core-native
    frida-gum
    ${log_lib}
    ${dl_lib}
    ${m_lib}
    ${z_lib})
