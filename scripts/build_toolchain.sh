#!/usr/bin/env bash
set -eo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
BUILDSCRIPTS_DIR="$REPO_ROOT/third_party/buildscripts"

if [ ! -f "$BUILDSCRIPTS_DIR/shared/packages.sh" ]; then
    echo "ERROR: third_party/buildscripts submodule is not initialized." >&2
    exit 1
fi

ABIS="arm64-v8a"
CLEAN_BUILD=0
TARGET_API="${EASYRPG_TARGET_API:-21}"
NDK_VERSION="${ANDROID_NDK_VERSION:-28.2.13676358}"

usage() {
    cat <<EOF
Usage: $0 [--abis arm64-v8a,armeabi-v7a,x86_64] [--clean]
Builds EasyRPG Android static dependency toolchains into .toolchains/<abi>-toolchain.
EOF
}

while [ $# -gt 0 ]; do
    case "$1" in
        --abis)
            ABIS="${2:?Missing value for --abis}"
            shift 2
            ;;
        --abis=*)
            ABIS="${1#--abis=}"
            shift
            ;;
        --clean)
            CLEAN_BUILD=1
            shift
            ;;
        -h|--help)
            usage
            exit 0
            ;;
        *)
            echo "Unknown option: $1" >&2
            usage
            exit 1
            ;;
    esac
done

resolve_sdk_root() {
    for candidate in \
        "${ANDROID_HOME:-}" \
        "${ANDROID_SDK_ROOT:-}" \
        "$HOME/Library/Android/sdk" \
        "$HOME/Android/Sdk"; do
        if [ -n "$candidate" ] && [ -d "$candidate" ]; then
            echo "$candidate"
            return 0
        fi
    done
    return 1
}

SDK_ROOT="$(resolve_sdk_root || true)"
if [ -z "$SDK_ROOT" ]; then
    echo "ERROR: Android SDK not found. Set ANDROID_HOME or ANDROID_SDK_ROOT." >&2
    exit 1
fi

resolve_ndk_dir() {
    if [ -d "$SDK_ROOT/ndk/$NDK_VERSION/toolchains/llvm/prebuilt" ]; then
        echo "$SDK_ROOT/ndk/$NDK_VERSION"
        return 0
    fi
    if [ -n "${ANDROID_NDK_HOME:-}" ] && [ -d "$ANDROID_NDK_HOME/toolchains/llvm/prebuilt" ]; then
        echo "$ANDROID_NDK_HOME"
        return 0
    fi
    local fallback
    fallback="$(ls -1d "$SDK_ROOT/ndk"/28.* 2>/dev/null | sort -t. -k1,1n -k2,2n -k3,3n | tail -n 1 || true)"
    if [ -n "$fallback" ] && [ -d "$fallback/toolchains/llvm/prebuilt" ]; then
        echo "$fallback"
        return 0
    fi
    return 1
}

ANDROID_NDK="$(resolve_ndk_dir || true)"
if [ -z "$ANDROID_NDK" ]; then
    echo "ERROR: Complete Android NDK $NDK_VERSION (or 28.x) not found under $SDK_ROOT/ndk" >&2
    exit 1
fi
echo "Using Android NDK: $ANDROID_NDK"

HOST_OS="$(uname)"
if [ "$HOST_OS" = "Darwin" ]; then
    NPROC="$(getconf _NPROCESSORS_ONLN)"
    if [ -d "$ANDROID_NDK/toolchains/llvm/prebuilt/darwin-x86_64" ]; then
        NDK_ARCH="darwin-x86_64"
    elif [ -d "$ANDROID_NDK/toolchains/llvm/prebuilt/darwin-arm64" ]; then
        NDK_ARCH="darwin-arm64"
    else
        echo "ERROR: Unsupported macOS NDK prebuilt directory in $ANDROID_NDK" >&2
        exit 1
    fi
elif [ "$HOST_OS" = "Linux" ]; then
    NPROC="$(nproc)"
    NDK_ARCH="linux-x86_64"
else
    echo "ERROR: Unsupported host OS: $HOST_OS" >&2
    exit 1
fi

NDK_BIN="$ANDROID_NDK/toolchains/llvm/prebuilt/$NDK_ARCH/bin"
if [ ! -x "$NDK_BIN/clang" ]; then
    echo "ERROR: clang not found in $NDK_BIN" >&2
    exit 1
fi

