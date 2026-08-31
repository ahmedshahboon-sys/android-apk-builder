package com.shahboun.multi

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

object RuntimePermissionBroker {
    private val supported = buildSet {
        add(Manifest.permission.CAMERA)
        add(Manifest.permission.RECORD_AUDIO)
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= 29) {
            add(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            add(Manifest.permission.ACTIVITY_RECOGNITION)
        }
        add(Manifest.permission.READ_CONTACTS)
        add(Manifest.permission.WRITE_CONTACTS)
        add(Manifest.permission.READ_CALENDAR)
        add(Manifest.permission.WRITE_CALENDAR)
        add(Manifest.permission.READ_PHONE_STATE)
        add(Manifest.permission.READ_PHONE_NUMBERS)
        add(Manifest.permission.CALL_PHONE)
        add(Manifest.permission.BODY_SENSORS)
        if (Build.VERSION.SDK_INT >= 31) {
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.BLUETOOTH_CONNECT)
            add(Manifest.permission.BLUETOOTH_ADVERTISE)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            add(Manifest.permission.POST_NOTIFICATIONS)
            add(Manifest.permission.READ_MEDIA_IMAGES)
            add(Manifest.permission.READ_MEDIA_VIDEO)
            add(Manifest.permission.READ_MEDIA_AUDIO)
            add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }
    }

    fun requestedByGuest(context: Context, packageName: String): List<String> {
        val pm = context.packageManager
        val info = if (Build.VERSION.SDK_INT >= 33) {
            pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong()))
        } else {
            @Suppress("DEPRECATION") pm.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS)
        }
        return info.requestedPermissions.orEmpty().filter { it in supported }.distinct()
    }

    fun missingForGuest(context: Context, packageName: String): List<String> =
        requestedByGuest(context, packageName).filter { context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
}
