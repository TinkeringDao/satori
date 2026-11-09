cmake_minimum_required(VERSION 3.22.1)

project("reader-onnx" LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED true)

# --------------------------------------------------------------------------
# libreader-onnx.so: IndicTrans2's graphs on sherpa-onnx's ONNX Runtime, and SentencePiece
# --------------------------------------------------------------------------

set(SATORI_ROOT ${CMAKE_CURRENT_LIST_DIR}/../../../..)
# The ONNX Runtime 1.28.2 C API headers, pinned with the library they belong to (their README).
set(ORT_HEADERS ${SATORI_ROOT}/third_party/onnxruntime-headers-1.28.2)
# sherpa-onnx's libonnxruntime.so, the one ONNX Runtime the APK carries. It is linked as -L and
# -lonnxruntime: linked by its path or as an IMPORTED target, it counts as a runtime file of this
# target, and the Android Gradle plugin copies it into this module's native output (its JNI
# artifact and AAR), leaving the app's merge to drop the copy only because its bytes are the same.
# So the library needs it by soname (NEEDED libonnxruntime.so), the module ships libreader-onnx.so
# alone, and at run time the reader binds to the copy sherpa-onnx loads. LINK_DEPENDS still relinks
# when that file changes.
set(ORT_LIBRARY_DIR ${SATORI_ROOT}/lib-sherpa-onnx/src/main/jniLibs/${ANDROID_ABI})
set(ORT_LIBRARY ${ORT_LIBRARY_DIR}/libonnxruntime.so)
# SentencePiece v0.2.1, a git submodule pinned in .gitmodules, built static.
set(SPM_SRC ${SATORI_ROOT}/third_party/sentencepiece CACHE PATH "SentencePiece source tree")

if(NOT EXISTS ${SPM_SRC}/src/sentencepiece_processor.h)
    message(FATAL_ERROR "${SPM_SRC} is empty: run git submodule update --init third_party/sentencepiece")
endif()
# A Git LFS pointer instead of the library (git lfs pull not run) is a few hundred bytes.
file(SIZE ${ORT_LIBRARY} SATORI_ORT_BYTES)
if(SATORI_ORT_BYTES LESS 1000000)
    message(FATAL_ERROR "${ORT_LIBRARY} is ${SATORI_ORT_BYTES} bytes, an LFS pointer: run git lfs pull")
endif()

