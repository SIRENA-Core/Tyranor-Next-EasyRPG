package com.tyranor.next.easyrpg

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EasyRpgSettingsMapperTest {
    @Test
    fun `null blank and malformed json fall back to defaults`() {
        val expected = EasyRpgSettings()
        assertEquals(expected, EasyRpgSettingsMapper.parse(null))
        assertEquals(expected, EasyRpgSettingsMapper.parse(""))
        assertEquals(expected, EasyRpgSettingsMapper.parse("   "))
        assertEquals(expected, EasyRpgSettingsMapper.parse("{not-json"))
        assertEquals(expected, EasyRpgSettingsMapper.parse("[]"))
    }

    @Test
    fun `default settings produce expected cli arguments`() {
        val args = EasyRpgSettings().toCliArgs()
        assertEquals(
            listOf(
                "--scaling", "bilinear",
                "--game-resolution", "original",
                "--no-stretch",
                "--vsync",
                "--fps-limit", "60",
                "--no-show-fps",
                "--music-volume", "100",
                "--sound-volume", "100",
            ),
            args,
        )
    }

    @Test
    fun `all 13 settings keys map cleanly when valid`() {
        val json = """
            {
              "encoding": "936",
              "scaling": "nearest",
              "gameResolution": "widescreen",
              "stretch": true,
              "vsync": false,
              "fpsLimit": 120,
              "showFps": true,
              "disableAudio": true,
              "musicVolume": 45,
              "soundVolume": 80,
              "noRtp": true,
              "patchMode": "maniac",
              "testPlay": true,
              "unknownFutureKey": "ignored"
            }
        """.trimIndent()

        val parsed = EasyRpgSettingsMapper.parse(json)
        assertEquals("936", parsed.encoding)
        assertEquals("nearest", parsed.scaling)
        assertEquals("widescreen", parsed.gameResolution)
        assertTrue(parsed.stretch)
        assertFalse(parsed.vsync)
        assertEquals(120, parsed.fpsLimit)
        assertTrue(parsed.showFps)
        assertTrue(parsed.disableAudio)
        assertEquals(45, parsed.musicVolume)
        assertEquals(80, parsed.soundVolume)
        assertTrue(parsed.noRtp)
        assertEquals("maniac", parsed.patchMode)
        assertTrue(parsed.testPlay)

        assertEquals(
            listOf(
                "--encoding", "936",
                "--scaling", "nearest",
                "--game-resolution", "widescreen",
                "--stretch",
                "--no-vsync",
                "--fps-limit", "120",
                "--show-fps",
                "--no-audio",
                "--music-volume", "45",
                "--sound-volume", "80",
                "--no-rtp",
                "--patch-maniac",
                "--test-play",
            ),
            parsed.toCliArgs(),
        )
    }

    @Test
    fun `all enum values and numeric boundaries are accepted`() {
        for (enc in listOf("auto", "932", "936", "950", "949", "1252", "1251", "65001")) {
            val s = EasyRpgSettingsMapper.parse("""{"encoding":"$enc"}""")
            assertEquals(enc, s.encoding)
        }
        for (scaling in listOf("nearest", "integer", "bilinear")) {
            val s = EasyRpgSettingsMapper.parse("""{"scaling":"$scaling"}""")
            assertEquals(scaling, s.scaling)
        }
        for (res in listOf("original", "widescreen", "ultrawide")) {
            val s = EasyRpgSettingsMapper.parse("""{"gameResolution":"$res"}""")
            assertEquals(res, s.gameResolution)
        }

        val patchExpectations = mapOf(
            "auto" to null,
            "none" to "--no-patch",
            "easyrpg" to "--patch-easyrpg",
            "maniac" to "--patch-maniac",
            "dynrpg" to "--patch-dynrpg",
        )
        for ((mode, flag) in patchExpectations) {
            val cli = EasyRpgSettingsMapper.parse("""{"patchMode":"$mode"}""").toCliArgs()
            if (flag == null) {
                assertFalse(cli.any { it.contains("patch") })
            } else {
                assertTrue("Expected $flag for patchMode=$mode", flag in cli)
            }
        }

        // Boundaries: fpsLimit 0 -> --no-fps-limit, 240 -> --fps-limit 240
        val fpsZero = EasyRpgSettingsMapper.parse("""{"fpsLimit":0}""")
        assertEquals(0, fpsZero.fpsLimit)
        assertTrue("--no-fps-limit" in fpsZero.toCliArgs())
        assertFalse("--fps-limit" in fpsZero.toCliArgs())

        val fpsMax = EasyRpgSettingsMapper.parse("""{"fpsLimit":240}""")
        assertEquals(240, fpsMax.fpsLimit)
        assertTrue("--fps-limit" in fpsMax.toCliArgs())

        val volumeMin = EasyRpgSettingsMapper.parse("""{"musicVolume":0,"soundVolume":0}""")
        assertEquals(0, volumeMin.musicVolume)
        assertEquals(0, volumeMin.soundVolume)

        val volumeMax = EasyRpgSettingsMapper.parse("""{"musicVolume":100,"soundVolume":100}""")
        assertEquals(100, volumeMax.musicVolume)
        assertEquals(100, volumeMax.soundVolume)
    }

    @Test
    fun `out of range numbers and wrong json types fall back to defaults`() {
        val json = """
            {
              "encoding": "9999",
              "scaling": "cubic",
              "gameResolution": "4k",
              "stretch": "true",
              "vsync": 0,
              "fpsLimit": -1,
              "showFps": 1,
              "disableAudio": "yes",
              "musicVolume": 101,
              "soundVolume": -5,
              "noRtp": null,
              "patchMode": "custom",
              "testPlay": 123
            }
        """.trimIndent()

        assertEquals(EasyRpgSettings(), EasyRpgSettingsMapper.parse(json))

        val highFps = EasyRpgSettingsMapper.parse("""{"fpsLimit":241}""")
        assertEquals(EasyRpgSettings.DEFAULT_FPS_LIMIT, highFps.fpsLimit)

        val floatFps = EasyRpgSettingsMapper.parse("""{"fpsLimit":60.5}""")
        assertEquals(EasyRpgSettings.DEFAULT_FPS_LIMIT, floatFps.fpsLimit)
    }

    @Test
    fun `buildCommandLineArgs prefixes required paths and fullscreen flag`() {
        val settings = EasyRpgSettingsMapper.parse("""{"encoding":"932","noRtp":true}""")
        val cmd = EasyRpgSettingsMapper.buildCommandLineArgs(
            projectPath = "/sdcard/Games/TestGame",
            savePath = "/sdcard/Games/TestGame/saves",
            configPath = "/sdcard/Android/data/com.tyranor.next.easyrpg/files",
            logFilePath = "/sdcard/Android/data/com.tyranor.next.easyrpg/files/easyrpg-player.log",
            settings = settings,
        )

        assertArrayEquals(
            arrayOf(
                "--project-path", "/sdcard/Games/TestGame",
                "--save-path", "/sdcard/Games/TestGame/saves",
                "--config-path", "/sdcard/Android/data/com.tyranor.next.easyrpg/files",
                "--log-file", "/sdcard/Android/data/com.tyranor.next.easyrpg/files/easyrpg-player.log",
                "--fullscreen",
                "--encoding", "932",
                "--scaling", "bilinear",
                "--game-resolution", "original",
                "--no-stretch",
                "--vsync",
                "--fps-limit", "60",
                "--no-show-fps",
                "--music-volume", "100",
                "--sound-volume", "100",
                "--no-rtp",
            ),
            cmd,
        )
    }
}
