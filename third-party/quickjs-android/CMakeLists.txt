cmake_minimum_required(VERSION 3.25)
project(cash_quickjs_android LANGUAGES C CXX)

set(native "${CMAKE_CURRENT_SOURCE_DIR}/upstream/quickjs/common/native")
file(STRINGS "${native}/quickjs/VERSION" quickjs_version LIMIT_COUNT 1)

add_library(quickjs SHARED
    "${native}/Context.cpp"
    "${native}/ExceptionThrowers.cpp"
    "${native}/JavaMethodProxy.cpp"
    "${native}/JavaObjectProxy.cpp"
    "${native}/JsMethodProxy.cpp"
    "${native}/JsObjectProxy.cpp"
    "${native}/quickjs-jni.cpp"
    "${native}/quickjs/cutils.c"
    "${native}/quickjs/libregexp.c"
    "${native}/quickjs/libunicode.c"
    "${native}/quickjs/quickjs.c"
)
set_target_properties(quickjs PROPERTIES
    C_STANDARD 99
    CXX_STANDARD 11
    C_VISIBILITY_PRESET hidden
    CXX_VISIBILITY_PRESET hidden
)

# Match the published 0.9.2 release profile in every app variant. The Java/JNI
# API, engine version and bytecode configuration remain unchanged.
target_compile_definitions(quickjs PRIVATE CONFIG_VERSION="${quickjs_version}" NDEBUG)
target_compile_options(quickjs PRIVATE -fstrict-aliasing -Os -fomit-frame-pointer)
target_link_libraries(quickjs PRIVATE m)
target_link_options(quickjs PRIVATE "-Wl,-z,max-page-size=16384")
