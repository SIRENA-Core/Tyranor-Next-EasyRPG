#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

if [ $# -lt 1 ]; then
    echo "Usage: $0 <path-to-apk>" >&2
    exit 1
fi

APK_PATH="$1"
if [ ! -f "$APK_PATH" ]; then
    echo "ERROR: APK not found: $APK_PATH" >&2
    exit 1
fi

echo "==> Checking clean upstream submodules..."
for sub in third_party/Player third_party/buildscripts; do
    if [ ! -d "$REPO_ROOT/$sub" ]; then
        echo "ERROR: Missing submodule directory: $sub" >&2
        exit 1
    fi
    dirty="$(git -C "$REPO_ROOT/$sub" status --porcelain)"
    if [ -n "$dirty" ]; then
        echo "ERROR: Submodule $sub is dirty!" >&2
        echo "$dirty" >&2
        exit 1
    fi
    echo "    OK: $sub is clean ($(git -C "$REPO_ROOT/$sub" rev-parse --short HEAD))"
done

find_llvm_readelf() {
    if command -v llvm-readelf >/dev/null 2>&1; then
        command -v llvm-readelf
        return 0
    fi
    for sdk in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" "$HOME/Library/Android/sdk" "$HOME/Android/Sdk"; do
        [ -n "$sdk" ] && [ -d "$sdk/ndk" ] || continue
        local found
        found="$(find "$sdk/ndk" -name "llvm-readelf" \( -type f -o -type l \) 2>/dev/null | head -n 1 || true)"
        if [ -n "$found" ]; then
            echo "$found"
            return 0
        fi
    done
    if command -v readelf >/dev/null 2>&1; then
        command -v readelf
        return 0
    fi
    return 1
}

READELF_BIN="$(find_llvm_readelf || true)"
if [ -z "$READELF_BIN" ]; then
    echo "ERROR: Could not locate llvm-readelf or readelf." >&2
    exit 1
fi
echo "==> Using ELF inspector: $READELF_BIN"

TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

unzip -q "$APK_PATH" "lib/*" -d "$TMP_DIR" || {
    echo "ERROR: No native libraries (lib/*) found in $APK_PATH" >&2
    exit 1
}

if [ ! -d "$TMP_DIR/lib" ]; then
    echo "ERROR: Extracted APK has no lib/ directory." >&2
    exit 1
fi

FOUND_EASYRPG=0
FOUND_SDL3=0

for abi_dir in "$TMP_DIR"/lib/*; do
    [ -d "$abi_dir" ] || continue
    abi="$(basename "$abi_dir")"
    echo "==> Inspecting ABI: $abi"

    [ -f "$abi_dir/libeasyrpg_android.so" ] && FOUND_EASYRPG=1
    [ -f "$abi_dir/libSDL3.so" ] && FOUND_SDL3=1

    for so_file in "$abi_dir"/*.so; do
        [ -f "$so_file" ] || continue
        so_name="$(basename "$so_file")"

        debug_sections="$("$READELF_BIN" -S -W "$so_file" | grep -E '\.debug_' || true)"
        if [ -n "$debug_sections" ]; then
            echo "ERROR: Unstripped .debug_* sections found in $abi/$so_name:" >&2
            echo "$debug_sections" >&2
            exit 1
        fi

        # Verify ELF PT_LOAD alignment (16 KiB = 0x4000 required for 64-bit ABIs).
        load_lines="$("$READELF_BIN" -l -W "$so_file" | awk '$1 == "LOAD" {print $NF}')"
        if [ -z "$load_lines" ]; then
            echo "ERROR: No PT_LOAD segments found in $abi/$so_name" >&2
            exit 1
        fi

        min_align=0
        for align_hex in $load_lines; do
            align_dec=$((align_hex))
            if [ "$min_align" -eq 0 ] || [ "$align_dec" -lt "$min_align" ]; then
                min_align="$align_dec"
            fi
            if [ "$abi" = "arm64-v8a" ] || [ "$abi" = "x86_64" ]; then
                if [ "$align_dec" -lt 16384 ]; then
                    echo "ERROR: $abi/$so_name PT_LOAD align $align_hex ($align_dec bytes) < 16384 (16 KiB)" >&2
                    exit 1
                fi
            fi
        done
        echo "    OK: $abi/$so_name (no .debug_* sections, min PT_LOAD align=${min_align} bytes)"
    done
done

if [ "$FOUND_EASYRPG" -ne 1 ]; then
    echo "ERROR: libeasyrpg_android.so missing from $APK_PATH" >&2
    exit 1
fi
if [ "$FOUND_SDL3" -ne 1 ]; then
    echo "ERROR: libSDL3.so missing from $APK_PATH" >&2
    exit 1
fi

echo "==> APK verification PASSED: $APK_PATH"
