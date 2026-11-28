package com.tyranor.next.easyrpg

import android.content.Context
import org.json.JSONObject
import java.io.File

data class ActiveGameSession(
    val pid: Int,
    val gameDir: String?,
    val gameTitle: String?,
)

/**
 * Tracks the currently active `:game` process and game directory on disk so [LaunchActivity]
 * can prevent starting a second SDL3/EasyRPG instance while a game is already running.
 */
class ActiveGameSessionStore(private val sessionFile: File) {
    fun write(pid: Int, gameDir: String?, gameTitle: String?) {
        sessionFile.parentFile?.mkdirs()
        val payload = JSONObject().apply {
            put(KEY_PID, pid)
            if (!gameDir.isNullOrBlank()) {
                put(KEY_GAME_DIR, normalizePath(gameDir))
            }
            if (!gameTitle.isNullOrBlank()) {
                put(KEY_GAME_TITLE, gameTitle)
            }
        }
        val tmpFile = File(sessionFile.parentFile, "${sessionFile.name}.tmp")
        try {
            tmpFile.writeText(payload.toString(), Charsets.UTF_8)
            if (!tmpFile.renameTo(sessionFile)) {
                sessionFile.writeText(payload.toString(), Charsets.UTF_8)
                tmpFile.delete()
            }
        } catch (_: Throwable) {
            tmpFile.delete()
            runCatching { sessionFile.writeText(payload.toString(), Charsets.UTF_8) }
        }
    }

    fun read(): ActiveGameSession? {
        if (!sessionFile.isFile) {
            return null
        }
        val raw = runCatching { sessionFile.readText(Charsets.UTF_8) }.getOrNull() ?: return null
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        val pid = json.optInt(KEY_PID, -1)
        if (pid <= 0) {
            return null
        }
        val gameDir = json.optString(KEY_GAME_DIR, "").trim().ifEmpty { null }
        val gameTitle = json.optString(KEY_GAME_TITLE, "").trim().ifEmpty { null }
        return ActiveGameSession(pid = pid, gameDir = gameDir, gameTitle = gameTitle)
    }

    fun clearIfOwned(pid: Int) {
        val current = read()
        if (current == null || current.pid == pid) {
            clear()
        }
    }

    fun clear() {
        runCatching {
            if (sessionFile.exists()) {
                sessionFile.delete()
            }
        }
    }

    /**
     * Resolves the live session against the observed `:game` process PID.
     * Clears stale session metadata if the `:game` process is no longer running.
     */
    fun resolveActiveSession(gameProcessPid: Int?): ActiveGameSession? {
        if (gameProcessPid == null || gameProcessPid <= 0) {
            clear()
            return null
        }
        val recorded = read()
        return if (recorded != null && recorded.pid == gameProcessPid) {
            recorded
        } else {
            ActiveGameSession(pid = gameProcessPid, gameDir = null, gameTitle = null)
        }
    }

    companion object {
        private const val FILE_NAME = "active_game_session.json"
        private const val KEY_PID = "pid"
        private const val KEY_GAME_DIR = "gameDir"
        private const val KEY_GAME_TITLE = "gameTitle"

        fun forContext(context: Context): ActiveGameSessionStore =
            ActiveGameSessionStore(File(context.filesDir, FILE_NAME))

        fun normalizePath(path: String): String {
            return try {
                File(path).canonicalPath
            } catch (_: Throwable) {
                path.trim().trimEnd('/').ifEmpty { if (path.trim().startsWith("/")) "/" else "" }
            }
        }

        fun isSameGame(activeDir: String?, requestedDir: String): Boolean {
            if (activeDir.isNullOrBlank()) {
                return false
            }
            return normalizePath(activeDir) == normalizePath(requestedDir)
        }
    }
}
