package com.tyranor.next.easyrpg

import org.json.JSONObject

/**
 * Validated EasyRPG Player launch settings parsed from [EasyRpgHostContract.EXTRA_SETTINGS].
 */
data class EasyRpgSettings(
    val encoding: String = DEFAULT_ENCODING,
    val scaling: String = DEFAULT_SCALING,
    val gameResolution: String = DEFAULT_GAME_RESOLUTION,
    val stretch: Boolean = DEFAULT_STRETCH,
    val vsync: Boolean = DEFAULT_VSYNC,
    val fpsLimit: Int = DEFAULT_FPS_LIMIT,
    val showFps: Boolean = DEFAULT_SHOW_FPS,
    val disableAudio: Boolean = DEFAULT_DISABLE_AUDIO,
    val musicVolume: Int = DEFAULT_MUSIC_VOLUME,
    val soundVolume: Int = DEFAULT_SOUND_VOLUME,
    val noRtp: Boolean = DEFAULT_NO_RTP,
    val patchMode: String = DEFAULT_PATCH_MODE,
    val testPlay: Boolean = DEFAULT_TEST_PLAY,
) {
    /**
     * Converts settings into EasyRPG Player CLI flags.
     */
    fun toCliArgs(): List<String> = buildList {
        if (encoding != DEFAULT_ENCODING) {
            add("--encoding")
            add(encoding)
        }
        add("--scaling")
        add(scaling)
        add("--game-resolution")
        add(gameResolution)
        add(if (stretch) "--stretch" else "--no-stretch")
        add(if (vsync) "--vsync" else "--no-vsync")
        if (fpsLimit == 0) {
            add("--no-fps-limit")
        } else {
            add("--fps-limit")
            add(fpsLimit.toString())
        }
        add(if (showFps) "--show-fps" else "--no-show-fps")
        if (disableAudio) {
            add("--no-audio")
        }
        add("--music-volume")
        add(musicVolume.toString())
        add("--sound-volume")
        add(soundVolume.toString())
        if (noRtp) {
            add("--no-rtp")
        }
        when (patchMode) {
            "none" -> add("--no-patch")
            "easyrpg" -> add("--patch-easyrpg")
            "maniac" -> add("--patch-maniac")
            "dynrpg" -> add("--patch-dynrpg")
        }
        if (testPlay) {
            add("--test-play")
        }
    }

    companion object {
        const val DEFAULT_ENCODING = "auto"
        const val DEFAULT_SCALING = "bilinear"
        const val DEFAULT_GAME_RESOLUTION = "original"
        const val DEFAULT_STRETCH = false
        const val DEFAULT_VSYNC = true
        const val DEFAULT_FPS_LIMIT = 60
        const val DEFAULT_SHOW_FPS = false
        const val DEFAULT_DISABLE_AUDIO = false
        const val DEFAULT_MUSIC_VOLUME = 100
        const val DEFAULT_SOUND_VOLUME = 100
        const val DEFAULT_NO_RTP = false
        const val DEFAULT_PATCH_MODE = "auto"
        const val DEFAULT_TEST_PLAY = false

        val VALID_ENCODINGS = setOf("auto", "932", "936", "950", "949", "1252", "1251", "65001")
        val VALID_SCALINGS = setOf("nearest", "integer", "bilinear")
        val VALID_GAME_RESOLUTIONS = setOf("original", "widescreen", "ultrawide")
        val VALID_PATCH_MODES = setOf("auto", "none", "easyrpg", "maniac", "dynrpg")
    }
}

/**
 * Fault-tolerant parser and CLI argument builder for EasyRPG launch settings JSON.
 */
object EasyRpgSettingsMapper {
    fun parse(rawJson: String?): EasyRpgSettings {
        if (rawJson.isNullOrBlank()) {
            return EasyRpgSettings()
        }
        val json = try {
            JSONObject(rawJson)
        } catch (_: Throwable) {
            return EasyRpgSettings()
        }

        return EasyRpgSettings(
            encoding = json.optAllowedString(
                key = "encoding",
                allowed = EasyRpgSettings.VALID_ENCODINGS,
                default = EasyRpgSettings.DEFAULT_ENCODING,
            ),
            scaling = json.optAllowedString(
                key = "scaling",
                allowed = EasyRpgSettings.VALID_SCALINGS,
                default = EasyRpgSettings.DEFAULT_SCALING,
            ),
            gameResolution = json.optAllowedString(
                key = "gameResolution",
                allowed = EasyRpgSettings.VALID_GAME_RESOLUTIONS,
                default = EasyRpgSettings.DEFAULT_GAME_RESOLUTION,
            ),
            stretch = json.optStrictBoolean("stretch", EasyRpgSettings.DEFAULT_STRETCH),
            vsync = json.optStrictBoolean("vsync", EasyRpgSettings.DEFAULT_VSYNC),
            fpsLimit = json.optBoundedInt(
                key = "fpsLimit",
                range = 0..240,
                default = EasyRpgSettings.DEFAULT_FPS_LIMIT,
            ),
            showFps = json.optStrictBoolean("showFps", EasyRpgSettings.DEFAULT_SHOW_FPS),
            disableAudio = json.optStrictBoolean("disableAudio", EasyRpgSettings.DEFAULT_DISABLE_AUDIO),
            musicVolume = json.optBoundedInt(
                key = "musicVolume",
                range = 0..100,
                default = EasyRpgSettings.DEFAULT_MUSIC_VOLUME,
            ),
            soundVolume = json.optBoundedInt(
                key = "soundVolume",
                range = 0..100,
                default = EasyRpgSettings.DEFAULT_SOUND_VOLUME,
            ),
            noRtp = json.optStrictBoolean("noRtp", EasyRpgSettings.DEFAULT_NO_RTP),
            patchMode = json.optAllowedString(
                key = "patchMode",
                allowed = EasyRpgSettings.VALID_PATCH_MODES,
                default = EasyRpgSettings.DEFAULT_PATCH_MODE,
            ),
            testPlay = json.optStrictBoolean("testPlay", EasyRpgSettings.DEFAULT_TEST_PLAY),
        )
    }

    fun buildCommandLineArgs(
        projectPath: String,
        savePath: String,
        configPath: String,
        logFilePath: String,
        settings: EasyRpgSettings,
    ): Array<String> = buildList {
        add("--project-path")
        add(projectPath)
        add("--save-path")
        add(savePath)
        add("--config-path")
        add(configPath)
        add("--log-file")
        add(logFilePath)
        add("--fullscreen")
        addAll(settings.toCliArgs())
    }.toTypedArray()

    private fun JSONObject.optAllowedString(
        key: String,
        allowed: Set<String>,
        default: String,
    ): String {
        val raw = opt(key) as? String ?: return default
        return if (raw in allowed) raw else default
    }

    private fun JSONObject.optStrictBoolean(key: String, default: Boolean): Boolean {
        val raw = opt(key)
        return raw as? Boolean ?: default
    }

    private fun JSONObject.optBoundedInt(
        key: String,
        range: IntRange,
        default: Int,
    ): Int {
        val raw = opt(key)
        val value = when (raw) {
            is Int -> raw
            is Long -> if (raw in Int.MIN_VALUE..Int.MAX_VALUE) raw.toInt() else return default
            else -> return default
        }
        return if (value in range) value else default
    }
}
