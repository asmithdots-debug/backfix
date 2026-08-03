package com.util.imebackfix

import android.app.Activity
import android.util.Log
import android.widget.EditText
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * ColorOS Global Search (com.oppo.quicksearchbox) already has an "Enter launches the top
 * result" feature (SearchBar's onEditorAction -> r0() -> ShelfSearchViewModel.Z0(), logged
 * internally under the tag "TopResult.ImeAction") but it silently no-ops in two spots:
 *  1) SearchBar.r0() only calls the best-match listener when its internal state enum happens
 *     to be in the exact right value at the moment Enter is pressed.
 *  2) ShelfSearchViewModel.Z0() additionally requires the top-result payload's cached key to
 *     still match the current query text, which can be stale due to search debouncing.
 * Both hooks below bypass those checks so Enter reliably launches whatever the top result
 * currently displayed is, matching iOS Spotlight behavior.
 */
object QuickSearchBoxHook {

    private const val TAG = "BackFix"

    @Volatile private var bestMatchListenerField: java.lang.reflect.Field? = null

    fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        hookFeatureFlag(lpparam)
        hookSearchBarEnter(lpparam)
        hookTopResultDispatch(lpparam)
        hookClearOnResume(lpparam)
        hookFinishOnUserLeave(lpparam)
    }

    /**
     * SearchActivity never finishes itself after launching an app from a result (true whether
     * launched by a tap or by our Enter fix - neither path calls finish()), so it just sits
     * paused in its own task. Leaving the launched app via back/home then surfaces this stale
     * search screen instead of the real home screen. onUserLeaveHint() fires precisely when the
     * user intentionally navigates away (launching something, pressing home) and not for
     * incidental focus loss (dialogs, notification shade), so it's the correct point to close it.
     */
    private fun hookFinishOnUserLeave(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            XposedBridge.hookAllMethods(Activity::class.java, "onUserLeaveHint", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val activity = param.thisObject as? Activity ?: return
                    if (activity.javaClass.name != "com.oplus.globalsearch.ui.SearchActivity") return
                    try {
                        if (!activity.isFinishing) activity.finish()
                    } catch (t: Throwable) {
                        XposedBridge.log("$TAG: finish-on-user-leave failed: ${Log.getStackTraceString(t)}")
                    }
                }
            })
            XposedBridge.log("$TAG: hooked Activity.onUserLeaveHint for SearchActivity auto-finish")
        } catch (t: Throwable) {
            XposedBridge.log("$TAG: failed to hook onUserLeaveHint: ${t.message}")
        }
    }

    /**
     * The search Activity is reused (not recreated) between openings, so leftover query text
     * survives silently. Clear it on resume so reopening the search always starts from an empty
     * field instead of the previous query.
     */
    private fun hookClearOnResume(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val activityClass = XposedHelpers.findClass(
                "com.oplus.globalsearch.ui.SearchActivity", lpparam.classLoader
            )
            val doClear = { param: XC_MethodHook.MethodHookParam ->
                try {
                    val editText = XposedHelpers.callMethod(param.thisObject, "gdk") as? EditText
                    if (editText != null && !editText.text.isNullOrEmpty()) {
                        editText.setText("")
                    }
                } catch (t: Throwable) {
                    XposedBridge.log("$TAG: clear-on-resume failed: ${Log.getStackTraceString(t)}")
                }
            }
            XposedBridge.hookAllMethods(activityClass, "onResume", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) = doClear(param)
            })
            XposedBridge.log("$TAG: hooked SearchActivity.onResume for clear")
        } catch (t: Throwable) {
            XposedBridge.log("$TAG: failed to hook SearchActivity.onResume: ${t.message}")
        }
    }

    /**
     * Pk.gdt.gdb() (TopResultFeature.isEnableSearchBarAndIme) gates the whole "Enter launches
     * top result" pipeline behind a remote rollout flag (TrackingConfigManager value must be
     * exactly 2) that isn't enabled for this device/account, so ShelfSearchViewModel.z1() never
     * populates the LiveData the rest of the flow depends on. Force it on unconditionally.
     */
    private fun hookFeatureFlag(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val flagClass = XposedHelpers.findClass("Pk.gdt", lpparam.classLoader)
            XposedBridge.hookAllMethods(flagClass, "gdb", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    param.result = true
                }
            })
            XposedBridge.log("$TAG: hooked Pk.gdt.gdb feature flag in quicksearchbox")
        } catch (t: Throwable) {
            XposedBridge.log("$TAG: failed to hook Pk.gdt.gdb: ${t.message}")
        }
    }

    /** Walks up the class hierarchy looking for the (single) field whose type is [typeClass]. */
    private fun findFieldByType(startClass: Class<*>, typeClass: Class<*>): java.lang.reflect.Field? {
        var current: Class<*>? = startClass
        while (current != null) {
            for (field in current.declaredFields) {
                if (typeClass.isAssignableFrom(field.type)) {
                    field.isAccessible = true
                    return field
                }
            }
            current = current.superclass
        }
        return null
    }

    private fun hookSearchBarEnter(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val searchBarClass = XposedHelpers.findClass(
                "com.oplus.globalsearch.ui.widget.SearchBar", lpparam.classLoader
            )
            val listenerInterface = XposedHelpers.findClass(
                "com.oplus.globalsearch.ui.widget.gdb.gdc", lpparam.classLoader
            )
            XposedBridge.hookAllMethods(searchBarClass, "r0", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    try {
                        XposedBridge.log("$TAG: SearchBar.r0 called, thisClass=${param.thisObject.javaClass.name}")
                        val field = bestMatchListenerField
                            ?: findFieldByType(param.thisObject.javaClass, listenerInterface)
                                ?.also { bestMatchListenerField = it }
                        if (field == null) {
                            XposedBridge.log("$TAG: no field of type $listenerInterface found")
                            return
                        }
                        val listener = field.get(param.thisObject)
                        XposedBridge.log("$TAG: listener field=${field.name} value=$listener")
                        if (listener == null) return
                        val handled = XposedHelpers.callMethod(listener, "gda") as? Boolean
                        XposedBridge.log("$TAG: gda() returned $handled")
                        if (handled == null) return
                        param.result = handled
                    } catch (t: Throwable) {
                        XposedBridge.log("$TAG: SearchBar.r0 override failed: ${Log.getStackTraceString(t)}")
                    }
                }
            })
            XposedBridge.log("$TAG: hooked SearchBar.r0 in quicksearchbox")
        } catch (t: Throwable) {
            XposedBridge.log("$TAG: failed to hook SearchBar.r0: ${t.message}")
        }
    }

    private fun hookTopResultDispatch(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val viewModelClass = XposedHelpers.findClass(
                "com.oplus.globalsearch.ui.viewmodel.ShelfSearchViewModel", lpparam.classLoader
            )
            XposedBridge.hookAllMethods(viewModelClass, "Z0", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    try {
                        val payload = param.args.getOrNull(0)
                        XposedBridge.log("$TAG: ShelfSearchViewModel.Z0 called, payload=$payload")
                        if (payload == null) return
                        val context = XposedHelpers.callMethod(param.thisObject, "l")
                        val dispatchClass = XposedHelpers.findClass("Pk.b", lpparam.classLoader)
                        val result = XposedHelpers.callStaticMethod(dispatchClass, "gdb", context, payload)
                        XposedBridge.log("$TAG: Pk.b.gdb returned $result")
                        param.result = result
                    } catch (t: Throwable) {
                        XposedBridge.log("$TAG: ShelfSearchViewModel.Z0 override failed: ${Log.getStackTraceString(t)}")
                    }
                }
            })
            XposedBridge.log("$TAG: hooked ShelfSearchViewModel.Z0 in quicksearchbox")
        } catch (t: Throwable) {
            XposedBridge.log("$TAG: failed to hook ShelfSearchViewModel.Z0: ${t.message}")
        }
    }
}
