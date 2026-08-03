package com.util.imebackfix

import android.app.Activity
import android.content.Context
import android.util.Log
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * The OEM launcher's pull-down-anywhere-on-workspace gesture (WorkspacePullDownDetectController)
 * always opens global search regardless of where on screen the pull started. This splits that
 * same gesture into thirds by the horizontal start position: left third expands notifications,
 * right third expands quick settings, middle third keeps the existing search behavior - so all
 * three panels are reachable from anywhere down the screen, not just the very top edge.
 */
object LauncherHook {

    private const val TAG = "BackFix"

    fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val controllerClass = XposedHelpers.findClass(
                "com.android.launcher.touch.WorkspacePullDownDetectController", lpparam.classLoader
            )
            XposedBridge.hookAllMethods(controllerClass, "startTargetApp", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    try {
                        val launcher = param.args.getOrNull(0) as? Activity ?: return
                        val touchDownX = XposedHelpers.getFloatField(param.thisObject, "mTouchDownX")
                        val width = launcher.windowManager.currentWindowMetrics.bounds.width()
                        XposedBridge.log("$TAG: startTargetApp touchDownX=$touchDownX width=$width")

                        val statusBarManager = launcher.getSystemService(Context.STATUS_BAR_SERVICE) ?: return
                        val panelMethod = when {
                            touchDownX < width / 3f -> "expandNotificationsPanel"
                            touchDownX > width * 2f / 3f -> "expandSettingsPanel"
                            else -> null
                        }
                        if (panelMethod != null) {
                            XposedHelpers.callMethod(statusBarManager, panelMethod)
                            XposedBridge.log("$TAG: called $panelMethod")
                            // We never actually started an app/search from this pull-down, so tell
                            // the launcher's own pull-down animator to reset (restore workspace
                            // icons/blur) instead of leaving it waiting for an activity transition
                            // that will never come.
                            try {
                                XposedHelpers.callMethod(param.thisObject, "pullCancel")
                            } catch (t: Throwable) {
                                XposedBridge.log("$TAG: pullCancel failed: ${Log.getStackTraceString(t)}")
                            }
                            param.result = false
                        }
                    } catch (t: Throwable) {
                        XposedBridge.log("$TAG: startTargetApp override failed: ${Log.getStackTraceString(t)}")
                    }
                }
            })
            XposedBridge.log("$TAG: hooked WorkspacePullDownDetectController.startTargetApp in launcher")
        } catch (t: Throwable) {
            XposedBridge.log("$TAG: failed to hook WorkspacePullDownDetectController: ${t.message}")
        }
    }
}