# Satori's changes to SentencePiece, lib-reader-onnx/patches/*.patch in name order, applied to
# SPM_SRC before it is built, each once, as lib-llama-android applies its own to llama.cpp: a patch
# that is in already is left as it is (git apply --reverse --check), and one that neither applies
# nor is in (another SentencePiece, or a hand edit) stops the build. They are never committed to
# third_party/sentencepiece, whose pinned commit stays, so git status shows it as m (modified
# content) after a build. The files a patch changes are configure dependencies, so a checkout reset
# to the pin is patched again by the next build, and so is the list of patches; two configures at
# once take turns through a lock in the module's build folder. 0001 quotes a flag SentencePiece
# gives the NDK's clang, which a checkout path with a space or a parenthesis breaks.
find_package(Git REQUIRED)
file(GLOB SATORI_SPM_PATCHES CONFIGURE_DEPENDS ${CMAKE_CURRENT_LIST_DIR}/../../../patches/*.patch)
list(SORT SATORI_SPM_PATCHES)
set(SATORI_PATCH_LOCK ${CMAKE_CURRENT_LIST_DIR}/../../../build/satori-sentencepiece-patches.lock)
file(MAKE_DIRECTORY ${CMAKE_CURRENT_LIST_DIR}/../../../build)
file(LOCK ${SATORI_PATCH_LOCK} TIMEOUT 600)
foreach(satori_patch IN LISTS SATORI_SPM_PATCHES)
    execute_process(COMMAND ${GIT_EXECUTABLE} apply --reverse --check ${satori_patch}
                    WORKING_DIRECTORY ${SPM_SRC}
                    RESULT_VARIABLE satori_patch_in OUTPUT_QUIET ERROR_QUIET)
    if(NOT satori_patch_in EQUAL 0)
        execute_process(COMMAND ${GIT_EXECUTABLE} apply ${satori_patch}
                        WORKING_DIRECTORY ${SPM_SRC}
                        RESULT_VARIABLE satori_patch_applied ERROR_VARIABLE satori_patch_error)
        if(NOT satori_patch_applied EQUAL 0)
            file(LOCK ${SATORI_PATCH_LOCK} RELEASE)
            message(FATAL_ERROR "${satori_patch} neither applies to ${SPM_SRC} nor is in it: ${satori_patch_error}")
        endif()
        message(STATUS "Applied ${satori_patch} to ${SPM_SRC}")
    endif()
    file(STRINGS ${satori_patch} satori_patched REGEX "^\\+\\+\\+ b/")
    foreach(satori_line IN LISTS satori_patched)
        string(REGEX REPLACE "^\\+\\+\\+ b/" "" satori_file "${satori_line}")
        # Without "..", as CMake names its own inputs: SentencePiece's CMakeLists.txt is one of
        # them already, and Ninja stops on a file that is listed twice, spelled two ways.
        get_filename_component(satori_file "${SPM_SRC}/${satori_file}" ABSOLUTE)
        set_property(DIRECTORY APPEND PROPERTY CMAKE_CONFIGURE_DEPENDS ${satori_file})
    endforeach()
    set_property(DIRECTORY APPEND PROPERTY CMAKE_CONFIGURE_DEPENDS ${satori_patch})
endforeach()
file(LOCK ${SATORI_PATCH_LOCK} RELEASE)

# __FILE__ in SentencePiece's and protobuf-lite's checks, libc++'s assertions and the debug info
# name each source by its absolute path, which would put the checkout's folders and the home
# folder they are in into the .so. As in lib-llama-android/src/main/cpp/CMakeLists.txt, these maps
# make them relative: ndk/ for the NDK, ./ for the checkout, build/ for the build folder and
# sentencepiece/ for SPM_SRC; the more specific maps come last. A path through a symlink is
# mapped in both spellings (on macOS /tmp is /private/tmp).
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
satori_map_prefix("${SATORI_ROOT}" ".")
satori_map_prefix("${CMAKE_BINARY_DIR}" "build")
satori_map_prefix("${SPM_SRC}" "sentencepiece")

# Only SentencePiece's library: no shared build, tcmalloc, tests or tools (EXCLUDE_FROM_ALL builds
# just what the reader links), and its own absl subset and protobuf-lite.
set(SPM_ENABLE_SHARED OFF CACHE BOOL "" FORCE)
set(SPM_ENABLE_TCMALLOC OFF CACHE BOOL "" FORCE)
set(SPM_BUILD_TEST OFF CACHE BOOL "" FORCE)
set(SPM_ABSL_PROVIDER "internal" CACHE STRING "" FORCE)
set(SPM_PROTOBUF_PROVIDER "internal" CACHE STRING "" FORCE)
add_subdirectory(${SPM_SRC} build-sentencepiece EXCLUDE_FROM_ALL)

add_library(${CMAKE_PROJECT_NAME} SHARED
        reader_jni.cpp
        reader_engine.cpp
        beam_search.cpp
        kv_cache.cpp
        spm_tokenizer.cpp)

target_include_directories(${CMAKE_PROJECT_NAME} PRIVATE ${ORT_HEADERS} ${SPM_SRC}/src)

target_compile_options(${CMAKE_PROJECT_NAME} PRIVATE
        -Wall -Wextra -Werror
        -fvisibility=hidden -fvisibility-inlines-hidden)

# liblog for protobuf-lite inside SentencePiece, which logs through __android_log_write.
target_link_directories(${CMAKE_PROJECT_NAME} PRIVATE ${ORT_LIBRARY_DIR})
target_link_libraries(${CMAKE_PROJECT_NAME} PRIVATE
        sentencepiece-static
        -lonnxruntime
        log)
set_property(TARGET ${CMAKE_PROJECT_NAME} APPEND PROPERTY LINK_DEPENDS ${ORT_LIBRARY})

# Only the JNI functions are exported (SentencePiece's and protobuf-lite's symbols stay inside);
# nothing is left undefined but what the libraries named above give; LOAD segments are 16 KB
# aligned, as every .so of the APK is.
target_link_options(${CMAKE_PROJECT_NAME} PRIVATE
        -Wl,--exclude-libs,ALL
        -Wl,--no-undefined
        -Wl,--gc-sections
        -Wl,-z,max-page-size=16384)
