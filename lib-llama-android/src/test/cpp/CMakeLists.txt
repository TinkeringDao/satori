# Host builds of grammar_sampler_test and copied_pages_test (see the header of each). They run on
# the development machine, grammar_sampler_test against the same llama.cpp checkout as the app;
# copied_pages_test needs no llama.cpp. Gradle doesn't build them.

cmake_minimum_required(VERSION 3.22)

project("grammar-sampler-test" LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED true)

set(LLAMA_SRC ${CMAKE_CURRENT_LIST_DIR}/../../../../third_party/llama.cpp CACHE PATH "llama.cpp source tree")

# Only the libraries the test links: no tools, server, tests, HTTPS or GPU backends.
set(BUILD_SHARED_LIBS    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON   ON  CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER   OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP      OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_UI       OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL        OFF CACHE BOOL "" FORCE)
set(GGML_METAL           OFF CACHE BOOL "" FORCE)
set(GGML_BLAS            OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP          OFF CACHE BOOL "" FORCE)
add_subdirectory(${LLAMA_SRC} build-llama)

add_executable(grammar_sampler_test
        grammar_sampler_test.cpp
        ../../main/cpp/grammar_sampler.cpp)

target_include_directories(grammar_sampler_test PRIVATE ../../main/cpp)

target_link_libraries(grammar_sampler_test PRIVATE llama-common llama)

# The page arithmetic of dropcopied (copied_pages.h), on the two models' tensor tables.
add_executable(copied_pages_test
        copied_pages_test.cpp)

target_include_directories(copied_pages_test PRIVATE ../../main/cpp)
