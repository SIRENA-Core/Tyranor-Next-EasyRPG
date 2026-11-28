import java.io.File
import java.net.URI
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.isFile) {
        file.inputStream().use { load(it) }
    }
}

fun configValue(vararg keys: String): String? {
    for (key in keys) {
        System.getenv(key)?.takeIf { it.isNotBlank() }?.let { return it }
        localProperties.getProperty(key)?.takeIf { it.isNotBlank() }?.let { return it }
        providers.gradleProperty(key).orNull?.takeIf { it.isNotBlank() }?.let { return it }
    }
    return null
}

/**
 * Host APK version name: the single version field to edit when releasing.
 * [appVersionCode] is derived automatically (`major*1_000_000 + minor*1_000 + patch`).
 */
val appVersionName = "0.1.2"

fun versionCodeOf(name: String): Int {
    val parts = Regex("\\d+").findAll(name).map { it.value.toInt() }.toList()
    require(parts.size >= 2) { "versionName must be major.minor[.patch]: $name" }
    val major = parts[0]
    val minor = parts.getOrElse(1) { 0 }
    val patch = parts.getOrElse(2) { 0 }
    require(minor in 0..999 && patch in 0..999) { "versionName minor/patch must be < 1000: $name" }
    val code = major * 1_000_000 + minor * 1_000 + patch
    require(code in 1..2_100_000_000) { "versionCode out of Android range: $code" }
    return code
}

val appVersionCode = versionCodeOf(appVersionName)

abstract class PrintAppVersionTask : DefaultTask() {
    @get:Input
    abstract val versionName: Property<String>

    @get:Input
    abstract val versionCode: Property<Int>

    @TaskAction
    fun print() {
        println("${versionName.get()} ${versionCode.get()}")
    }
}

tasks.register<PrintAppVersionTask>("printAppVersion") {
    group = "help"
    description = "Prints appVersionName and the derived appVersionCode."
    versionName.set(appVersionName)
    versionCode.set(appVersionCode)
}

val sdlVersion: String = providers.gradleProperty("sdlVersion").orElse("3.4.20").get()
val abiFiltersDebug: List<String> = providers.gradleProperty("ABI_FILTERS_DEBUG")
    .orElse("arm64-v8a")
    .get()
    .split(',')
    .map { it.trim() }
    .filter { it.isNotEmpty() }
val abiFiltersRelease: List<String> = providers.gradleProperty("ABI_FILTERS_RELEASE")
    .orElse("armeabi-v7a,arm64-v8a,x86_64")
    .get()
    .split(',')
    .map { it.trim() }
    .filter { it.isNotEmpty() }

val resolvedToolchainPath: String = configValue(
    "toolchainDirs",
    "EASYRPG_TOOLCHAIN_DIR",
) ?: rootProject.layout.projectDirectory.dir(".toolchains").asFile.absolutePath

val releaseStoreFile = configValue("ANDROID_KEYSTORE_FILE", "RELEASE_STORE_FILE")
val releaseStorePassword = configValue("ANDROID_KEYSTORE_PASSWORD", "RELEASE_STORE_PASSWORD")
val releaseKeyAlias = configValue("ANDROID_KEY_ALIAS", "RELEASE_KEY_ALIAS")
val releaseKeyPassword = configValue("ANDROID_KEY_PASSWORD", "RELEASE_KEY_PASSWORD")
val hasReleaseSigning = listOf(
    releaseStoreFile,
    releaseStorePassword,
    releaseKeyAlias,
    releaseKeyPassword,
).all { !it.isNullOrBlank() }