# Ensure autotools finds glibtoolize on macOS if libtoolize is absent.
if ! command -v libtoolize >/dev/null 2>&1 && command -v glibtoolize >/dev/null 2>&1; then
    export LIBTOOLIZE=glibtoolize
fi

for tool in cmake git curl tar unzip patch perl pkg-config autoconf automake meson ninja make; do
    if ! command -v "$tool" >/dev/null 2>&1; then
        echo "ERROR: Required build tool '$tool' is missing from PATH." >&2
        exit 1
    fi
done

TOOLCHAIN_BASE="$REPO_ROOT/.toolchains"
BUILD_DIR="$TOOLCHAIN_BASE/build"
DOWNLOAD_CACHE="$TOOLCHAIN_BASE/downloads"

if [ "$CLEAN_BUILD" -eq 1 ]; then
    rm -rf "$BUILD_DIR"
    IFS=',' read -r -a _clean_abis <<< "$ABIS"
    for abi in "${_clean_abis[@]}"; do
        rm -rf "$TOOLCHAIN_BASE/${abi}-toolchain"
    done
fi

mkdir -p "$BUILD_DIR" "$DOWNLOAD_CACHE"

export WORKSPACE="$BUILD_DIR"
export EASYRPG_PATH_DOWNLOADCACHE="$DOWNLOAD_CACHE"
export BUILD_LIBLCF=1
export ANDROID_HOME="$SDK_ROOT"
export ANDROID_SDK_ROOT="$SDK_ROOT"
export ANDROID_NDK="$ANDROID_NDK"
export ANDROID_NDK_HOME="$ANDROID_NDK"
export MAKEFLAGS="-j${NPROC:-2}"

# Import upstream definitions in read-only mode.
_SAVED_SCRIPT_DIR="${SCRIPT_DIR}"
SCRIPT_DIR="$BUILDSCRIPTS_DIR/android"
# shellcheck source=/dev/null
source "$BUILDSCRIPTS_DIR/shared/common.sh"
# shellcheck source=/dev/null
source "$BUILDSCRIPTS_DIR/shared/packages.sh"
SCRIPT_DIR="$_SAVED_SCRIPT_DIR"

# Validate downloaded archives so HTML error pages never poison the download cache.
is_valid_archive() {
    local archive="$1"
    [ -s "$archive" ] || return 1
    case "$archive" in
        *.zip)
            unzip -tq "$archive" >/dev/null 2>&1
            ;;
        *.tar.gz|*.tgz)
            tar -tzf "$archive" >/dev/null 2>&1
            ;;
        *.tar.bz2)
            tar -tjf "$archive" >/dev/null 2>&1
            ;;
        *.tar.xz)
            tar -tJf "$archive" >/dev/null 2>&1
            ;;
        *)
            tar -tf "$archive" >/dev/null 2>&1
            ;;
    esac
}

download_verified() {
    local url="$1"
    local dest="$2"
    local file="${url##*/}"
    local tmp="${dest}.tmp"
    local mirror_url="https://easyrpg.org/downloads/sources/${file}"

    rm -f "$tmp"
    if [ "${USE_EASYRPG_MIRROR:-0}" != "1" ]; then
        if curl --fail -sSL --retry 3 --connect-timeout 10 -o "$tmp" "$url" && is_valid_archive "$tmp"; then
            mv -f "$tmp" "$dest"
            return 0
        fi
        rm -f "$tmp"
    fi

    echo "Falling back to EasyRPG mirror for $file..."
    if curl --fail -sSL --retry 3 --connect-timeout 15 -o "$tmp" "$mirror_url" && is_valid_archive "$tmp"; then
        mv -f "$tmp" "$dest"
        return 0
    fi
    rm -f "$tmp"
    echo "ERROR: Failed to download a valid archive for $file" >&2
    return 1
}

# Override download_and_extract to populate DOWNLOAD_CACHE on cache miss.
cached_download_and_extract() {
    local url="$1"
    local file="${url##*/}"
    msg "Preparing $file..."
    if [ -e "$DOWNLOAD_CACHE/$file" ] && ! is_valid_archive "$DOWNLOAD_CACHE/$file"; then
        echo "Removing corrupted cached archive $file..."
        rm -f "$DOWNLOAD_CACHE/$file"
    fi
    if [ ! -e "$DOWNLOAD_CACHE/$file" ]; then
        download_verified "$url" "$DOWNLOAD_CACHE/$file"
    fi
    cp "$DOWNLOAD_CACHE/$file" "$BUILD_DIR/$file"
    extract "$BUILD_DIR/$file"
    rm -f "$BUILD_DIR/$file"
}

