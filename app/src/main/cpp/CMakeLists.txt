cmake_minimum_required(VERSION 3.22)
project(amengine C CXX)

get_filename_component(AM_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../../.." ABSOLUTE)
add_compile_options("-ffile-prefix-map=${AM_ROOT}=." "-fdebug-prefix-map=${AM_ROOT}=.")
if(CMAKE_ANDROID_NDK)
    add_compile_options("-ffile-prefix-map=${CMAKE_ANDROID_NDK}=/ndk" "-fdebug-prefix-map=${CMAKE_ANDROID_NDK}=/ndk")
endif()

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(SKIA_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/skia)
set(SKIA_OUT ${SKIA_DIR}/out/android-arm64)

set(RNNOISE_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/rnnoise)
add_library(rnnoise STATIC
    ${RNNOISE_DIR}/src/denoise.c ${RNNOISE_DIR}/src/rnn.c ${RNNOISE_DIR}/src/rnn_data.c
    ${RNNOISE_DIR}/src/pitch.c ${RNNOISE_DIR}/src/kiss_fft.c ${RNNOISE_DIR}/src/celt_lpc.c)
set_target_properties(rnnoise PROPERTIES POSITION_INDEPENDENT_CODE ON)
target_include_directories(rnnoise PUBLIC ${RNNOISE_DIR}/include PRIVATE ${RNNOISE_DIR}/src)
target_compile_definitions(rnnoise PRIVATE RNNOISE_BUILD)
target_compile_options(rnnoise PRIVATE -O3 -fvisibility=hidden)

add_library(amengine SHARED preview.cpp camera.cpp drawing.cpp effects.cpp easing.cpp engine.cpp jni.cpp scene.cpp shapes.cpp styles.cpp text.cpp xml.cpp denoise_jni.cpp)
target_include_directories(amengine PRIVATE ${SKIA_DIR} ${SKIA_DIR}/third_party/externals/expat/expat/lib)
target_compile_definitions(amengine PRIVATE
    NDEBUG SK_GANESH SK_GRAPHITE SK_VULKAN SK_GL SK_ENABLE_PRECOMPILE SK_DISABLE_TRACING
    VK_USE_PLATFORM_ANDROID_KHR XML_STATIC)
target_compile_options(amengine PRIVATE -O2 -fno-rtti -fno-exceptions -fvisibility=hidden -Wall)

set(SKIA_LIBS skparagraph skshaper skunicode_core skunicode_icu skia pathops skcms freetype2 harfbuzz icu png jpeg webp wuffs zlib expat cpu-features)
list(TRANSFORM SKIA_LIBS PREPEND ${SKIA_OUT}/lib)
list(TRANSFORM SKIA_LIBS APPEND .a)
target_link_libraries(amengine PRIVATE
    -Wl,--start-group ${SKIA_LIBS} -Wl,--end-group
    -Wl,--gc-sections
    rnnoise m vulkan android nativewindow jnigraphics EGL GLESv3 log)

set(WHISPER_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/whisper.cpp)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_CPU_ARM_ARCH armv8.2-a+dotprod+fp16 CACHE STRING "" FORCE)
add_subdirectory(${WHISPER_DIR} whisper EXCLUDE_FROM_ALL)
foreach(lib whisper ggml ggml-base ggml-cpu)
    target_compile_options(${lib} PRIVATE -O3 -g0 -ffunction-sections -fdata-sections)
endforeach()
add_library(whisper_jni SHARED whisper_jni.cpp)
target_compile_options(whisper_jni PRIVATE -O3 -g0 -fvisibility=hidden -Wall)
target_link_libraries(whisper_jni PRIVATE whisper -Wl,--gc-sections -Wl,--exclude-libs,ALL)
