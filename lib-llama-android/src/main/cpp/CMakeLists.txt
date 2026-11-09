cmake_minimum_required(VERSION 3.31.6)

project("ai-chat" VERSION 1.0.0 LANGUAGES C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED true)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED true)

set(CMAKE_C_FLAGS   "${CMAKE_C_FLAGS}"   CACHE STRING "" FORCE)
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS}" CACHE STRING "" FORCE)

# --------------------------------------------------------------------------
# AI Chat library
# --------------------------------------------------------------------------

if(DEFINED ANDROID_ABI)
    message(STATUS "Detected Android ABI: ${ANDROID_ABI}")
    if(ANDROID_ABI STREQUAL "arm64-v8a")
        set(GGML_SYSTEM_ARCH "ARM")
        set(GGML_CPU_KLEIDIAI ON)
        set(GGML_OPENMP ON)
    elseif(ANDROID_ABI STREQUAL "x86_64")
        set(GGML_SYSTEM_ARCH "x86")
        set(GGML_CPU_KLEIDIAI OFF)
        set(GGML_OPENMP OFF)
    else()
        message(FATAL_ERROR "Unsupported ABI: ${ANDROID_ABI}")
    endif()
endif()

# llama.cpp is a git submodule pinned to b9574 (third_party/llama.cpp).
# Pass -DLLAMA_SRC=<path> to build against a different checkout.
set(LLAMA_SRC ${CMAKE_CURRENT_LIST_DIR}/../../../../third_party/llama.cpp CACHE PATH "llama.cpp source tree")

