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

add_library(absent_bg OBJECT absent_bg/absent_bg.cpp absent_bg_jni.cpp)
set_target_properties(absent_bg PROPERTIES POSITION_INDEPENDENT_CODE ON)
target_compile_options(absent_bg PRIVATE -O3 -fvisibility=hidden)

add_library(motion_tracker OBJECT tracker/tracker.cpp tracker_jni.cpp)
set_target_properties(motion_tracker PROPERTIES POSITION_INDEPENDENT_CODE ON)
target_compile_options(motion_tracker PRIVATE -O3 -fvisibility=hidden)

add_library(amengine SHARED preview.cpp camera.cpp drawing.cpp effects.cpp easing.cpp engine.cpp interpolation.cpp jni.cpp model.cpp motion.cpp scene.cpp shapes.cpp styles.cpp text.cpp textanim.cpp xml.cpp denoise_jni.cpp audio_dsp.cpp audio_fx_jni.cpp)
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
    rnnoise absent_bg motion_tracker m vulkan android nativewindow jnigraphics EGL GLESv3 log)

set(LAME_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/lame)
set(LAME_SOURCES bitstream encoder fft gain_analysis id3tag lame mpglib_interface newmdct presets psymodel quantize
    quantize_pvt reservoir set_get tables takehiro util vbrquantize VbrTag version)
list(TRANSFORM LAME_SOURCES PREPEND ${LAME_DIR}/libmp3lame/)
list(TRANSFORM LAME_SOURCES APPEND .c)
add_library(mp3lame SHARED ${LAME_SOURCES})
target_include_directories(mp3lame PUBLIC ${LAME_DIR}/include PRIVATE ${LAME_DIR}/libmp3lame)
target_compile_definitions(mp3lame PRIVATE NDEBUG STDC_HEADERS HAVE_STDINT_H HAVE_ERRNO_H HAVE_FCNTL_H ieee754_float32_t=float)
target_compile_options(mp3lame PRIVATE -O2 -w)
target_link_libraries(mp3lame PRIVATE m)
add_library(lame_jni SHARED lame_jni.cpp)
target_compile_options(lame_jni PRIVATE -O2 -fvisibility=hidden -Wall)
target_link_libraries(lame_jni PRIVATE mp3lame)

set(WHISPER_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/whisper.cpp)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_CPU_ARM_ARCH armv8-a CACHE STRING "" FORCE)
add_subdirectory(${WHISPER_DIR} whisper EXCLUDE_FROM_ALL)
foreach(lib whisper ggml ggml-base ggml-cpu)
    target_compile_options(${lib} PRIVATE -O3 -g0 -ffunction-sections -fdata-sections)
endforeach()
add_library(whisper_jni SHARED whisper_jni.cpp)
target_compile_options(whisper_jni PRIVATE -O3 -g0 -fvisibility=hidden -Wall)
target_link_libraries(whisper_jni PRIVATE whisper -Wl,--gc-sections -Wl,--exclude-libs,ALL)

get_target_property(GGML_CPU_DIR ggml-cpu SOURCE_DIR)
get_target_property(GGML_CPU_SOURCES ggml-cpu SOURCES)
list(TRANSFORM GGML_CPU_SOURCES PREPEND ${GGML_CPU_DIR}/)
add_library(ggml-cpu-fast STATIC EXCLUDE_FROM_ALL ${GGML_CPU_SOURCES})
foreach(property INCLUDE_DIRECTORIES COMPILE_DEFINITIONS COMPILE_OPTIONS COMPILE_FEATURES C_STANDARD C_EXTENSIONS CXX_STANDARD CXX_EXTENSIONS
                 POSITION_INDEPENDENT_CODE LINK_LIBRARIES INTERFACE_LINK_LIBRARIES)
    get_target_property(value ggml-cpu ${property})
    if(value)
        list(TRANSFORM value REPLACE "^-march=armv8-a$" "-march=armv8.2-a+dotprod+fp16")
        set_target_properties(ggml-cpu-fast PROPERTIES ${property} "${value}")
    endif()
endforeach()
get_directory_property(GGML_CPU_DIR_DEFINITIONS DIRECTORY ${GGML_CPU_DIR} COMPILE_DEFINITIONS)
target_compile_definitions(ggml-cpu-fast PRIVATE ${GGML_CPU_DIR_DEFINITIONS})
add_library(whisper_jni_fast SHARED whisper_jni.cpp)
target_compile_options(whisper_jni_fast PRIVATE -O3 -g0 -fvisibility=hidden -Wall)
target_include_directories(whisper_jni_fast PRIVATE $<TARGET_PROPERTY:whisper,INTERFACE_INCLUDE_DIRECTORIES> $<TARGET_PROPERTY:ggml,INTERFACE_INCLUDE_DIRECTORIES>
                           $<TARGET_PROPERTY:ggml-base,INTERFACE_INCLUDE_DIRECTORIES>)
