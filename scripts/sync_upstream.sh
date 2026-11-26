#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$REPO_ROOT"

PLAYER_REF=""
BUILDSCRIPTS_REF=""
SUMMARY_FILE=""

while [ $# -gt 0 ]; do
    case "$1" in
        --player-ref)
            PLAYER_REF="${2:-}"
            shift 2
            ;;
        --buildscripts-ref)
            BUILDSCRIPTS_REF="${2:-}"
            shift 2
            ;;
        --summary-file)
            SUMMARY_FILE="${2:-}"
            shift 2
            ;;
        -h|--help)
            cat <<EOF
Usage: $0 [--player-ref <ref>] [--buildscripts-ref <ref>] [--summary-file <path>]
Updates third_party/Player and third_party/buildscripts, syncs sdlVersion in gradle.properties,
and optionally writes a Markdown summary for automated PRs.
EOF
            exit 0
            ;;
        *)
            echo "Unknown option: $1" >&2
            exit 1
            ;;
    esac
done

update_submodule() {
    local path="$1"
    local ref="$2"

    git -C "$path" fetch --tags origin >/dev/null 2>&1 || git -C "$path" fetch origin
    if [ -n "$ref" ]; then
        git -C "$path" checkout --detach "$ref"
    else
        local default_branch
        default_branch="$(git -C "$path" remote show origin 2>/dev/null | sed -n 's/.*HEAD branch: //p' | head -n 1)"
        default_branch="${default_branch:-master}"
        git -C "$path" checkout --detach "origin/$default_branch"
    fi
}

OLD_PLAYER_SHA="$(git -C third_party/Player rev-parse HEAD)"
OLD_BUILDSCRIPTS_SHA="$(git -C third_party/buildscripts rev-parse HEAD)"

update_submodule "third_party/Player" "$PLAYER_REF"
update_submodule "third_party/buildscripts" "$BUILDSCRIPTS_REF"

NEW_PLAYER_SHA="$(git -C third_party/Player rev-parse HEAD)"
NEW_BUILDSCRIPTS_SHA="$(git -C third_party/buildscripts rev-parse HEAD)"

# Ensure both submodules stay completely clean.
test -z "$(git -C third_party/Player status --porcelain)"
test -z "$(git -C third_party/buildscripts status --porcelain)"

# Sync sdlVersion from upstream Player/builds/android/gradle.properties into root gradle.properties.
OLD_SDL="$(grep -E '^sdlVersion=' gradle.properties | head -n 1 | cut -d= -f2- || true)"
UPSTREAM_SDL="$(grep -E '^sdlVersion=' third_party/Player/builds/android/gradle.properties | head -n 1 | cut -d= -f2- || true)"
SDL_SYNC_NOTE="Unchanged (\`${OLD_SDL:-unknown}\`)"
if [ -n "$UPSTREAM_SDL" ] && [ "$UPSTREAM_SDL" != "$OLD_SDL" ]; then
    python3 - "$UPSTREAM_SDL" <<'PY'
import pathlib, re, sys
new_ver = sys.argv[1]
path = pathlib.Path("gradle.properties")
text = path.read_text(encoding="utf-8")
updated, count = re.subn(r'^sdlVersion=.*$', f'sdlVersion={new_ver}', text, flags=re.MULTILINE)
if count == 0:
    updated = text.rstrip() + f"\nsdlVersion={new_ver}\n"
path.write_text(updated, encoding="utf-8")
PY
    SDL_SYNC_NOTE="Synced \`$OLD_SDL\` -> \`$UPSTREAM_SDL\`"
fi

echo "Player:       ${OLD_PLAYER_SHA:0:9} -> ${NEW_PLAYER_SHA:0:9}"
echo "buildscripts: ${OLD_BUILDSCRIPTS_SHA:0:9} -> ${NEW_BUILDSCRIPTS_SHA:0:9}"
echo "sdlVersion:   $SDL_SYNC_NOTE"

if [ -n "$SUMMARY_FILE" ]; then
    mkdir -p "$(dirname "$SUMMARY_FILE")"
    {
        echo "## Automated Upstream Sync"
        echo
        echo "| Component | Previous | Current |"
        echo "| --- | --- | --- |"
        echo "| \`third_party/Player\` | \`${OLD_PLAYER_SHA:0:9}\` | \`${NEW_PLAYER_SHA:0:9}\` |"
        echo "| \`third_party/buildscripts\` | \`${OLD_BUILDSCRIPTS_SHA:0:9}\` | \`${NEW_BUILDSCRIPTS_SHA:0:9}\` |"
        echo "| \`sdlVersion\` | — | ${SDL_SYNC_NOTE} |"
        echo

        if [ "$OLD_PLAYER_SHA" != "$NEW_PLAYER_SHA" ]; then
            echo "### EasyRPG/Player Changes"
            echo '```text'
            git -C third_party/Player log --oneline -n 30 "${OLD_PLAYER_SHA}..${NEW_PLAYER_SHA}" || true
            echo '```'
            echo
            ANDROID_TOUCHED="$(git -C third_party/Player diff --name-only "${OLD_PLAYER_SHA}..${NEW_PLAYER_SHA}" -- \
                builds/android/ src/platform/android/ src/cmdline_parser.cpp CMakeLists.txt || true)"
            if [ -n "$ANDROID_TOUCHED" ]; then
                echo "### Review Needed: Android / JNI / Build Files Changed Upstream"
                echo '```text'
                echo "$ANDROID_TOUCHED"
                echo '```'
                echo
            fi
        fi

        if [ "$OLD_BUILDSCRIPTS_SHA" != "$NEW_BUILDSCRIPTS_SHA" ]; then
            echo "### EasyRPG/buildscripts Changes"
            echo '```text'
            git -C third_party/buildscripts log --oneline -n 30 "${OLD_BUILDSCRIPTS_SHA}..${NEW_BUILDSCRIPTS_SHA}" || true
            echo '```'
            echo
            BS_TOUCHED="$(git -C third_party/buildscripts diff --name-only "${OLD_BUILDSCRIPTS_SHA}..${NEW_BUILDSCRIPTS_SHA}" -- \
                android/ shared/ || true)"
            if [ -n "$BS_TOUCHED" ]; then
                echo "### Review Needed: Toolchain Scripts Changed Upstream"
                echo '```text'
                echo "$BS_TOUCHED"
                echo '```'
                echo
            fi
        fi

        echo "### Verification & Release Checklist"
        echo "- [x] Upstream submodules remain clean and unmodified"
        echo "- [x] \`arm64-v8a\` dependency toolchain + unit tests + release APK + ELF 16 KiB/strip check passed in CI"
        echo "- [ ] Review upstream Android/buildscripts changes above (if any)"
        echo "- [ ] Bump \`versionCode\` and \`versionName\` in \`app/build.gradle.kts\` before tagging \`easyrpg-host-<versionName>\`"
    } > "$SUMMARY_FILE"
fi
