package com.wireguard.android.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.content.PermissionChecker

object AppPermissionAccess {
    fun cameraAllowed(context: Context): Boolean =
        PermissionChecker.checkSelfPermission(context, Manifest.permission.CAMERA) == PermissionChecker.PERMISSION_GRANTED

    fun runtimePermissions(context: Context): List<String> = AppPermissionPolicy.runtimePermissions(
        Build.VERSION.SDK_INT, context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY))

    fun runtimeGranted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}
