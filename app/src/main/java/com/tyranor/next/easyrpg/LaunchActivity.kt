package com.tyranor.next.easyrpg

import android.app.ActivityManager
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import org.easyrpg.player.R
import org.easyrpg.player.player.EasyRpgPlayerActivity
import java.io.File
import java.nio.file.Files

/**
 * External entry Activity invoked by TyranorNext via [EasyRpgHostContract.ACTION_RUN].
 */
class LaunchActivity : AppCompatActivity() {
    private var activeDialog: AlertDialog? = null
    private var launched = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) {
            launched = savedInstanceState.getBoolean(STATE_LAUNCHED, false)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        launched = false
        dismissActiveDialog()
    }

    override fun onResume() {
        super.onResume()
        if (launched || isFinishing) {
            return
        }
        attemptLaunch()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_LAUNCHED, launched)
    }

    override fun onDestroy() {
        dismissActiveDialog()
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == StoragePermissionHelper.REQUEST_CODE_STORAGE_PERMISSIONS) {
            if (StoragePermissionHelper.hasStoragePermission(this)) {
                attemptLaunch()
            } else {
                showPermissionPrompt()
            }
        }
    }

    private fun attemptLaunch() {
        val rawGameDir = intent.getStringExtra(EasyRpgHostContract.EXTRA_GAME_DIR)?.trim().orEmpty()
        if (rawGameDir.isEmpty()) {
            showErrorAndFinish(getString(R.string.tyn_error_missing_game_dir))
            return
        }

        val rawFile = File(rawGameDir)
        if (!rawFile.isAbsolute) {
            showErrorAndFinish(getString(R.string.tyn_error_invalid_game_dir, rawGameDir))
            return
        }

        val runningSession = ActiveGameSessionStore.forContext(this)
            .resolveActiveSession(findRunningGameProcessPid())
        if (runningSession != null) {
            if (ActiveGameSessionStore.isSameGame(runningSession.gameDir, rawGameDir)) {
                bringRunningGameToFront()
                return
            }
            showRunningConflictDialog(runningSession)
            return
        }

        if (!StoragePermissionHelper.hasStoragePermission(this)) {
            showPermissionPrompt()
            return
        }

        val gameDir = validateReadableDirectory(rawFile)
        if (gameDir == null) {
            showErrorAndFinish(getString(R.string.tyn_error_invalid_game_dir, rawGameDir))
            return
        }

        val savesDir = File(gameDir, "saves")
        if (!ensureDirectoryExists(savesDir)) {
            showErrorAndFinish(getString(R.string.tyn_error_create_saves_failed, savesDir.absolutePath))
            return
        }

        val configDir = getExternalFilesDir(null) ?: filesDir
        if (!ensureDirectoryExists(configDir)) {
            showErrorAndFinish(getString(R.string.tyn_error_create_config_failed, configDir.absolutePath))
            return
        }
        val logFile = File(configDir, "easyrpg-player.log")

        val settings = EasyRpgSettingsMapper.parse(
            intent.getStringExtra(EasyRpgHostContract.EXTRA_SETTINGS),
        )
        val args = EasyRpgSettingsMapper.buildCommandLineArgs(
            projectPath = gameDir.absolutePath,
            savePath = savesDir.absolutePath,
            configPath = configDir.absolutePath,
            logFilePath = logFile.absolutePath,
            settings = settings,
        )

        val playerIntent = Intent(this, TynEasyRpgPlayerActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(EasyRpgPlayerActivity.TAG_COMMAND_LINE, args)
            putExtra(EasyRpgPlayerActivity.TAG_SAVE_PATH, savesDir.absolutePath)
            putExtra(EasyRpgPlayerActivity.TAG_LOG_FILE, logFile.absolutePath)
            putExtra(EasyRpgPlayerActivity.TAG_STANDALONE, true)
            putExtra(TynEasyRpgPlayerActivity.EXTRA_NO_RTP, settings.noRtp)
            putExtra(TynEasyRpgPlayerActivity.EXTRA_ACTIVE_GAME_DIR, gameDir.absolutePath)
            intent.getStringExtra(EasyRpgHostContract.EXTRA_TITLE)
                ?.takeIf { it.isNotBlank() }
                ?.let { putExtra(EasyRpgHostContract.EXTRA_TITLE, it) }
        }

        launched = true
        dismissActiveDialog()
        startActivity(playerIntent)
        finish()
    }

    private fun findRunningGameProcessPid(): Int? {
        val manager = getSystemService(ACTIVITY_SERVICE) as? ActivityManager ?: return null
        val processes = try {
            manager.runningAppProcesses
        } catch (_: Throwable) {
            null
        } ?: return null
        val gameProcessName = "$packageName:game"
        return processes.firstOrNull { it.processName == gameProcessName }?.pid
    }

    private fun bringRunningGameToFront() {
        val resumeIntent = Intent(this, TynEasyRpgPlayerActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        }
        launched = true
        dismissActiveDialog()
        startActivity(resumeIntent)
        finish()
    }

    private fun showRunningConflictDialog(session: ActiveGameSession) {
        if (activeDialog?.isShowing == true) {
            return
        }
        val runningLabel = session.gameTitle?.takeIf { it.isNotBlank() }
            ?: session.gameDir?.let { File(it).name.ifBlank { null } }
        val message = if (runningLabel != null) {
            getString(R.string.tyn_running_conflict_message, runningLabel)
        } else {
            getString(R.string.tyn_running_conflict_message_unknown)
        }

        activeDialog = AlertDialog.Builder(this)
            .setTitle(R.string.tyn_running_conflict_title)
            .setMessage(message)
            .setCancelable(true)
            .setPositiveButton(R.string.tyn_running_return_current) { _, _ ->
                bringRunningGameToFront()
            }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                finish()
            }
            .setOnCancelListener {
                finish()
            }
            .show()
    }

    private fun validateReadableDirectory(file: File): File? {
        val path = try {
            file.toPath()
        } catch (_: Throwable) {
            return null
        }

        // Reject broken symlinks explicitly before falling back to File checks.
        if (Files.isSymbolicLink(path) && !Files.exists(path)) {
            return null
        }
        if (!Files.isDirectory(path)) {
            return null
        }

        val resolved = try {
            file.canonicalFile
        } catch (_: Throwable) {
            return null
        }
        if (!resolved.exists() || !resolved.isDirectory || !resolved.canRead()) {
            return null
        }
        return resolved
    }

    private fun ensureDirectoryExists(dir: File): Boolean {
        if (dir.exists()) {
            return dir.isDirectory
        }
        return dir.mkdirs() || dir.isDirectory
    }

    private fun showPermissionPrompt() {
        if (activeDialog?.isShowing == true) {
            return
        }
        activeDialog = StoragePermissionHelper.showPermissionDialog(this) {
            finish()
        }
    }

    private fun showErrorAndFinish(message: String) {
        if (activeDialog?.isShowing == true) {
            return
        }
        activeDialog = AlertDialog.Builder(this)
            .setTitle(R.string.tyn_error_title)
            .setMessage(message)
            .setCancelable(false)
            .setPositiveButton(R.string.ok) { _, _ ->
                finish()
            }
            .show()
    }

    private fun dismissActiveDialog() {
        activeDialog?.dismiss()
        activeDialog = null
    }

    private companion object {
        private const val STATE_LAUNCHED = "tyn_launched"
    }
}