cd "$BUILD_DIR"

if [ ! -f "$BUILD_DIR/.sources-prepared" ]; then
    msg "[1/4] Downloading & extracting dependencies into $BUILD_DIR..."
    rm -rf "$ZLIB_DIR" "$LIBPNG_DIR" "$FREETYPE_DIR" "$HARFBUZZ_DIR" \
        "$PIXMAN_DIR" "$EXPAT_DIR" "$LIBOGG_DIR" "$LIBVORBIS_DIR" \
        "$MPG123_DIR" "$LIBSNDFILE_DIR" "$LIBXMP_LITE_DIR" "$SPEEXDSP_DIR" \
        "$WILDMIDI_DIR" "$OPUS_DIR" "$OPUSFILE_DIR" "$FLUIDSYNTH_DIR" \
        "$NLOHMANNJSON_DIR" "$INIH_DIR" "$LHASA_DIR" "$FMT_DIR" \
        "$ICU_DIR" $ICUDATA_FILES

    cached_download_and_extract "$ZLIB_URL"
    cached_download_and_extract "$LIBPNG_URL"
    cached_download_and_extract "$FREETYPE_URL"
    cached_download_and_extract "$HARFBUZZ_URL"
    cached_download_and_extract "$PIXMAN_URL"
    cached_download_and_extract "$EXPAT_URL"
    cached_download_and_extract "$LIBOGG_URL"
    cached_download_and_extract "$LIBVORBIS_URL"
    cached_download_and_extract "$MPG123_URL"
    cached_download_and_extract "$LIBSNDFILE_URL"
    cached_download_and_extract "$LIBXMP_LITE_URL"
    cached_download_and_extract "$SPEEXDSP_URL"
    cached_download_and_extract "$WILDMIDI_URL"
    cached_download_and_extract "$OPUS_URL"
    cached_download_and_extract "$OPUSFILE_URL"
    cached_download_and_extract "$FLUIDSYNTH_URL"
    cached_download_and_extract "$NLOHMANNJSON_URL"
    cached_download_and_extract "$INIH_URL"
    cached_download_and_extract "$LHASA_URL"
    cached_download_and_extract "$FMT_URL"
    cached_download_and_extract "$ICU_URL"
    cached_download_and_extract "$ICUDATA_URL"

    if [ ! -d "$BUILD_DIR/liblcf/.git" ]; then
        download_liblcf
    fi

    msg "[2/4] Applying buildscripts patches inside $BUILD_DIR..."
    patches_common
    (cd "$PIXMAN_DIR" && patch -Np1 < "$BUILDSCRIPTS_DIR/android/pixman-cpufeatures.patch")

    touch "$BUILD_DIR/.sources-prepared"
else
    msg "[1/4 & 2/4] Reusing prepared sources in $BUILD_DIR"
    if [ ! -d "$BUILD_DIR/liblcf/.git" ]; then
        download_liblcf
    fi
fi

test_ccache

if [ ! -f "$BUILD_DIR/icu-native/config/icucross.mk" ]; then
    msg "[3/4] Building host ICU..."
    install_lib_icu_native
else
    msg "[3/4] Reusing host ICU in $BUILD_DIR/icu-native"
fi

# Restore SCRIPT_DIR so common.sh make_meson_cross locates $BUILDSCRIPTS_DIR/shared/mk-meson-cross.sh
SCRIPT_DIR="$BUILDSCRIPTS_DIR/android"
export OLD_PATH="$ANDROID_NDK:$NDK_BIN:$PATH"
export ac_cv_func_mmap_fixed_mapped=yes
export CMAKE_SYSTEM_NAME=Android

