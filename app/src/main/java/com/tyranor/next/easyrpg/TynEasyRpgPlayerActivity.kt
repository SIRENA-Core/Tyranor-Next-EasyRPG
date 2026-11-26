package com.tyranor.next.easyrpg

import android.os.Bundle
import android.os.Environment
import org.easyrpg.player.player.EasyRpgPlayerActivity
import java.io.File

/**
 * Host subclass of upstream [EasyRpgPlayerActivity] that provides direct filesystem RTP lookup
 * while preserving all static JNI methods on [EasyRpgPlayerActivity].
 */
class TynEasyRpgPlayerActivity : EasyRpgPlayerActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        intent.getStringExtra(EasyRpgHostContract.EXTRA_TITLE)
            ?.takeIf { it.isNotBlank() }
            ?.let { title = it }
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

    private fun isNoRtpEnabled(): Boolean {
        if (intent.getBooleanExtra(EXTRA_NO_RTP, false)) {
            return true
        }
        val args = intent.getStringArrayExtra(TAG_COMMAND_LINE) ?: return false
        return args.any { it == "--no-rtp" || it == "--disable-rtp" }
    }

    companion object {
        const val EXTRA_NO_RTP = "com.tyranor.next.easyrpg.internal.NO_RTP"
    }
}
