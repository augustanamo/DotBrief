package com.briefwidget.ui.settings

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/** 是否已获得日历读取权限（运行时权限，可直接弹窗申请）。 */
fun Context.hasCalendarPermission(): Boolean =
    ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CALENDAR) ==
        PackageManager.PERMISSION_GRANTED

/**
 * 是否已获得位置权限（天气用）。
 *
 * 粗定位就够：天气只要"你在哪个城市"。系统在 Android 12 之后允许用户只给"大致位置"，
 * 那种情况下 ACCESS_FINE_LOCATION 是拒的而 ACCESS_COARSE_LOCATION 是给的，
 * 所以两个都要查、有一个就算有。
 */
fun Context.hasLocationPermission(): Boolean {
    val coarse = ContextCompat.checkSelfPermission(
        this,
        Manifest.permission.ACCESS_COARSE_LOCATION,
    ) == PackageManager.PERMISSION_GRANTED
    val fine = ContextCompat.checkSelfPermission(
        this,
        Manifest.permission.ACCESS_FINE_LOCATION,
    ) == PackageManager.PERMISSION_GRANTED
    return coarse || fine
}

/**
 * 是否已获得通知使用权。
 *
 * 注意：这个权限**不能**用运行时弹窗申请，只能引导用户到系统设置里手动勾选，
 * 判断方式是检查自己的包名是否在系统的"已启用监听器"名单里。
 */
fun Context.hasNotificationListenerAccess(): Boolean =
    NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)

/** 跳转系统「通知使用权」列表页。 */
fun Context.openNotificationListenerSettings() {
    startActivitySafely(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
}

/** 跳转本应用的系统详情页（权限被永久拒绝时的兜底出口）。 */
fun Context.openAppDetailsSettings() {
    startActivitySafely(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", packageName, null)
        },
    )
}

private fun Context.startActivitySafely(intent: Intent) {
    // 从 Activity 之外的上下文启动必须加 NEW_TASK；部分 ROM 可能没有该设置页，失败时静默
    runCatching { startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}
