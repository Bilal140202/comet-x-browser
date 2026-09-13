cmake_minimum_required(VERSION 3.22.1)
project(cometx_llama CXX)

include(FetchContent)

set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL OFF CACHE BOOL "" FORCE)

# llama.cpp pinned to tag b4458 — the exact API era used by llama_jni.cpp.
# Integrity: official GitHub source tarball, SHA-256 hash pinned below
# (same verified pin as the JARVIS reference implementation).
FetchContent_Declare(
    llamacpp
    URL https://github.com/ggml-org/llama.cpp/archive/refs/tags/b4458.tar.gz
    URL_HASH SHA256=62e698f1a34e851d448db0d85123bf6f1c3f6eec5a25e29ddc8a4a71aeda5230
)
FetchContent_MakeAvailable(llamacpp)

add_library(cometx_llama SHARED llama_jni.cpp)

find_library(android_log log)
target_link_libraries(cometx_llama PRIVATE llama ${android_log})
target_compile_options(cometx_llama PRIVATE -O3 -fexceptions -frtti -Wall)
target_include_directories(cometx_llama PRIVATE ${llamacpp_SOURCE_DIR}/include)
