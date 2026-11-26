# EasyRPG/Player 接入 TyranorNext 外置引擎模块计划（MAGES. 模式）

> **目标**：参照 [TyranorNext](file:///Users/weiss/opencode/rma/TyranorNext) 中 **MAGES.（`EngineType.IMPACTO`）** 的外置 APK 引擎架构与内置更新检测机制，在不修改上游 `EasyRPG/Player`（及 `EasyRPG/buildscripts`）的前提下构建宿主 APK 仓库 `tyn-easyrpg`，并在 `TyranorNext` 主仓库完成注册、扫描、全局/单游戏设置、更新检测与下载接入。
>
> **核心参考**：
> - MAGES. 接入记录：[docs/impacto-upstream-only.md](file:///Users/weiss/opencode/rma/TyranorNext/docs/impacto-upstream-only.md)
> - 外置模块契约与更新检测：[ExternalEngineModule.kt](file:///Users/weiss/opencode/rma/TyranorNext/app/src/main/java/com/tyranor/next/core/engine/external/ExternalEngineModule.kt)、[ImpactoExternalEngineModule.kt](file:///Users/weiss/opencode/rma/TyranorNext/app/src/main/java/com/tyranor/next/core/engine/external/ImpactoExternalEngineModule.kt)、[ExternalEngineUpdateChecker.kt](file:///Users/weiss/opencode/rma/TyranorNext/app/src/main/java/com/tyranor/next/core/engine/external/ExternalEngineUpdateChecker.kt)、[ExternalEngineLauncher.kt](file:///Users/weiss/opencode/rma/TyranorNext/app/src/main/java/com/tyranor/next/core/engine/external/ExternalEngineLauncher.kt)
> - 引擎页与更新跳转：[EngineScreen.kt](file:///Users/weiss/opencode/rma/TyranorNext/app/src/main/java/com/tyranor/next/ui/engine/EngineScreen.kt)
> - 宿主仓库：`https://github.com/Weiss-UltimateSavior/Tyranor-Next-EasyRPG`（本地工作区 `/Volumes/HUAWEI-M2LITE/app/tyn-easyrpg`）
> - 上游仓库：`https://github.com/EasyRPG/Player`（已调研 `a99ca83`）、`https://github.com/EasyRPG/buildscripts`（已调研 `84cf2f4`）

---

## 1. 架构定位与核心约束

### 1.1 为什么采用“独立宿主 APK + 主 App 外置引擎注册”（MAGES. 模式）
1. **SDL3 / SDL2 类名冲突（硬约束）**：
   - `EasyRPG/Player` Android 端使用 **SDL3**（`SDL3-3.4.20.aar`，Java 包 `org.libsdl.app`），与 TyranorNext 主 App 内置的 SDL2 同名 Java 类直接冲突，**无法合入主 App 进程**（与 MAGES./impacto 原因完全一致）。
2. **不修改上游仓库（硬约束）**：
   - `EasyRPG/Player` 与 `EasyRPG/buildscripts` 均以只读 Git Submodule 引入 `tyn-easyrpg` 仓库，零源码 patch、不碰上游 SDL3 Java 层与 JNI 命名空间。
3. **体验与能力收益（相比纯 `com.tyranor.runtime.*` 第三方插件模式）**：
   - **未安装宿主 APK 即可扫出游戏**：主 App 内置识别 RPG Maker 2000/2003 游戏，用户点击启动时直接提示未安装并引导下载。
   - **内置更新检测与版本门控**：`EngineScreen` 与游戏操作抽屉自动检测 GitHub Releases 新版本；`ExternalEngineLauncher` 支持 `minimumVersionCode` 拦截旧版宿主。
   - **全局 + 单游戏两级设置**：支持在主 App 全局「引擎设置」配置默认值，再按单游戏覆盖。
   - **独立品牌卡片**：在引擎页 `RPGM` 分页展示专属 `EasyRPG` 图标与安装状态，而非通用 `Plugin`。
4. **开源许可隔离（GPL-3.0）**：
   - `EasyRPG/Player` 及产出的 `libeasyrpg_android.so` 为 **GNU GPLv3**。通过独立 APK + Android `Intent` 与 GPL-2.0-only 的 TyranorNext 隔离通信；在公开的 `tyn-easyrpg` 仓库发布 APK 时随附构建脚本、包装层源码及固定 Submodule commit 即可满足 GPLv3 合规。

---

## 2. 双仓库分工总览

| 仓库 | 职责 | 上游源码改动 |
| --- | --- | --- |
| **宿主 APK 仓库**（`/Volumes/HUAWEI-M2LITE/app/tyn-easyrpg`） | 预编译依赖工具链、CMake 封装上游 `easyrpg_android`、实现 `com.tyranor.next.easyrpg` 宿主 APK、按规范发布 GitHub Release | **无**（`third_party/Player`、`third_party/buildscripts` 完全只读） |
| **主 App 仓库**（`/Users/weiss/opencode/rma/TyranorNext`） | 新增 `EngineType.EASYRPG`、`EasyRpgExternalEngineModule`、扫描识别、全局/单游戏设置、引擎页卡片与更新跳转（及可选：存档接入） | 仅改 TyranorNext 自身代码 |

---

## 3. 宿主 APK 仓库设计（`tyn-easyrpg`）

### 3.1 仓库目录布局
```text
tyn-easyrpg/
├── third_party/
│   ├── Player/                         # [Submodule] https://github.com/EasyRPG/Player (只读，不修改)
│   └── buildscripts/                   # [Submodule] https://github.com/EasyRPG/buildscripts (只读，不修改)
├── app/
│   ├── build.gradle.kts                # applicationId="com.tyranor.next.easyrpg", namespace="org.easyrpg.player"
│   └── src/
│       └── main/
│           ├── AndroidManifest.xml     # 权限 + 响应 com.tyranor.next.easyrpg.action.RUN + 上游 Activity 保留
│           ├── java/
│           │   └── com/tyranor/next/easyrpg/
│           │       ├── EasyRpgHostContract.kt       # 与主 App 同步的包名/Action/Extra 常量
│           │       ├── LaunchActivity.kt            # 校验路径/权限，解析 SETTINGS，组装 CLI 并拉起内核
│           │       ├── EasyRpgSettingsMapper.kt     # JSON 设置白名单解析与 CLI 参数映射
│           │       ├── StoragePermissionHelper.kt   # API 30+ MANAGE_EXTERNAL_STORAGE / API <30 权限引导
│           │       └── TynEasyRpgPlayerActivity.kt  # 继承 EasyRpgPlayerActivity，按需覆写 getRtpPath()
│           └── res/
│               └── values*/strings.xml              # 模块名、权限提示、错误弹窗文案
├── gradle/
│   ├── wrapper/                        # Gradle 9.x Wrapper
│   └── libs.versions.toml
├── scripts/
│   ├── build_toolchain.sh              # 调用 third_party/buildscripts/android 预编译各 ABI 工具链
│   └── verify_release_apk.sh           # 校验上游干净、llvm-strip 无调试段、16 KiB ELF 对齐
├── .github/workflows/
│   └── release.yml                     # 自动构建签名 APK 并按 ExternalEngineUpdateChecker 规范发版
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties                   # 对齐上游 sdlVersion=3.4.20 等
├── COPYING                             # GPL-3.0 声明
└── PLAN.md
```

### 3.2 零修改复用上游的核心技术方案
1. **Gradle `namespace` 与 `applicationId` 分离**：
   - `applicationId = "com.tyranor.next.easyrpg"`（宿主包名，与 `com.tyranor.next.impacto` 同构）。
   - `namespace = "org.easyrpg.player"`（与上游 Java 导入的 `org.easyrpg.player.R` 及 C++ JNI 硬编码查找的 `org/easyrpg/player/player/EasyRpgPlayerActivity` 完全匹配）。
   - 通过 `sourceSets.main` 直接挂载 `third_party/Player/builds/android/app/src/main/java` 与 `res`，将 `manifest.srcFile` 指向包装层自己的 `AndroidManifest.xml`（去掉桌面 `LAUNCHER` 图标入口，补充存储权限）。
2. **CMake 与预编译工具链**：
   - `externalNativeBuild.cmake.path` 指向 `../third_party/Player/CMakeLists.txt`，传入 `-DPLAYER_GRADLE_BUILD=ON`、`-DBUILD_SHARED_LIBS=ON`、`-DPLAYER_ENABLE_TESTS=OFF`、`-DPLAYER_ANDROID_TOOLCHAIN_PATH=<toolchain_dir>`，`targets` 仅构建 `"easyrpg_android"`（无需内置游戏浏览器的 `libgamebrowser.so`）。
   - 本地工具链已验证就绪：NDK `28.2.13676358`（与上游 `builds/android/app/build.gradle` 一致）、`cmake`、`ninja`、`java`、`adb`。
   - **显式 Strip 与 16 KiB 对齐检查**（吸收 MAGES. 实机教训）：在打包阶段用 NDK `llvm-strip --strip-unneeded` 确认 `libeasyrpg_android.so` / `libSDL3.so` 无 `.debug_*` 残留且满足 16 KiB ELF `LOAD` 页对齐。
3. **存储与路径直读**：
   - `AndroidManifest.xml` 声明 `MANAGE_EXTERNAL_STORAGE`、`READ_EXTERNAL_STORAGE`/`WRITE_EXTERNAL_STORAGE`（`maxSdkVersion="29"`）及 `requestLegacyExternalStorage="true"`，并保留上游 `VIBRATE`、`BLUETOOTH`、`BLUETOOTH_CONNECT`、手柄与 OpenGL ES 2.0 声明。
   - 上游 `RootFilesystem::Create` 对原生绝对路径走 `NativeFilesystem`，无需 SAF `content://`，`LaunchActivity` 在运行前确认授权即可。
4. **存档与配置隔离**：
   - 核查上游 `src/player.cpp:589` 与 `src/filesystem.cpp:93` 可知：若 `--save-path` 目录不存在，`FileFinder::Root().Create(...)` 会报错 `Output::Error("Invalid --save-path ...")` 退出。
   - 因此 `LaunchActivity` 在启动前校验 `<GAME_DIR>` 可读、非异常符号链接后，确保 `<GAME_DIR>/saves` 存在（`mkdirs()`），再传递 `--save-path <GAME_DIR>/saves`。
   - 同时传入 `--config-path <getExternalFilesDir(null)>` 与 `--log-file <getExternalFilesDir(null)>/easyrpg-player.log`，防止 `config.ini` 与日志污染游戏目录。
5. **RTP 扩展**：
   - 上游 `src/filefinder_rtp.cpp` 在 Android 忽略 `--rtp-path`，通过 JNI 调用活动实例的 `getRtpPath()`。
   - 由 `TynEasyRpgPlayerActivity : EasyRpgPlayerActivity()` 覆写 `getRtpPath()` 返回 `<getExternalFilesDir(null)>/rtp` 或 `/sdcard/EasyRPG/rtp`（在用户未开启 `no_rtp` 且目录存在时生效），无需修改上游 C++。

### 3.3 宿主 Intent 协议与 GitHub Release 发布规范

#### Intent 契约（`EasyRpgHostContract`，双端同步常量）
- `PACKAGE = "com.tyranor.next.easyrpg"`
- `VERSION_CODE = 1`（首版发布后仅在协议不兼容时递增）
- `ACTION_RUN = "com.tyranor.next.easyrpg.action.RUN"`
- Extras：
  - `com.tyranor.next.easyrpg.extra.GAME_DIR`（String，游戏绝对路径）
  - `com.tyranor.next.easyrpg.extra.TITLE`（String，游戏标题）
  - `com.tyranor.next.easyrpg.extra.SETTINGS`（String，扁平 JSON 设置载荷）

#### Release 规范（严格匹配 `ExternalEngineUpdateChecker.kt`）
`ExternalEngineUpdateChecker` 通过 `GET https://api.github.com/repos/Weiss-UltimateSavior/Tyranor-Next-EasyRPG/releases/latest` 校验更新，发布时必须满足：
1. 必须为正式 Release（`draft == false` 且 `prerelease == false`）。
2. `tag_name` 格式为 **`easyrpg-host-<versionName>`**（例如 `easyrpg-host-0.1.0`），去掉前缀 `easyrpg-host-` 后须满足正则 `[0-9]+(?:\.[0-9]+)*`，并与 APK 的 `android:versionName` 完全一致。
3. Release 资产中至少包含一个 `.apk` 文件，且文件名固定为 **`Tyranor-Next-EasyRPG.apk`**，使主 App 的 `installUrl`（`https://github.com/Weiss-UltimateSavior/Tyranor-Next-EasyRPG/releases/latest/download/Tyranor-Next-EasyRPG.apk`）永久有效。

---

## 4. 主 App 仓库接入设计（`TyranorNext` 侧改动清单）

### 4.1 核心注册与协议接入（与 MAGES. 完全同构）
1. **`EngineType.kt`**：
   - 新增枚举：`EASYRPG("EasyRPG", "ERP")`
2. **`app/src/main/AndroidManifest.xml`**：
   - 在 `<queries>` 中添加 `<package android:name="com.tyranor.next.easyrpg" />`（确保 Android 11+ 包可见性，满足 `ExternalEngineManifestQueriesTest` 类校验逻辑）。
3. **`core/engine/external/EasyRpgExternalEngineModule.kt` & `ExternalEngineModuleRegistry.kt`**：
   - 实现 `ExternalEngineModule`：
     - `id = "easyrpg"`，`engine = EngineType.EASYRPG`，`displayName = "EasyRPG"`
     - `packageName = EasyRpgHostContract.PACKAGE`，`action = EasyRpgHostContract.ACTION_RUN`
     - `updateSource = ExternalEngineReleaseSource("Weiss-UltimateSavior/Tyranor-Next-EasyRPG", "easyrpg-host-")`
     - `installUrl = "${updateSource.releasesUrl}/latest/download/Tyranor-Next-EasyRPG.apk"`
     - `minimumVersionCode = EasyRpgHostContract.VERSION_CODE.toLong()`
     - `requiresGameDirectoryPath = true`
     - `buildLaunchIntent(request)`：组装 `Intent(action).setPackage(packageName)`，注入 `EXTRA_GAME_DIR`、`EXTRA_TITLE`、`EXTRA_SETTINGS`。
   - 注册进 `ExternalEngineModuleRegistry.modules`。
4. **`core/game/launch/EngineLauncher.kt`**：
   - 将 `EngineType.EASYRPG` 加入 `supportedEngines`，由 `ExternalEngineLauncher` 自动完成安装检查、最低版本检查、`MANAGE_EXTERNAL_STORAGE` 授权引导及安全拉起。

### 4.2 扫描识别（`core/game/scan/EngineScanner.kt`）
- 新增 RPG Maker 2000/2003 / EasyRPG 目录识别（位于主机 ROM/SWF 之后、独立精确命中规则中）：
  - **标准条件**：目录直属文件同时存在 `RPG_RT.ldb` 和 `RPG_RT.lmt`（大小写不敏感），或同时存在 `EASY_RT.edb` 和 `EASY_RT.emt`（大小写不敏感）。
  - 与现有 `RPG_MV`（`js/rpg_core.js`）、`RPG_MZ`（`js/rmmz_core.js`）、`RPGMAKER`（`*.rgss*` / `Data/*.rvdata*` / `*.rxdata`）特征完全正交，无误判风险。

### 4.3 全局 + 单游戏设置（`core/settings/` & `ui/settings/`）
参照 `Impacto` 的三层合并架构（`EngineSettingsStore` + `PerGameSettingsStore` / `GameOverridePartitions` + `EffectiveEngineSettings` + `EngineSettingsResolver`）：
- 新增 `EngineSettingsStore.EasyRpg`（及对应的 `KEY_EASYRPG_*`、`F_EASYRPG_*` 持久化键与单游戏覆盖 UI）：

| 设置键 | 类型 | 默认值 | 说明 / 宿主侧映射的 EasyRPG CLI 参数 |
| --- | --- | --- | --- |
| `encoding` | `String` 枚举 | `"auto"` | `auto` / `932` / `936` / `950` / `949` / `1252` / `1251` / `65001` → 非 `auto` 时 `--encoding <val>` |
| `scaling` | `String` 枚举 | `"bilinear"` | `nearest` / `integer` / `bilinear` → `--scaling <val>` |
| `gameResolution` | `String` 枚举 | `"original"` | `original` / `widescreen` / `ultrawide` → `--game-resolution <val>` |
| `stretch` | `Boolean` | `false` | `--stretch` / `--no-stretch` |
| `vsync` | `Boolean` | `true` | `--vsync` / `--no-vsync` |
| `fpsLimit` | `Int` | `60` | `0..240`（`0` → `--no-fps-limit`；`>0` → `--fps-limit <N>`） |
| `showFps` | `Boolean` | `false` | `--show-fps` / `--no-show-fps` |
| `disableAudio` | `Boolean` | `false` | `true` → `--no-audio` |
| `musicVolume` | `Int` | `100` | `0..100` → `--music-volume <N>` |
| `soundVolume` | `Int` | `100` | `0..100` → `--sound-volume <N>` |
| `noRtp` | `Boolean` | `false` | `true` → `--no-rtp` |
| `patchMode` | `String` 枚举 | `"auto"` | `auto` / `none` / `easyrpg` / `maniac` / `dynrpg` → `--no-patch` / `--patch-*` |
| `testPlay` | `Boolean` | `false` | `true` → `--test-play` |

### 4.4 UI、存档管理与下载体验增强
1. **引擎页 & 游戏页**：
   - `EngineScreen.kt`：`engineTabOf` 将 `EngineType.EASYRPG` 归入 `EngineTab.RPGM`；使用 `/Volumes/HUAWEI-M2LITE/下载/engine_icons/6BBF841D72D5A0DE6BA2C1DA471DF90B.jpg`（1376×768 JPEG）按既有 `drawable-nodpi/ic_engine_card_*.png` 规格（如 `ic_engine_card_impacto.png` 为 `480×252` PNG）裁切/缩放并转存为 `app/src/main/res/drawable-nodpi/ic_engine_card_easyrpg.png`，映射到 `EngineType.EASYRPG -> R.drawable.ic_engine_card_easyrpg`；`EngineScreen` 既有的 `produceState` 遍历 `ExternalEngineModuleRegistry.modules` 会**自动**执行更新检查并弹窗显示版本状态与下载按钮。
   - `GameScreen.kt`：为 `EngineType.EASYRPG` 配置卡片主题色，并在游戏抽屉接入更新检测横幅（同 `IMPACTO`）。
2. **文件存档与 WebDAV 同步（推荐在主 App 补齐，比 MAGES. 更完整）**：
   - MAGES. 禁用存档管理（`GameSaveManager.kt`）是因为 Impacto 依赖的外部存储软链接被 Android FUSE 拒绝而被迫将存档留在宿主私有目录；**EasyRPG 无此限制**，通过 `--save-path <GAME_DIR>/saves` 直接落盘。
   - 可在 `GameSaveManager.kt` 中将 `EngineType.EASYRPG` 映射至 `File(root, "saves")`，从而直接享受 TyranorNext 的导入、导出、版本备份与 WebDAV 同步能力（`SaveSyncRuntime` / `SaveDirectoryIdentity`）。
3. **下载与更新行为完全沿用 MAGES. 机制**：
   - 使用 `ExternalEngineUpdateChecker` 检查 GitHub Releases 最新正式版，并在引擎页、游戏抽屉和报错弹窗提示安装/更新。
   - 点击下载或更新时统一调用 `ExternalEngineLauncher.openInstallPage(...)`（`ACTION_VIEW` 跳转系统浏览器打开 `installUrl` 或 `releasesUrl`），**不复用主 App 自更新的 `UpdateApkDownloader.kt` 实现 App 内下载直装**。

---

## 5. 分阶段实施计划

### Phase 1：宿主仓库初始化与上游工具链编译（`Tyranor-Next-EasyRPG`）
1. 初始化 `/Volumes/HUAWEI-M2LITE/app/tyn-easyrpg` Git 仓库并关联远端 `https://github.com/Weiss-UltimateSavior/Tyranor-Next-EasyRPG.git`，添加只读 Submodule：
   - `third_party/Player`（锁定稳定 release tag 或 commit）
   - `third_party/buildscripts`
2. 编写 `scripts/build_toolchain.sh`，基于本机 NDK `28.2.13676358` 编译 `arm64-v8a`（开发期）及 `armeabi-v7a` / `x86_64`（发布期）依赖工具链至 `.toolchains/`。
3. 编写 `scripts/verify_release_apk.sh`，检查 `third_party/*` Git 洁净度、`llvm-strip` 剥离状态及 16 KiB ELF 对齐。

### Phase 2：宿主 APK 构建与协议实现（`tyn-easyrpg`）
1. 搭建 Gradle 9.x + AGP 9.x 包装工程，`namespace = "org.easyrpg.player"`、`applicationId = "com.tyranor.next.easyrpg"`、`minSdk = 26`。
2. 对接 `third_party/Player/CMakeLists.txt`（仅目标 `easyrpg_android`）与 `SDL3`（`3.4.20`）AAR Prefab，打通基础 `assembleDebug`。
3. 实现 `EasyRpgHostContract.kt`、`StoragePermissionHelper.kt`、`EasyRpgSettingsMapper.kt`、`LaunchActivity.kt`、`TynEasyRpgPlayerActivity.kt` 及 `AndroidManifest.xml`。
4. 为 `EasyRpgSettingsMapper` 编写本地单测（验证全部 13 项配置边界、损坏 JSON 容错、默认值回退）。

### Phase 3：主 App 侧接入（`TyranorNext`）
1. 新增 `EngineType.EASYRPG`、`<queries>` 包声明、`EasyRpgExternalEngineModule` 与 `ExternalEngineModuleRegistry` 注册。
2. 在 `EngineScanner.kt` 接入 `RPG_RT.ldb` + `RPG_RT.lmt` / `EASY_RT.edb` + `EASY_RT.emt` 识别。
3. 在 `EngineSettingsStore`、`PerGameSettingsStore`、`GameOverridePartitions`、`EffectiveEngineSettings` 及设置 UI 接入全局与单游戏配置；将 `/Volumes/HUAWEI-M2LITE/下载/engine_icons/6BBF841D72D5A0DE6BA2C1DA471DF90B.jpg` 转换为 `app/src/main/res/drawable-nodpi/ic_engine_card_easyrpg.png`（480×252），并在 `EngineScreen` / `GameScreen` 接入卡片与更新提示（下载跳转沿用 `ExternalEngineLauncher.openInstallPage`）。
4. （按需）在 `GameSaveManager.kt` 接入 `<gameDir>/saves` 存档与 WebDAV。
5. 补齐对应单测（仿照 `ImpactoExternalEngineModuleTest`、`ExternalEngineManifestQueriesTest`、`EngineScannerImpactoTest`、`ImpactoSettingsKeysTest`）。

### Phase 4：CI 发布与真机全链路验收
1. 配置 `Tyranor-Next-EasyRPG` `.github/workflows/release.yml`：缓存多 ABI 工具链、校验上游洁净、打包签名、发布 `easyrpg-host-<version>` Tag + `Tyranor-Next-EasyRPG.apk` 固定资产到 `Weiss-UltimateSavior/Tyranor-Next-EasyRPG` Releases。
2. 真机联调验证清单：
   - **未装宿主时扫描**：放入标准 RM2k/2k3 游戏目录，TyranorNext 扫描入库为 `EasyRPG`；引擎页 `RPGM` 标签显示卡片“未安装”；点击启动游戏或卡片弹窗可直接触发下载。
   - **安装后启动**：授权 `MANAGE_EXTERNAL_STORAGE` 后顺利进入游戏画面、BGM/SE 音频与虚拟按键正常。
   - **版本检测与门控**：模拟旧 `versionCode` 宿主触发“版本过旧请更新”；发布更高版本 Tag 后在引擎页和游戏抽屉检测到 `ExternalEngineUpdate`。
   - **全局与单游戏设置生效**：修改编码（如 `936` GBK / `932` Shift-JIS）、缩放模式、显示 FPS、音量等，验证覆盖优先级（单游戏覆盖 > 全局默认）及游戏内实效。
   - **存档隔离与同步**：存档写入 `<GAME_DIR>/saves/Save01.lsd`（不污染游戏根）；若开启 `GameSaveManager` 接线，验证导出、恢复与 WebDAV 同步。
   - **恶意/异常请求防护**：验证空路径、非目录、缺失 `RPG_RT` 目录、损坏 `SETTINGS` JSON 时均安全降级或友好提示退出，不崩溃。
