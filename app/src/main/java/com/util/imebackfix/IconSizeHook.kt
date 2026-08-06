package com.util.imebackfix

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * Scales workspace and dock icons past the OEM launcher's own icon-size slider, which caps out
 * at `IconParam.MAX_ICON_IMAGE_SIZE` (60dp) - not big enough for an iOS-style no-label look.
 *
 * Workspace icons and dock (hotseat) icons are computed completely independently on this build:
 * `IconParam.getIconSizePx()` drives every workspace/DeviceProfile grid computation, but the
 * dock reads its own private `HotseatParam.hotseatIconSizePx` field - set once in the
 * constructor straight from `InvariantDeviceProfile.iconSize[0]`, never going through
 * IconParam's getter at all. Confirmed by testing: scaling only IconParam left the dock
 * unchanged. Both need their own hook.
 */
object IconSizeHook {

    private const val TAG = "BackFix"
    private const val SCALE = 1.1f

    fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val iconParamClass = XposedHelpers.findClass(
                "com.android.launcher.layoutparam.IconParam", lpparam.classLoader
            )
            XposedBridge.hookAllMethods(iconParamClass, "getIconSizePx", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val original = param.result as? Int ?: return
                    param.result = (original * SCALE).toInt()
                }
            })
            XposedBridge.log("$TAG: hooked IconParam.getIconSizePx in launcher")
        } catch (t: Throwable) {
            XposedBridge.log("$TAG: failed to hook IconParam.getIconSizePx: ${t.message}")
        }

        try {
            val hotseatParamClass = XposedHelpers.findClass(
                "com.android.launcher.layoutparam.HotseatParam", lpparam.classLoader
            )
            XposedBridge.hookAllConstructors(hotseatParamClass, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val original = XposedHelpers.getIntField(param.thisObject, "hotseatIconSizePx")
                    XposedHelpers.setIntField(param.thisObject, "hotseatIconSizePx", (original * SCALE).toInt())
                }
            })
            XposedBridge.log("$TAG: hooked HotseatParam.hotseatIconSizePx in launcher")
        } catch (t: Throwable) {
            XposedBridge.log("$TAG: failed to hook HotseatParam.hotseatIconSizePx: ${t.message}")
        }
    }
}