build_abi() {
    local name="$1"
    local abi="$2"
    local target_host="$3"
    local extra_cppflags="$4"

    cd "$BUILD_DIR"
    msg "[4/4] Building $name ($abi) toolchain -> $TOOLCHAIN_BASE/${abi}-toolchain"

    export PATH="$OLD_PATH"
    export PLATFORM_PREFIX="$TOOLCHAIN_BASE/${abi}-toolchain"
    export PATH="$PLATFORM_PREFIX/bin:$PATH"

    export AR="$NDK_BIN/llvm-ar"
    export NM="$NDK_BIN/llvm-nm"
    export RANLIB="$NDK_BIN/llvm-ranlib"

    export CFLAGS="-g0 -O2 -fPIC -std=gnu17 $extra_cppflags"
    export CXXFLAGS="-g0 -O2 -fPIC $extra_cppflags"
    export CPPFLAGS="-I$PLATFORM_PREFIX/include -I$ANDROID_NDK/sources/android/cpufeatures"
    export LDFLAGS="-L$PLATFORM_PREFIX/lib"
    unset PKG_CONFIG_PATH || true
    export PKG_CONFIG_LIBDIR="$PLATFORM_PREFIX/lib/pkgconfig"
    export TARGET_HOST="$target_host"
    export CC="clang -target ${TARGET_HOST}${TARGET_API}"
    export CXX="clang++ -target ${TARGET_HOST}${TARGET_API}"

    export CMAKE_EXTRA_ARGS="-DCMAKE_ANDROID_ARCH_ABI=$abi -DCMAKE_ANDROID_API=$TARGET_API -DCMAKE_ANDROID_NDK=$ANDROID_NDK -DCMAKE_FIND_ROOT_PATH=$PLATFORM_PREFIX"

    mkdir -p "$PLATFORM_PREFIX"
    rm -f "$PLATFORM_PREFIX/config.cache"
    make_meson_cross "${TARGET_HOST}${TARGET_API}" > "$PLATFORM_PREFIX/meson-cross.txt"

    install_lib_cmake "$ZLIB_DIR" $ZLIB_ARGS
    install_lib_cmake "$LIBPNG_DIR" $LIBPNG_ARGS
    install_lib_cmake "$FREETYPE_DIR" $FREETYPE_ARGS -DFT_DISABLE_HARFBUZZ=ON
    install_lib_meson "$HARFBUZZ_DIR" $HARFBUZZ_ARGS
    install_lib_cmake "$FREETYPE_DIR" $FREETYPE_ARGS -DFT_DISABLE_HARFBUZZ=OFF
    install_lib_meson "$PIXMAN_DIR" $PIXMAN_ARGS
    install_lib_cmake "$EXPAT_DIR" $EXPAT_ARGS
    install_lib "$LIBOGG_DIR"
    install_lib "$LIBVORBIS_DIR"
    install_lib "$MPG123_DIR" $MPG123_ARGS
    install_lib "$LIBSNDFILE_DIR" $LIBSNDFILE_ARGS
    install_lib_cmake "$LIBXMP_LITE_DIR" $LIBXMP_LITE_ARGS
    install_lib "$SPEEXDSP_DIR" $SPEEXDSP_ARGS
    install_lib_cmake "$WILDMIDI_DIR" $WILDMIDI_ARGS
    install_lib_cmake "$OPUS_DIR" $OPUS_ARGS
    install_lib "$OPUSFILE_DIR" $OPUSFILE_ARGS
    install_lib_cmake "$FLUIDSYNTH_DIR" $FLUIDSYNTH_ARGS -Dosal=cpp11 -DANDROID_ABI="$TARGET_API"
    install_lib_cmake "$NLOHMANNJSON_DIR" $NLOHMANNJSON_ARGS
    install_lib_meson "$INIH_DIR"
    install_lib "$LHASA_DIR"
    install_lib_cmake "$FMT_DIR" $FMT_ARGS

    rm -rf "$BUILD_DIR/icu-cross"
    install_lib_icu_cross
    install_lib_liblcf

    rm -f "$PLATFORM_PREFIX"/lib/*.la
    rm -rf "$PLATFORM_PREFIX/sbin" "$PLATFORM_PREFIX/share"
}

IFS=',' read -r -a REQUESTED_ABIS <<< "$ABIS"
for abi in "${REQUESTED_ABIS[@]}"; do
    case "$abi" in
        armeabi-v7a)
            build_abi "ARMeabi-v7a" "armeabi-v7a" "armv7a-linux-androideabi" "-march=armv7-a -mfloat-abi=softfp -mfpu=vfpv3"
            ;;
        arm64-v8a)
            build_abi "AArch64" "arm64-v8a" "aarch64-linux-android" ""
            ;;
        x86)
            build_abi "x86" "x86" "i686-linux-android" ""
            ;;
        x86_64)
            build_abi "x86_64" "x86_64" "x86_64-linux-android" ""
            ;;
        *)
            echo "ERROR: Unsupported ABI '$abi'" >&2
            exit 1
            ;;
    esac
done

echo "Toolchain build complete for ABIs: $ABIS"
