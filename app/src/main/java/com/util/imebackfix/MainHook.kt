package com.util.imebackfix

import android.content.Context
import android.os.Handler
import android.os.Looper
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * When a back gesture only closes the IME (SoftInputShowHideReason.HIDE_SOFT_INPUT_BY_BACK_KEY),
 * automatically re-fires a back action shortly after, so a single swipe both dismisses the
 * keyboard and navigates back instead of requiring two separate swipes.
 */
class MainHook : IXposedHookLoadPackage {

    companion object {
        private const val TAG = "BackFix"
        private const val REFIRE_COOLDOWN_MS = 800L
        // Yandex Keyboard's own "collapse keyboard" gesture on the keyboard surface reuses the
        // exact same SoftInputShowHideReason.HIDE_SOFT_INPUT_BY_BACK_KEY value as a genuine back
        // key/gesture (confirmed by logging both: both report reason=29). So the reason alone
        // cannot tell them apart. BackNavigationController.startBackNavigation() is the single
        // system_server entry point for every real back key/gesture dispatch (it still runs even
        // when predictive-back animation is off, it just returns early), so treat it as a fresh
        // marker of "a real back just happened" and only act on reason=29 hides that land within
        // this window of it - the keyboard's own gesture never goes through that method at all.
        private const val REAL_BACK_CORRELATION_WINDOW_MS = 250L
    }

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    @Volatile private var lastFireAt = 0L
    @Volatile private var lastRealBackNavigationAt = 0L

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        when (lpparam.packageName) {
            "android" -> {
                try {
                    hookBackNavigationController(lpparam)
                    XposedBridge.log("$TAG: hooked BackNavigationController in system_server")
                } catch (t: Throwable) {
                    XposedBridge.log("$TAG: failed to hook BackNavigationController: ${t.message}")
                }
                try {
                    hookImms(lpparam)
                    XposedBridge.log("$TAG: hooked InputMethodManagerService in system_server")
                } catch (t: Throwable) {
                    XposedBridge.log("$TAG: failed to hook IMMS: ${t.message}")
                }
                try {
                    ForwardKeyInjector.install(lpparam)
                } catch (t: Throwable) {
                    XposedBridge.log("$TAG: failed to install ForwardKeyInjector: ${t.message}")
                }
            }
            "com.oppo.quicksearchbox" -> QuickSearchBoxHook.install(lpparam)
            "com.android.launcher" -> {
                LauncherHook.install(lpparam)
                IconSizeHook.install(lpparam)
            }
            "com.android.chrome" -> {
                try {
                    ChromeSwipeForwardHook.install(lpparam)
                } catch (t: Throwable) {
                    XposedBridge.log("$TAG: failed to hook Chrome swipe-forward: ${t.message}")
                }
            }
        }
    }

    private fun hookBackNavigationController(lpparam: XC_LoadPackage.LoadPackageParam) {
        val backNavClass = XposedHelpers.findClass(
            "com.android.server.wm.BackNavigationController", lpparam.classLoader
        )
        val hook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                lastRealBackNavigationAt = System.currentTimeMillis()
            }
        }
        XposedBridge.hookAllMethods(backNavClass, "startBackNavigation", hook)
    }

    private fun hookImms(lpparam: XC_LoadPackage.LoadPackageParam) {
        val immsClass = XposedHelpers.findClass(
            "com.android.server.inputmethod.InputMethodManagerService", lpparam.classLoader
        )
        val reasonClass = XposedHelpers.findClass(
            "com.android.internal.inputmethod.SoftInputShowHideReason", lpparam.classLoader
        )
        val hideByBackKeyReason = XposedHelpers.getStaticIntField(reasonClass, "HIDE_SOFT_INPUT_BY_BACK_KEY")

        val hook = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                try {
                    // hideMySoftInputLocked(ImeTracker.Token statsToken, int flags, int reason, UserData userData)
                    // Must check the reason arg specifically - flags is also an Int and can
                    // coincidentally share a value with the reason enum, causing false matches
                    // on unrelated hides (e.g. tapping the keyboard's own dismiss button).
                    val reasonArg = param.args.getOrNull(2) as? Int
                    if (reasonArg != hideByBackKeyReason) return

                    val now = System.currentTimeMillis()
                    val sinceRealBack = now - lastRealBackNavigationAt
                    XposedBridge.log("$TAG: reason=BACK_KEY hide, sinceRealBackNavigation=${sinceRealBack}ms")
                    if (sinceRealBack > REAL_BACK_CORRELATION_WINDOW_MS) return
                    if (now - lastFireAt < REFIRE_COOLDOWN_MS) return
                    lastFireAt = now

                    val context = XposedHelpers.getObjectField(param.thisObject, "mContext") as Context
                    mainHandler.post {
                        AnimationScaleHelper.runWithAnimationsDisabled(mainHandler) {
                            GlobalActionHelper.performGlobalAction(context, GlobalActionHelper.GLOBAL_ACTION_BACK)
                        }
                    }
                } catch (t: Throwable) {
                    XposedBridge.log("$TAG: hook body failed: ${t.message}")
                }
            }
        }

        XposedBridge.hookAllMethods(immsClass, "hideMySoftInputLocked", hook)
    }
}
