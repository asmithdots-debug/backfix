package com.util.imebackfix

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.window.BackEvent
import android.window.OnBackAnimationCallback
import android.window.OnBackInvokedCallback
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.util.Collections

/**
 * iOS-Safari-style "swipe from the right edge to go forward" for Chrome.
 *
 * Left-edge swipe already goes back (unchanged). A right-edge swipe currently triggers the
 * exact *same* back action - there's no built-in forward gesture, even though the plumbing to
 * tell edges apart already exists: Chrome participates in Android's Predictive Back API
 * (registers an OnBackAnimationCallback via Activity/Window#getOnBackInvokedDispatcher), and
 * the framework - not Chrome's own touch handling - is what recognizes the edge-swipe gesture
 * and delivers it as a BackEvent carrying getSwipeEdge(). Confirmed this dispatcher-based path
 * is the real one (not raw touch reaching Chrome's own dispatchTouchEvent) by testing: a hook
 * on Activity#dispatchTouchEvent never fired for an edge swipe, meaning the launcher/SystemUI
 * gesture-nav layer consumes/pilfers those touches before the app's window ever sees them.
 *
 * This wraps whatever callback Chrome registers with the platform dispatcher: left-edge (or
 * unknown) events pass through untouched, so existing back behavior and Chrome's own back
 * preview animation are unaffected. A right-edge gesture is fully suppressed from reaching
 * Chrome's real callback and, on completion, is replaced with a synthetic KEYCODE_FORWARD key
 * event - a real Android key (companion of KEYCODE_BACK) already confirmed (via
 * `adb shell input keyevent 125` against real in-tab navigation history) to make Chrome go
 * forward, multi-level, exactly like a browser back/forward stack.
 */
object ChromeSwipeForwardHook {

    private const val TAG = "BackFix"

    private val hookedDispatcherClasses = Collections.synchronizedSet(HashSet<Class<*>>())
    @Volatile private var currentActivity: Activity? = null

    fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (Build.VERSION.SDK_INT < 33) return
        XposedHelpers.findAndHookMethod(
            Activity::class.java,
            "getOnBackInvokedDispatcher",
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    currentActivity = param.thisObject as? Activity
                    val dispatcher = param.result ?: return
                    hookDispatcherClass(dispatcher.javaClass)
                }
            }
        )
        XposedBridge.log("$TAG: installed OnBackInvokedDispatcher hook in ${lpparam.packageName}")
    }

    private fun hookDispatcherClass(cls: Class<*>) {
        if (!hookedDispatcherClasses.add(cls)) return
        try {
            XposedHelpers.findAndHookMethod(
                cls,
                "registerOnBackInvokedCallback",
                Int::class.javaPrimitiveType,
                OnBackInvokedCallback::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val original = param.args[1] as? OnBackInvokedCallback ?: return
                        if (original is RightEdgeForwardCallback) return
                        param.args[1] = RightEdgeForwardCallback(original)
                    }
                }
            )
            XposedBridge.log("$TAG: hooked registerOnBackInvokedCallback on ${cls.name}")
        } catch (t: Throwable) {
            XposedBridge.log("$TAG: failed to hook registerOnBackInvokedCallback on ${cls.name}: ${t.message}")
        }
    }

    private class RightEdgeForwardCallback(
        private val original: OnBackInvokedCallback
    ) : OnBackAnimationCallback {

        private var suppressingForForward = false

        override fun onBackStarted(backEvent: BackEvent) {
            suppressingForForward = backEvent.swipeEdge == BackEvent.EDGE_RIGHT
            if (suppressingForForward) return
            (original as? OnBackAnimationCallback)?.onBackStarted(backEvent)
        }

        override fun onBackProgressed(backEvent: BackEvent) {
            if (suppressingForForward) return
            (original as? OnBackAnimationCallback)?.onBackProgressed(backEvent)
        }

        override fun onBackCancelled() {
            if (suppressingForForward) {
                suppressingForForward = false
                return
            }
            (original as? OnBackAnimationCallback)?.onBackCancelled()
        }

        override fun onBackInvoked() {
            if (suppressingForForward) {
                suppressingForForward = false
                fireForward()
                return
            }
            original.onBackInvoked()
        }

        private fun fireForward() {
            val activity = currentActivity ?: run {
                XposedBridge.log("$TAG: no known Activity to request KEYCODE_FORWARD from")
                return
            }
            try {
                // A bare in-process Activity#dispatchKeyEvent bypasses the normal input
                // pipeline and was silently ignored by Chrome (confirmed: `adb shell input
                // keyevent 125` worked, dispatchKeyEvent alone did not). Real injection needs
                // InputManager#injectInputEvent, which requires INJECT_EVENTS - Chrome doesn't
                // hold that, so ask ForwardKeyInjector (running in system_server, uid=system)
                // to do it instead.
                activity.sendBroadcast(Intent(ForwardKeyInjector.ACTION_FIRE_FORWARD_KEY))
                XposedBridge.log("$TAG: right-edge back-invoked suppressed, requested KEYCODE_FORWARD")
            } catch (t: Throwable) {
                XposedBridge.log("$TAG: fireForward failed: ${t.message}")
            }
        }
    }
}
