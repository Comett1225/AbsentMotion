cmake_minimum_required(VERSION 3.21)
project(AbsentMotionSetup CXX RC)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_MSVC_RUNTIME_LIBRARY "MultiThreaded")

if(NOT AM_VERSION)
    set(AM_VERSION "0.0.0")
endif()
string(REGEX MATCHALL "[0-9]+" AM_VERSION_PARTS "${AM_VERSION}")
list(APPEND AM_VERSION_PARTS 0 0 0 0)
list(SUBLIST AM_VERSION_PARTS 0 4 AM_VERSION_PARTS)
string(REPLACE ";" "," AM_VERSION_COMMAS "${AM_VERSION_PARTS}")
get_filename_component(AM_ICON "${CMAKE_CURRENT_SOURCE_DIR}/../AbsentMotion.ico" ABSOLUTE)
set(AM_MANIFEST "${CMAKE_CURRENT_SOURCE_DIR}/setup.manifest")
configure_file(setup.rc.in "${CMAKE_CURRENT_BINARY_DIR}/setup.rc" @ONLY)

add_compile_definitions(UNICODE _UNICODE NOMINMAX WIN32_LEAN_AND_MEAN)

add_executable(AbsentMotionSetup WIN32 setup.cpp ui.cpp "${CMAKE_CURRENT_BINARY_DIR}/setup.rc")
target_link_libraries(AbsentMotionSetup PRIVATE d2d1 dwrite dwmapi cabinet bcrypt shell32 ole32 uuid advapi32 user32)
target_link_options(AbsentMotionSetup PRIVATE /MANIFEST:NO)

add_executable(ampack ampack.cpp)
target_link_libraries(ampack PRIVATE cabinet bcrypt)
