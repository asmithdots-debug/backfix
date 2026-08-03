package com.util.imebackfix

import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

/**
 * Temporarily zeroes window/transition/animator scales around a single action so it applies
 * instantly instead of playing the normal window transition animation, then restores the
 * user's real settings shortly after.
 */
object AnimationScaleHelper {

    private const val TAG = "BackFix"
    private const val RESTORE_DELAY_MS = 250L

    private val zero = floatArrayOf(0f, 0f, 0f)

    fun runWithAnimationsDisabled(handler: android.os.Handler, action: () -> Unit) {
        val iwm = getIWindowManager()
        if (iwm == null) {
            action()
            return
        }
        try {
            val original = XposedHelpers.callMethod(iwm, "getAnimationScales") as FloatArray
            XposedHelpers.callMethod(iwm, "setAnimationScales", zero)
            try {
                action()
            } finally {
                handler.postDelayed({
                    try {
                        XposedHelpers.callMethod(iwm, "setAnimationScales", original)
                    } catch (t: Throwable) {
                        XposedBridge.log("$TAG: restore animation scales failed: ${t.message}")
                    }
                }, RESTORE_DELAY_MS)
            }
        } catch (t: Throwable) {
            XposedBridge.log("$TAG: disabling animations failed: ${t.message}")
            action()
        }
    }

    private fun getIWindowManager(): Any? {
        return try {
            val serviceManagerClass = XposedHelpers.findClass("android.os.ServiceManager", null)
            val binder = XposedHelpers.callStaticMethod(serviceManagerClass, "getService", "window")
            val iwmStubClass = XposedHelpers.findClass("android.view.IWindowManager\$Stub", null)
            XposedHelpers.callStaticMethod(iwmStubClass, "asInterface", binder)
        } catch (t: Throwable) {
            XposedBridge.log("$TAG: getIWindowManager failed: ${t.message}")
            null
        }
    }
}
