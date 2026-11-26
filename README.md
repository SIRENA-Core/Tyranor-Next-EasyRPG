# Tyranor-Next-EasyRPG

External Android host APK for [EasyRPG Player](https://github.com/EasyRPG/Player) integrated with TyranorNext via the MAGES.-style external engine contract (`com.tyranor.next.easyrpg`).

## Upstream Submodules (Read-Only)

- `third_party/Player`: `https://github.com/EasyRPG/Player.git` pinned at `a99ca834b` (`master`, SDL3 `3.4.20`, AGP `9.5.1`).
- `third_party/buildscripts`: `https://github.com/EasyRPG/buildscripts.git` pinned at `84cf2f4` (`master`).

Neither submodule is modified or patched. All wrapper code lives under `app/` and build automation lives under `scripts/`.

## Protocol Contract

- **Application ID**: `com.tyranor.next.easyrpg`
- **Gradle Namespace**: `org.easyrpg.player` (matches upstream `R` and JNI signatures)
- **Action**: `com.tyranor.next.easyrpg.action.RUN`
- **Extras**:
  - `com.tyranor.next.easyrpg.extra.GAME_DIR` (absolute path to game directory)
  - `com.tyranor.next.easyrpg.extra.TITLE` (display title)
  - `com.tyranor.next.easyrpg.extra.SETTINGS` (flat JSON payload mapped by `EasyRpgSettingsMapper`)

## Building from Source

### Prerequisites

- JDK 17
- Android SDK (`platforms;android-37`, `cmake;3.22.1`, NDK `28.2.13676358` or `28.x`)
- Host tools: `git`, `curl`, `tar`, `unzip`, `patch`, `perl`, `pkg-config`, `autoconf`, `automake`, `libtool` (`glibtoolize` on macOS), `cmake`, `meson`, `ninja`, `make`

### 1. Initialize Submodules

```bash
git submodule update --init --recursive
```

### 2. Prebuild Static Toolchains

```bash
./scripts/build_toolchain.sh --abis arm64-v8a
# Or for multi-ABI release builds:
./scripts/build_toolchain.sh --abis armeabi-v7a,arm64-v8a,x86_64
```

### 3. Run Unit Tests & Build APK

```bash
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug
```

### 4. Verify APK

```bash
./scripts/verify_release_apk.sh app/build/outputs/apk/debug/app-debug.apk
```

## License (GNU GPLv3)

EasyRPG Player and `libeasyrpg_android.so` are licensed under the GNU General Public License v3.0 (see [COPYING](COPYING)). This repository provides the complete corresponding source (pinned upstream submodules, build scripts, and Android wrapper sources) required to rebuild the distributed APK.