# Satori's own changes to llama.cpp, lib-llama-android/patches/*.patch in name order, applied to the
# checkout before it is built, each once: a patch that is in already is left as it is (git apply
# --reverse --check), so every configure can run this. They are never committed to
# third_party/llama.cpp, whose pinned commit stays as it is. A patch that neither applies nor is in
# (another llama.cpp, or a hand edit) stops the build. The files a patch changes are configure
# dependencies, so a checkout reset to the pin is patched again by the next build, and so is the
# list of patches (CONFIGURE_DEPENDS): a patch added or taken out reconfigures the build. Two
# configures at once (two build types or ABIs) take turns through a lock in the module's build
# directory, so they never apply a patch twice.
find_package(Git REQUIRED)
file(GLOB SATORI_LLAMA_PATCHES CONFIGURE_DEPENDS ${CMAKE_CURRENT_LIST_DIR}/../../../patches/*.patch)
list(SORT SATORI_LLAMA_PATCHES)
set(SATORI_PATCH_LOCK ${CMAKE_CURRENT_LIST_DIR}/../../../build/satori-llama-patches.lock)
file(MAKE_DIRECTORY ${CMAKE_CURRENT_LIST_DIR}/../../../build)
file(LOCK ${SATORI_PATCH_LOCK} TIMEOUT 600)
foreach(satori_patch IN LISTS SATORI_LLAMA_PATCHES)
    execute_process(COMMAND ${GIT_EXECUTABLE} apply --reverse --check ${satori_patch}
                    WORKING_DIRECTORY ${LLAMA_SRC}
                    RESULT_VARIABLE satori_patch_in OUTPUT_QUIET ERROR_QUIET)
    if(NOT satori_patch_in EQUAL 0)
        execute_process(COMMAND ${GIT_EXECUTABLE} apply ${satori_patch}
                        WORKING_DIRECTORY ${LLAMA_SRC}
                        RESULT_VARIABLE satori_patch_applied ERROR_VARIABLE satori_patch_error)
        if(NOT satori_patch_applied EQUAL 0)
            file(LOCK ${SATORI_PATCH_LOCK} RELEASE)
            message(FATAL_ERROR "${satori_patch} neither applies to ${LLAMA_SRC} nor is in it: ${satori_patch_error}")
        endif()
        message(STATUS "Applied ${satori_patch} to ${LLAMA_SRC}")
    endif()
    file(STRINGS ${satori_patch} satori_patched REGEX "^\\+\\+\\+ b/")
    foreach(satori_line IN LISTS satori_patched)
        string(REGEX REPLACE "^\\+\\+\\+ b/" "" satori_file "${satori_line}")
        set_property(DIRECTORY APPEND PROPERTY CMAKE_CONFIGURE_DEPENDS ${LLAMA_SRC}/${satori_file})
    endforeach()
    set_property(DIRECTORY APPEND PROPERTY CMAKE_CONFIGURE_DEPENDS ${satori_patch})
endforeach()
file(LOCK ${SATORI_PATCH_LOCK} RELEASE)

# __FILE__ in llama.cpp's and ggml's asserts and logs, and the debug info, name each source by its
# absolute path, which puts the folders of the checkout that built the libraries, and the home
# folder they are in, into every .so. These maps make those paths relative: ndk/ for the NDK (its
# headers in the debug info), ./ for the checkout, build/ for the build folder and llama.cpp/ for
# LLAMA_SRC. For __FILE__ the longest map that matches is used and for the debug info the last one,
# so the more specific maps come last. -ffile-prefix-map covers both; -fmacro-prefix-map is given as
# well, for __FILE__ alone. A path through a symlink is mapped in both spellings (on macOS /tmp is
# /private/tmp).
function(satori_map_prefix satori_from satori_to)
    get_filename_component(satori_absolute "${satori_from}" ABSOLUTE)
    get_filename_component(satori_real "${satori_from}" REALPATH)
    set(satori_paths "${satori_absolute}" "${satori_real}")
    list(REMOVE_DUPLICATES satori_paths)
    foreach(satori_path IN LISTS satori_paths)
        add_compile_options("-ffile-prefix-map=${satori_path}=${satori_to}"
                            "-fmacro-prefix-map=${satori_path}=${satori_to}")
    endforeach()
endfunction()
if(CMAKE_ANDROID_NDK)
    satori_map_prefix("${CMAKE_ANDROID_NDK}" "ndk")
endif()
satori_map_prefix("${CMAKE_CURRENT_LIST_DIR}/../../../.." ".")
satori_map_prefix("${CMAKE_BINARY_DIR}" "build")
satori_map_prefix("${LLAMA_SRC}" "llama.cpp")

add_subdirectory(${LLAMA_SRC} build-llama)

# OpenMP's source locations (";<path>;<function>;<line>;<column>;;", which libomp shows in its
# messages) hold the path as the compiler opened it, and no prefix map changes them. clang writes
# them only into code built with debug info, so ggml's two sources with OpenMP regions are built
# without it and say "unknown" there instead. The libraries in the APK are stripped of their debug
# info anyway; in the unstripped ones these two files have no line numbers.
get_filename_component(SATORI_GGML_SRC "${LLAMA_SRC}/ggml/src" ABSOLUTE)
set_source_files_properties(${SATORI_GGML_SRC}/ggml-quants.c ${SATORI_GGML_SRC}/ggml-cpu/ggml-cpu.c
        DIRECTORY ${SATORI_GGML_SRC}
        PROPERTIES COMPILE_OPTIONS -g0)

add_library(${CMAKE_PROJECT_NAME} SHARED
        ai_chat.cpp
        grammar_sampler.cpp)

target_compile_definitions(${CMAKE_PROJECT_NAME} PRIVATE
        GGML_SYSTEM_ARCH=${GGML_SYSTEM_ARCH}
        GGML_CPU_KLEIDIAI=$<BOOL:${GGML_CPU_KLEIDIAI}>
        GGML_OPENMP=$<BOOL:${GGML_OPENMP}>
)

target_include_directories(${CMAKE_PROJECT_NAME} PRIVATE
        ${LLAMA_SRC}
        ${LLAMA_SRC}/common
        ${LLAMA_SRC}/vendor
        ${LLAMA_SRC}/include
        ${LLAMA_SRC}/ggml/include
        ${LLAMA_SRC}/ggml/src)

target_link_libraries(${CMAKE_PROJECT_NAME}
        llama
        # The common utils library was renamed from `common` to `llama-common`
        # upstream (llama.cpp ~b9xxx); it transitively pulls in llama-common-base.
        llama-common
        android
        log)
