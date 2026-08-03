package com.util.imebackfix

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.os.Build
import android.view.accessibility.AccessibilityManager
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

/**
 * Performs a global action (e.g. back) from system_server by creating a fake
 * AccessibilityService bound to the real AccessibilityManagerService connection.
 * Technique borrowed from Xposed Edge Pro / EdgeX.
 */
object GlobalActionHelper {

    private const val TAG = "BackFix"
    const val GLOBAL_ACTION_BACK = 1

    private var fakeService: FakeAccessibilityService? = null

    fun performGlobalAction(context: Context, action: Int): Boolean {
        return try {
            val service = fakeService ?: FakeAccessibilityService(context).also { fakeService = it }
            service.performGlobalAction(action)
        } catch (e: Throwable) {
            XposedBridge.log("$TAG: performGlobalAction failed: ${e.message}")
            fakeService = null
            false
        }
    }

    private class FakeAccessibilityService(context: Context) : AccessibilityService() {
        init {
            val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
            val service = getAccessibilityManagerService(am)
            val connectionId = getConnectionId(service)
            XposedHelpers.setIntField(this, "mConnectionId", connectionId)
            attachBaseContext(context)
        }

        private fun getAccessibilityManagerService(am: AccessibilityManager): Any {
            return try {
                XposedHelpers.getObjectField(am, "mService")
            } catch (e: Throwable) {
                synchronized(XposedHelpers.getObjectField(am, "mLock")) {
                    XposedHelpers.callMethod(am, "getServiceLocked")
                }
            }
        }

        private fun getConnectionId(service: Any): Int {
            return if (Build.VERSION.SDK_INT >= 26) {
                val bridge = XposedHelpers.callMethod(service, "getInteractionBridge")
                XposedHelpers.getIntField(bridge, "mConnectionId")
            } else {
                val lock = XposedHelpers.getObjectField(service, "mLock")
                synchronized(lock) {
                    val bridge = XposedHelpers.callMethod(service, "getInteractionBridgeLocked")
                    XposedHelpers.getIntField(bridge, "mConnectionId")
                }
            }
        }

        override fun onAccessibilityEvent(event: android.view.accessibility.AccessibilityEvent?) {}
        override fun onInterrupt() {}
    }
}
