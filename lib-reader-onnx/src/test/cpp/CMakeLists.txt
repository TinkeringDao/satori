# Host builds of the reader's native code for its tests on a Mac (run_host_tests.sh drives them;
# Gradle doesn't build them). They compile the same sources as src/main/cpp against the same
# pinned ONNX Runtime 1.28.2 headers and SentencePiece submodule, and link ONNX Runtime 1.28.2's
# macOS release library in place of sherpa-onnx's Android one.
#   READER_SANITIZE=ON  (default): reader_host_test, everything under ASan and UBSan.
#   READER_JNI=ON: also libreader-onnx.dylib with reader_jni.cpp, for the JVM test of
#                  ReaderEngine.kt (no sanitizers there: a JVM doesn't run under ASan).

cmake_minimum_required(VERSION 3.22)

project("reader-host-test" LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED true)

set(READER_CPP ${CMAKE_CURRENT_LIST_DIR}/../../main/cpp)
set(SATORI_ROOT ${CMAKE_CURRENT_LIST_DIR}/../../../..)
set(ORT_HEADERS ${SATORI_ROOT}/third_party/onnxruntime-headers-1.28.2)
set(SPM_SRC ${SATORI_ROOT}/third_party/sentencepiece CACHE PATH "SentencePiece source tree")
set(ORT_DIR "" CACHE PATH "The unpacked onnxruntime-osx-arm64-1.28.2 release")
option(READER_SANITIZE "Build under ASan and UBSan" ON)
option(READER_JNI "Build the JNI library for the JVM test" OFF)

if(NOT EXISTS ${SPM_SRC}/src/sentencepiece_processor.h)
    message(FATAL_ERROR "${SPM_SRC} is empty: git submodule update --init third_party/sentencepiece")
endif()
find_library(ORT_LIBRARY onnxruntime PATHS ${ORT_DIR}/lib NO_DEFAULT_PATH)
if(NOT ORT_LIBRARY)
    message(FATAL_ERROR "No libonnxruntime in ORT_DIR=${ORT_DIR}/lib")
endif()

if(READER_SANITIZE)
    # Every finding stops the run: UBSan doesn't recover, ASan never does.
    set(READER_SANITIZERS -fsanitize=address,undefined -fno-sanitize-recover=all -fno-omit-frame-pointer)
    add_compile_options(${READER_SANITIZERS} -g)
    add_link_options(${READER_SANITIZERS})
endif()

set(SPM_ENABLE_SHARED OFF CACHE BOOL "" FORCE)
set(SPM_ENABLE_TCMALLOC OFF CACHE BOOL "" FORCE)
set(SPM_BUILD_TEST OFF CACHE BOOL "" FORCE)
set(SPM_ABSL_PROVIDER "internal" CACHE STRING "" FORCE)
set(SPM_PROTOBUF_PROVIDER "internal" CACHE STRING "" FORCE)
add_subdirectory(${SPM_SRC} build-sentencepiece EXCLUDE_FROM_ALL)

add_library(reader_core STATIC
        ${READER_CPP}/beam_search.cpp
        ${READER_CPP}/kv_cache.cpp
        ${READER_CPP}/reader_engine.cpp
        ${READER_CPP}/spm_tokenizer.cpp)
target_include_directories(reader_core PUBLIC ${READER_CPP} ${ORT_HEADERS} PRIVATE ${SPM_SRC}/src)
target_compile_options(reader_core PRIVATE -Wall -Wextra -Werror)
target_link_libraries(reader_core PUBLIC sentencepiece-static ${ORT_LIBRARY})

add_executable(reader_host_test reader_host_test.cpp)
target_compile_options(reader_host_test PRIVATE -Wall -Wextra -Werror)
target_link_libraries(reader_host_test PRIVATE reader_core)
set_target_properties(reader_host_test PROPERTIES BUILD_RPATH ${ORT_DIR}/lib)

if(READER_JNI)
    # The JDK's jni.h and its platform header; the library needs no libjvm to link.
    set(JDK_DIR "$ENV{JAVA_HOME}" CACHE PATH "The JDK whose jni.h to build against")
    if(NOT EXISTS ${JDK_DIR}/include/jni.h)
        message(FATAL_ERROR "No jni.h under JDK_DIR=${JDK_DIR}")
    endif()
    add_library(reader-onnx SHARED ${READER_CPP}/reader_jni.cpp)
    target_include_directories(reader-onnx PRIVATE ${JDK_DIR}/include ${JDK_DIR}/include/darwin)
    target_compile_options(reader-onnx PRIVATE -Wall -Wextra -Werror)
    target_link_libraries(reader-onnx PRIVATE reader_core)
    set_target_properties(reader-onnx PROPERTIES BUILD_RPATH ${ORT_DIR}/lib)
endif()
