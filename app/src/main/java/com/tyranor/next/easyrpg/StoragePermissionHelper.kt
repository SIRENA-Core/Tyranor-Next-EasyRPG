package com.tyranor.next.easyrpg

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.easyrpg.player.R

/**
 * Checks and guides the user through granting external storage access needed for direct game paths.
 */
object StoragePermissionHelper {
    const val REQUEST_CODE_STORAGE_PERMISSIONS = 1001

    private val LEGACY_STORAGE_PERMISSIONS = arrayOf(
        Manifest.permission.READ_EXTERNAL_STORAGE,
        Manifest.permission.WRITE_EXTERNAL_STORAGE,
    )

    fun hasStoragePermission(activity: Activity): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            LEGACY_STORAGE_PERMISSIONS.all { permission ->
                ContextCompat.checkSelfPermission(activity, permission) == PackageManager.PERMISSION_GRANTED
            }
        }
    }

    fun showPermissionDialog(
        activity: Activity,
        onCancel: () -> Unit,
    ): AlertDialog {
        return AlertDialog.Builder(activity)
            .setTitle(R.string.tyn_permission_title)
            .setMessage(
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    R.string.tyn_permission_message_manage_all
                } else {
                    R.string.tyn_permission_message_legacy
                },
            )
            .setCancelable(false)
            .setPositiveButton(R.string.tyn_permission_grant) { _, _ ->
                requestStoragePermission(activity)
            }
            .setNegativeButton(R.string.cancel) { _, _ ->
                onCancel()
            }
            .show()
    }

    fun requestStoragePermission(activity: Activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val packageUri = Uri.parse("package:${activity.packageName}")
            val appSettingsIntent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, packageUri)
            try {
                activity.startActivity(appSettingsIntent)
            } catch (_: Throwable) {
                val fallbackIntent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                try {
                    activity.startActivity(fallbackIntent)
                } catch (_: Throwable) {
                    // Fallback to standard app details if OEM strips all-files settings action.
                    activity.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri))
                }
            }
        } else {
            ActivityCompat.requestPermissions(
                activity,
                LEGACY_STORAGE_PERMISSIONS,
                REQUEST_CODE_STORAGE_PERMISSIONS,
            )
        }
    }
}