target_link_libraries(whisper_jni_fast PRIVATE $<TARGET_FILE:whisper> -Wl,--gc-sections -Wl,--exclude-libs,ALL $<TARGET_FILE:ggml> ggml-cpu-fast
                      $<TARGET_FILE:ggml-base> m dl atomic)
add_dependencies(whisper_jni_fast whisper ggml ggml-base)

set(NCNN_DIR ${AM_ROOT}/third_party/ncnn)
set(RIFE_WARP_DIR ${AM_ROOT}/third_party/rife_warp)
foreach(option NCNN_VULKAN NCNN_SIMPLEVK NCNN_DISABLE_RTTI NCNN_DISABLE_EXCEPTION NCNN_ARM82)
    set(${option} ON CACHE BOOL "" FORCE)
endforeach()
foreach(option NCNN_SHARED_LIB NCNN_INSTALL_SDK NCNN_BUILD_TOOLS NCNN_BUILD_EXAMPLES NCNN_BUILD_BENCHMARK NCNN_BUILD_TESTS NCNN_PYTHON
               NCNN_C_API NCNN_PLATFORM_API NCNN_INT8 NCNN_BF16 NCNN_WEIGHT_QUANT NCNN_PIXEL_DRAWING NCNN_PIXEL_AFFINE NCNN_PIXEL_ROTATE
               NCNN_ARM82DOT NCNN_ARM82FP16FML NCNN_ARM84BF16 NCNN_ARM84I8MM NCNN_ARM86SVE NCNN_ARM86SVE2 NCNN_ARM86SVEBF16 NCNN_ARM86SVEI8MM NCNN_ARM86SVEF32MM)
    set(${option} OFF CACHE BOOL "" FORCE)
endforeach()
set(NCNN_LAYERS input split concat convolution convolutiondepthwise deconvolution crop padding pooling interp memorydata pixelshuffle
    relu prelu sigmoid slice eltwise binaryop packing cast gemm reshape flatten noop)
file(STRINGS ${NCNN_DIR}/src/CMakeLists.txt NCNN_LAYER_LINES REGEX "^ncnn_add_layer\\(")
foreach(line ${NCNN_LAYER_LINES})
    string(REGEX REPLACE "^ncnn_add_layer\\(([A-Za-z0-9]+).*" "\\1" layer "${line}")
    string(TOLOWER ${layer} layer)
    if(layer IN_LIST NCNN_LAYERS)
        set(WITH_LAYER_${layer} ON CACHE BOOL "" FORCE)
    else()
        set(WITH_LAYER_${layer} OFF CACHE BOOL "" FORCE)
    endif()
endforeach()
add_subdirectory(${NCNN_DIR} ncnn EXCLUDE_FROM_ALL)
foreach(lib ncnn glslang SPIRV GenericCodeGen MachineIndependent OSDependent glslang-default-resource-limits)
    if(TARGET ${lib})
        target_compile_options(${lib} PRIVATE -O3 -g0 -ffunction-sections -fdata-sections)
    endif()
endforeach()
set(RIFE_SHADERS warp warp_pack4 warp_pack8)
foreach(shader ${RIFE_SHADERS})
    set(header ${CMAKE_CURRENT_BINARY_DIR}/rife_warp/${shader}.comp.hex.h)
    add_custom_command(OUTPUT ${header}
        COMMAND ${CMAKE_COMMAND} -DSHADER_SRC=${RIFE_WARP_DIR}/${shader}.comp -DSHADER_COMP_HEADER=${header} -P ${RIFE_WARP_DIR}/generate_shader_comp_header.cmake
        DEPENDS ${RIFE_WARP_DIR}/${shader}.comp WORKING_DIRECTORY ${CMAKE_CURRENT_BINARY_DIR}/rife_warp VERBATIM)
    list(APPEND RIFE_SHADER_HEADERS ${header})
endforeach()
file(MAKE_DIRECTORY ${CMAKE_CURRENT_BINARY_DIR}/rife_warp)
add_library(absent_ai SHARED ai_jni.cpp ${RIFE_WARP_DIR}/warp.cpp ${RIFE_SHADER_HEADERS})
target_include_directories(absent_ai PRIVATE ${RIFE_WARP_DIR} ${CMAKE_CURRENT_BINARY_DIR}/rife_warp)
target_compile_options(absent_ai PRIVATE -O3 -g0 -fno-rtti -fno-exceptions -fvisibility=hidden -ffunction-sections -fdata-sections)
target_link_libraries(absent_ai PRIVATE ncnn -Wl,--gc-sections -Wl,--exclude-libs,ALL log)
