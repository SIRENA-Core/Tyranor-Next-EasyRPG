package com.tyranor.next.easyrpg

import android.content.Intent
import android.os.Bundle
import android.os.Environment
import android.os.Process
import android.widget.Toast
import org.easyrpg.player.R
import org.easyrpg.player.player.EasyRpgPlayerActivity
import java.io.File

/**
 * Host subclass of upstream [EasyRpgPlayerActivity] running in `:game`.
 * Provides direct filesystem RTP lookup, tracks the active game lock, and reclaims
 * SDL3 / EasyRPG C++ process-wide state when the game exits.
 */
class TynEasyRpgPlayerActivity : EasyRpgPlayerActivity() {
    private var activeGameDir: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        val gameDir = extractGameDir(intent)
        val gameTitle = intent.getStringExtra(EasyRpgHostContract.EXTRA_TITLE)?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: gameDir?.let { File(it).name.ifBlank { null } }
        activeGameDir = gameDir?.let(ActiveGameSessionStore::normalizePath)
        ActiveGameSessionStore.forContext(this).write(
            pid = Process.myPid(),
            gameDir = activeGameDir,
            gameTitle = gameTitle,
        )

        super.onCreate(savedInstanceState)
        if (!gameTitle.isNullOrBlank()) {
            title = gameTitle
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val nextGameDir = extractGameDir(intent)
        if (!nextGameDir.isNullOrBlank() && !ActiveGameSessionStore.isSameGame(activeGameDir, nextGameDir)) {
            // Never swap games inside a live SDL3/EasyRPG native process.
            Toast.makeText(this, R.string.tyn_running_conflict_toast, Toast.LENGTH_LONG).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) {
            ActiveGameSessionStore.forContext(this).clearIfOwned(Process.myPid())
            // EasyRPG and SDL3 hold non-reentrant process-wide C++ globals; kill `:game`
            // on exit so the next game starts cleanly without affecting LaunchActivity.
            Process.killProcess(Process.myPid())
        }
    }

    /**
     * Invoked via JNI by upstream `filefinder_rtp.cpp`.
     */
    override fun getRtpPath(): String {
        if (isNoRtpEnabled()) {
            return ""
        }

        val candidates = buildList {
            getExternalFilesDir(null)?.let { add(File(it, "rtp")) }
            @Suppress("DEPRECATION")
            add(File(Environment.getExternalStorageDirectory(), "EasyRPG/rtp"))
        }

        for (candidate in candidates) {
            if (candidate.exists() && candidate.isDirectory && candidate.canRead()) {
                return candidate.absolutePath
            }
        }
        return ""
    }

    private fun extractGameDir(source: Intent?): String? {
        if (source == null) {
            return null
        }
        source.getStringExtra(EXTRA_ACTIVE_GAME_DIR)?.trim()?.takeIf { it.isNotEmpty() }?.let {
            return it
        }
        val args = source.getStringArrayExtra(TAG_COMMAND_LINE) ?: return null
        for (i in 0 until args.size - 1) {
            if (args[i] == "--project-path" && args[i + 1].isNotBlank()) {
                return args[i + 1]
            }
        }
        return null
    }

    private fun isNoRtpEnabled(): Boolean {
        if (intent.getBooleanExtra(EXTRA_NO_RTP, false)) {
            return true
        }
        val args = intent.getStringArrayExtra(TAG_COMMAND_LINE) ?: return false
        return args.any { it == "--no-rtp" || it == "--disable-rtp" }
    }

    companion object {
        const val EXTRA_NO_RTP = "com.tyranor.next.easyrpg.internal.NO_RTP"
        const val EXTRA_ACTIVE_GAME_DIR = "com.tyranor.next.easyrpg.internal.ACTIVE_GAME_DIR"
    }
}
