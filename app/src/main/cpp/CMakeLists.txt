# Telefonda yerel YZ: llama.cpp kaynaktan (alt modül, sabit sürüm). Yalnızca CPU; derleme sırasında ağdan bir şey
# indirilmez (F-Droid). Dağıtım: arm64-v8a ve x86_64 (öykünücü); diğer ABI'lerde yerel YZ yoktur.
# dsexec: Play sürümünde bileşen programlarının LD_PRELOAD kitaplığı (bkz. dsexec.c); her ABI'de.
cmake_minimum_required(VERSION 3.22.1)
project(droidserver_llama C CXX)

# Tekrarlanabilir derleme: kaynak yolları (__FILE__, hata ayıklama bilgisi) makineden bağımsız.
add_compile_options("-ffile-prefix-map=${CMAKE_CURRENT_SOURCE_DIR}=.")
add_link_options("-Wl,--build-id=sha1")

add_library(dsexec SHARED dsexec.c)
target_link_libraries(dsexec PRIVATE dl)

if(NOT ANDROID_ABI MATCHES "64")
    return()
endif()

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL OFF CACHE BOOL "" FORCE)
set(LLAMA_ALL_WARNINGS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_IS_DEV OFF CACHE BOOL "" FORCE)
# Taşınabilir: derleyen makinenin işlemcisine göre değil, her arm64 telefonda çalışır.
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_CPU_KLEIDIAI OFF CACHE BOOL "" FORCE)


add_subdirectory(llama.cpp build-llama)

add_library(droidserver_llama SHARED droidserver_llama.cpp)
target_link_libraries(droidserver_llama PRIVATE llama android log)
