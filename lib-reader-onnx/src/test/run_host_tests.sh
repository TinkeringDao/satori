#!/bin/sh
# The reader's host tests on a Mac (Apple Silicon).
#
#   lib-reader-onnx/src/test/run_host_tests.sh <ort dir> <python> [<work dir>]
#
# <ort dir>   onnxruntime-osx-arm64-1.28.2 unpacked: the archive of ONNX Runtime's v1.28.2 GitHub
#             release, its sha256 checked against the release's digest
#             (c4fceacfc53765d0869dc9180c31ec91054d149017a99d1e80ffe28dc79596de).
# <python>    a Python, best a throwaway venv, with numpy, onnx, onnxruntime and
#             sentencepiece==0.2.1 from pypi. pypi has no onnxruntime 1.28.2; 1.28.0 makes the
#             reference (1.27.0 and 1.29.0 make the same one).
# <work dir>  where the toy models, the reference and the builds go; a new folder in /tmp if left out.
#
# 1. The toy graphs (python/toy_models.py), the reference beam search over them
#    (python/toy_reference.py) and a toy SentencePiece model with its pieces (python/toy_spm.py).
# 2. cpp/reader_host_test under ASan and UBSan, which must pass.
# 3. libreader-onnx.dylib (cpp, READER_JNI=ON) for the JVM test. With GRADLE set to the command
#    that runs this checkout's Gradle (./gradlew, say), ReaderEngineHostTest then runs through it.
# CMAKE overrides the CMake (default: the Android SDK's 3.31.6, whose folder also has ninja).

set -eu

if [ $# -lt 2 ]; then
    sed -n '2,20p' "$0"
    exit 2
fi
ORT=$1
PY=$2
WORK=${3:-$(mktemp -d /tmp/reader-host-XXXXXX)}
HERE=$(cd "$(dirname "$0")" && pwd)
ROOT=$(cd "$HERE/../../.." && pwd)
CMAKE=${CMAKE:-${ANDROID_HOME:-$HOME/Library/Android/sdk}/cmake/3.31.6/bin/cmake}
PATH=$(dirname "$CMAKE"):$PATH
export PATH
mkdir -p "$WORK"

echo "== toy models, reference and tokenizer in $WORK"
"$PY" -I "$HERE/python/toy_models.py" "$WORK/models"
"$PY" -I "$HERE/python/toy_reference.py" "$WORK/models" "$WORK/expected.tsv"
"$PY" -I "$HERE/python/toy_spm.py" "$WORK/spm"

echo "== reader_host_test under ASan and UBSan"
"$CMAKE" -G Ninja -S "$HERE/cpp" -B "$WORK/build-asan" -DCMAKE_BUILD_TYPE=RelWithDebInfo \
    -DORT_DIR="$ORT" -DREADER_SANITIZE=ON > "$WORK/build-asan.log"
"$CMAKE" --build "$WORK/build-asan" --target reader_host_test >> "$WORK/build-asan.log"
ASAN_OPTIONS=halt_on_error=1 UBSAN_OPTIONS=print_stacktrace=1:halt_on_error=1 \
    "$WORK/build-asan/reader_host_test" "$WORK/models" "$WORK/expected.tsv" "$WORK/spm"

echo "== libreader-onnx.dylib for the JVM test"
"$CMAKE" -G Ninja -S "$HERE/cpp" -B "$WORK/build-jni" -DCMAKE_BUILD_TYPE=Release \
    -DORT_DIR="$ORT" -DREADER_SANITIZE=OFF -DREADER_JNI=ON > "$WORK/build-jni.log"
"$CMAKE" --build "$WORK/build-jni" --target reader-onnx >> "$WORK/build-jni.log"
SATORI_READER_HOST_LIB_DIR="$WORK/build-jni"
SATORI_READER_TOYS="$WORK"
export SATORI_READER_HOST_LIB_DIR SATORI_READER_TOYS
if [ -n "${GRADLE:-}" ]; then
    echo "== ReaderEngineHostTest through the JNI"
    cd "$ROOT"
    # --rerun: Gradle does not see the two variables, so it would take an earlier result as current.
    # shellcheck disable=SC2086 # GRADLE may be a command with arguments
    $GRADLE :lib-reader-onnx:testDebugUnitTest --rerun --tests com.satori.reader.ReaderEngineHostTest
else
    echo "GRADLE not set: the JVM test runs with SATORI_READER_HOST_LIB_DIR=$SATORI_READER_HOST_LIB_DIR" \
        "SATORI_READER_TOYS=$SATORI_READER_TOYS"
fi
echo "== host tests passed"
