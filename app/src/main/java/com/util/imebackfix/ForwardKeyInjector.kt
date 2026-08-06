package com.util.imebackfix

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.input.InputManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * Injects a synthetic KEYCODE_FORWARD key press system-wide, on request. Runs in system_server
 * (uid=system), the only realistic place to call InputManager#injectInputEvent - a regular app
 * like Chrome doesn't hold INJECT_EVENTS, and calling Activity#dispatchKeyEvent directly
 * in-process bypasses the normal input pipeline and was silently ignored by Chrome (confirmed
 * by testing: `adb shell input keyevent 125` worked, a bare in-process dispatchKeyEvent did
 * not). ChromeSwipeForwardHook (running inside com.android.chrome) requests this over a
 * broadcast since it has no injection permission of its own.
 */
object ForwardKeyInjector {

    private const val TAG = "BackFix"
    const val ACTION_FIRE_FORWARD_KEY = "com.util.imebackfix.ACTION_FIRE_FORWARD_KEY"

    fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        val activityThreadClass = XposedHelpers.findClass("android.app.ActivityThread", lpparam.classLoader)
        val context = XposedHelpers.callStaticMethod(activityThreadClass, "currentApplication") as? Context
            ?: return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                try {
                    val inputManager = ctx.getSystemService(Context.INPUT_SERVICE) as InputManager
                    val downTime = SystemClock.uptimeMillis()
                    val down = KeyEvent(
                        downTime, downTime, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_FORWARD,
                        0, 0, -1, 0, KeyEvent.FLAG_FROM_SYSTEM, InputDevice.SOURCE_KEYBOARD
                    )
                    val up = KeyEvent(
                        downTime, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, KeyEvent.KEYCODE_FORWARD,
                        0, 0, -1, 0, KeyEvent.FLAG_FROM_SYSTEM, InputDevice.SOURCE_KEYBOARD
                    )
                    // injectInputEvent/INJECT_INPUT_EVENT_MODE_ASYNC are @SystemApi - hidden
                    // from the public SDK we compile against - so call them reflectively.
                    val modeAsync = XposedHelpers.getStaticIntField(InputManager::class.java, "INJECT_INPUT_EVENT_MODE_ASYNC")
                    XposedHelpers.callMethod(inputManager, "injectInputEvent", down, modeAsync)
                    XposedHelpers.callMethod(inputManager, "injectInputEvent", up, modeAsync)
                    XposedBridge.log("$TAG: injected KEYCODE_FORWARD from system_server")
                } catch (t: Throwable) {
                    XposedBridge.log("$TAG: forward key injection failed: ${t.message}")
                }
            }
        }
        registerWithRetry(context, receiver, attempt = 0)
    }

    // handleLoadPackage("android") fires very early in system_server's boot - too early for
    // ActivityManager's binder to be wired up yet, so registerReceiver() throws deep inside the
    // framework (confirmed via LSPosed's module log: NPE in
    // IActivityManager.registerReceiverWithFeature). Retry with backoff instead of guessing a
    // fixed delay.
    private fun registerWithRetry(context: Context, receiver: BroadcastReceiver, attempt: Int) {
        val handler = Handler(Looper.getMainLooper())
        handler.postDelayed({
            try {
                val filter = IntentFilter(ACTION_FIRE_FORWARD_KEY)
                if (Build.VERSION.SDK_INT >= 33) {
                    context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
                } else {
                    @Suppress("UnspecifiedRegisterReceiverFlag")
                    context.registerReceiver(receiver, filter)
                }
                XposedBridge.log("$TAG: registered forward-key broadcast receiver in system_server (attempt ${attempt + 1})")
            } catch (t: Throwable) {
                if (attempt < 10) {
                    XposedBridge.log("$TAG: registerReceiver attempt ${attempt + 1} failed (${t.message}), retrying")
                    registerWithRetry(context, receiver, attempt + 1)
                } else {
                    XposedBridge.log("$TAG: giving up registering forward-key receiver: ${t.message}")
                }
            }
        }, 2000L * (attempt + 1))
    }
}
