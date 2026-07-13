#!/usr/bin/env bash
# Build wang.harlon.quickjs JVM native library into appResources/<platform>/lib/
#
# Usage:
#   ./scripts/build-quickjs-native.sh [macos-arm64|macos-x64|linux-x64|linux-arm64|windows-x64]
#
# Env:
#   QUICKJS_TAG   default 3.2.3
#   JAVA_HOME     required (JNI headers)
#   REPO_ROOT     optional; defaults to repo root from script location
set -euo pipefail

QUICKJS_TAG="${QUICKJS_TAG:-3.2.3}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="${REPO_ROOT:-$(cd "$SCRIPT_DIR/.." && pwd)}"

detect_platform() {
  local os arch
  os="$(uname -s | tr '[:upper:]' '[:lower:]')"
  arch="$(uname -m)"
  case "$os" in
    darwin)
      if [[ "$arch" == "arm64" ]]; then echo "macos-arm64"; else echo "macos-x64"; fi
      ;;
    linux)
      if [[ "$arch" == "aarch64" || "$arch" == "arm64" ]]; then echo "linux-arm64"; else echo "linux-x64"; fi
      ;;
    mingw*|msys*|cygwin*)
      echo "windows-x64"
      ;;
    *)
      echo "Unsupported OS: $os" >&2
      exit 1
      ;;
  esac
}

PLATFORM="${1:-$(detect_platform)}"
case "$PLATFORM" in
  macos-arm64) LIB_NAME="libquickjs-java-wrapper.dylib"; CMAKE_ARCH="arm64" ;;
  macos-x64)   LIB_NAME="libquickjs-java-wrapper.dylib"; CMAKE_ARCH="x86_64" ;;
  linux-x64|linux-arm64) LIB_NAME="libquickjs-java-wrapper.so"; CMAKE_ARCH="" ;;
  windows-x64)
    echo "On Windows use scripts/build-quickjs-native.ps1 (Win7-targeted MinGW build)." >&2
    exit 1
    ;;
  *)
    echo "Unknown platform: $PLATFORM" >&2
    exit 1
    ;;
esac

if [[ -z "${JAVA_HOME:-}" ]]; then
  if [[ "$(uname -s)" == "Darwin" ]] && command -v /usr/libexec/java_home >/dev/null 2>&1; then
    JAVA_HOME="$(/usr/libexec/java_home)"
    export JAVA_HOME
  else
    echo "JAVA_HOME is not set" >&2
    exit 1
  fi
fi

if [[ ! -f "$JAVA_HOME/include/jni.h" ]]; then
  echo "jni.h not found under JAVA_HOME=$JAVA_HOME" >&2
  exit 1
fi

command -v cmake >/dev/null 2>&1 || { echo "cmake is required" >&2; exit 1; }
command -v git >/dev/null 2>&1 || { echo "git is required" >&2; exit 1; }

OUT_DIR="$REPO_ROOT/composeApp/src/desktopMain/appResources/$PLATFORM/lib"
mkdir -p "$OUT_DIR"

WORK="$(mktemp -d "${TMPDIR:-/tmp}/quickjs-native.XXXXXX")"
cleanup() { rm -rf "$WORK"; }
trap cleanup EXIT

echo "==> Cloning HarlonWang/quickjs-wrapper@$QUICKJS_TAG"
git clone --depth 1 --branch "$QUICKJS_TAG" --recursive \
  https://github.com/HarlonWang/quickjs-wrapper.git "$WORK/src"

SRC="$WORK/src/wrapper-java"
BUILD="$WORK/build"
mkdir -p "$BUILD"

CMAKE_ARGS=(
  -DCMAKE_BUILD_TYPE=Release
  -S "$SRC/src/main"
  -B "$BUILD"
)

OS_NAME="$(uname -s)"
if [[ "$OS_NAME" == "Darwin" && -n "$CMAKE_ARCH" ]]; then
  CMAKE_ARGS+=(-DCMAKE_OSX_ARCHITECTURES="$CMAKE_ARCH")
fi

# Ensure JNI include dirs (upstream CMakeLists only lists darwin/linux; keep explicit)
JNI_FLAGS="-I${JAVA_HOME}/include"
if [[ "$OS_NAME" == "Darwin" ]]; then
  JNI_FLAGS="$JNI_FLAGS -I${JAVA_HOME}/include/darwin"
else
  JNI_FLAGS="$JNI_FLAGS -I${JAVA_HOME}/include/linux"
fi
CMAKE_ARGS+=(-DCMAKE_C_FLAGS="$JNI_FLAGS" -DCMAKE_CXX_FLAGS="$JNI_FLAGS")

echo "==> cmake configure ($PLATFORM)"
cmake "${CMAKE_ARGS[@]}"

echo "==> cmake build"
cmake --build "$BUILD" --target quickjs-java-wrapper -j "$(sysctl -n hw.ncpu 2>/dev/null || nproc 2>/dev/null || echo 4)"

BUILT=""
for cand in \
  "$BUILD/$LIB_NAME" \
  "$BUILD/lib/$LIB_NAME" \
  "$BUILD/Release/$LIB_NAME" \
  "$BUILD/Debug/$LIB_NAME"
do
  if [[ -f "$cand" ]]; then BUILT="$cand"; break; fi
done
if [[ -z "$BUILT" ]]; then
  echo "Built library not found (expected $LIB_NAME). Contents of $BUILD:" >&2
  find "$BUILD" -maxdepth 3 -type f | head -50 >&2
  exit 1
fi

cp -f "$BUILT" "$OUT_DIR/$LIB_NAME"
chmod +x "$OUT_DIR/$LIB_NAME" 2>/dev/null || true
echo "==> Installed $OUT_DIR/$LIB_NAME"
file "$OUT_DIR/$LIB_NAME" || true
ls -la "$OUT_DIR/$LIB_NAME"
