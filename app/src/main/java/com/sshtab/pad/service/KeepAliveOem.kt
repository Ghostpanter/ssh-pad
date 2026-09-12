package com.sshtab.pad.service

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.sshtab.pad.log.SessionLog

object KeepAliveOem {
    fun brand(): String = Build.MANUFACTURER.orEmpty().lowercase()

    fun isChineseRom(): Boolean {
        val b = brand()
        return listOf(
            "xiaomi", "redmi", "poco", "blackshark",
            "huawei", "honor",
            "oppo", "realme", "oneplus",
            "vivo", "iqoo",
            "samsung", "meizu", "lenovo", "zte", "nubia",
        ).any { it in b }
    }

    fun requestOverlay(context: Context) {
        if (Build.VERSION.SDK_INT < 23) return
        if (Settings.canDrawOverlays(context)) return
        try {
            context.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${context.packageName}"),
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (t: Throwable) {
            SessionLog.event("overlay settings: $t")
        }
    }

    fun requestBattery(context: Context) {
        if (Build.VERSION.SDK_INT < 23) return
        try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            if (pm.isIgnoringBatteryOptimizations(context.packageName)) return
            context.startActivity(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:${context.packageName}"),
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (t: Throwable) {
            SessionLog.event("battery settings: $t")
            try {
                context.startActivity(
                    Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (_: Throwable) {
            }
        }
    }

    fun overlayGranted(context: Context): Boolean =
        Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(context)

    fun batteryGranted(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 23) return true
        return try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            pm.isIgnoringBatteryOptimizations(context.packageName)
        } catch (_: Throwable) { false }
    }

    /** 打开该品牌的自启动 / 后台运行页，点不到就回落到应用详情。 */
    fun openVendorKeepAlive(context: Context) {
        val pkg = context.packageName
        val b = brand()
        val candidates = mutableListOf<Intent>()
        when {
            "xiaomi" in b || "redmi" in b || "poco" in b || "blackshark" in b -> {
                candidates += Intent("miui.intent.action.OP_AUTO_START").addCategory(Intent.CATEGORY_DEFAULT)
                candidates += Intent("miui.intent.action.POWER_HIDE_MODE_APP_LIST")
                candidates += Intent("miui.intent.action.APP_PERM_EDITOR")
                    .putExtra("extra_pkgname", pkg)
                candidates += component("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")
                candidates += component("com.miui.powerkeeper", "com.miui.powerkeeper.ui.HiddenAppsConfigActivity")
            }
            "huawei" in b || "honor" in b -> {
                candidates += component("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity")
                candidates += component("com.huawei.systemmanager", "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity")
                candidates += component("com.hihonor.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity")
            }
            "oppo" in b || "realme" in b || "oneplus" in b -> {
                candidates += component("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity")
                candidates += component("com.oplus.safecenter", "com.oplus.safecenter.startupapp.StartupAppListActivity")
                candidates += component("com.coloros.safecenter", "com.coloros.privacypermissionsentry.PermissionTopActivity")
            }
            "vivo" in b || "iqoo" in b -> {
                candidates += component("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity")
                candidates += component("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity")
                candidates += component("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager")
            }
            "samsung" in b -> {
                candidates += component("com.samsung.android.lool", "com.samsung.android.sm.battery.ui.BatteryActivity")
                candidates += component("com.samsung.android.sm", "com.samsung.android.sm.ui.battery.BatteryActivity")
            }
        }
        candidates += Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg"))
        for (intent in candidates) {
            try {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (intent.resolveActivity(context.packageManager) != null) {
                    context.startActivity(intent)
                    SessionLog.event("opened vendor keepalive ${intent.component ?: intent.action}")
                    return
                }
            } catch (_: Throwable) {
            }
        }
    }

    private fun component(pkg: String, cls: String): Intent =
        Intent().setComponent(ComponentName(pkg, cls))
}