fun fetchSdlAar(): File {
    val sdlAarFile = layout.projectDirectory.file("SDL3-$sdlVersion.aar").asFile
    if (sdlAarFile.isFile) {
        return sdlAarFile
    }

    val sdlZipFile = layout.buildDirectory.file("SDL3-devel-$sdlVersion-android.zip").get().asFile
    sdlZipFile.parentFile.mkdirs()
    if (!sdlZipFile.isFile) {
        val url = "https://github.com/libsdl-org/SDL/releases/download/release-$sdlVersion/SDL3-devel-$sdlVersion-android.zip"
        logger.lifecycle("Downloading SDL3 $sdlVersion AAR from $url...")
        URI(url).toURL().openStream().use { input ->
            sdlZipFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }
    }

    copy {
        from(zipTree(sdlZipFile))
        into(sdlAarFile.parentFile)
        include("**/*.aar")
        eachFile {
            relativePath = RelativePath(true, "SDL3-$sdlVersion.aar")
        }
        includeEmptyDirs = false
    }
    check(sdlAarFile.isFile) { "Failed to extract SDL3-$sdlVersion.aar from $sdlZipFile" }
    return sdlAarFile
}

val preferredNdkVersion = configValue("ANDROID_NDK_VERSION") ?: "28.2.13676358"
val resolvedNdkVersion: String = run {
    val sdkDir = configValue("sdk.dir", "ANDROID_HOME", "ANDROID_SDK_ROOT")
        ?: listOf(
            "${System.getProperty("user.home")}/Library/Android/sdk",
            "${System.getProperty("user.home")}/Android/Sdk",
        ).firstOrNull { File(it).isDirectory }
    if (sdkDir != null) {
        val preferredDir = File(sdkDir, "ndk/$preferredNdkVersion/toolchains/llvm/prebuilt")
        if (preferredDir.isDirectory) {
            preferredNdkVersion
        } else {
            val fallback = File(sdkDir, "ndk").listFiles()
                ?.filter { it.isDirectory && it.name.startsWith("28.") && File(it, "toolchains/llvm/prebuilt").isDirectory }
                ?.maxByOrNull { it.name }
            fallback?.name ?: preferredNdkVersion
        }
    } else {
        preferredNdkVersion
    }
}

android {
    namespace = "org.easyrpg.player"
    compileSdk = 37
    ndkVersion = resolvedNdkVersion

    buildFeatures {
        prefab = true
        buildConfig = false
    }

    defaultConfig {
        applicationId = "com.tyranor.next.easyrpg"
        minSdk = 26
        targetSdk = 37
        versionCode = appVersionCode
        versionName = appVersionName

        externalNativeBuild {
            cmake {
                arguments(
                    "-DPLAYER_GRADLE_BUILD=ON",
                    "-DBUILD_SHARED_LIBS=ON",
                    "-DPLAYER_ENABLE_TESTS=OFF",
                    "-DPLAYER_ANDROID_TOOLCHAIN_PATH=$resolvedToolchainPath",
                )
                targets("easyrpg_android", "gamebrowser")

                configValue("cmakeOptions")?.let { extra ->
                    arguments.addAll(extra.split(' ').filter { it.isNotBlank() })
                }
            }
        }
    }

    signingConfigs {
        create("release") {
            if (hasReleaseSigning) {
                storeFile = file(releaseStoreFile!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        debug {
            ndk {
                abiFilters += abiFiltersDebug
            }
        }
        release {
            ndk {
                abiFilters += abiFiltersRelease
            }
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName(
                if (hasReleaseSigning) "release" else "debug",
            )
        }
    }

    externalNativeBuild {
        cmake {
            path = file("../third_party/Player/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        abortOnError = false
        disable += "MissingTranslation"
    }

    sourceSets {
        getByName("main") {
            manifest.srcFile("src/main/AndroidManifest.xml")
            java.directories.add("../third_party/Player/builds/android/app/src/main/java")
            res.directories.add("../third_party/Player/builds/android/app/src/main/res")
        }
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(files(fetchSdlAar()))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity)
    implementation(libs.material)
    implementation(libs.androidx.cardview)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.documentfile)
    implementation(libs.ini4j)

    testImplementation(libs.junit)
    testImplementation(libs.json)
}
