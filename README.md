# BackFix

An [LSPosed](https://github.com/LSPosed/LSPosed) module that fixes a handful of specific,
reproducible UX papercuts on a rooted OnePlus / ColorOS device running Android 16, found by
reverse-engineering the relevant system apps rather than guessing.

Built and verified against:

- OnePlus 12 (`CPH2747`), Android 16 / API 36, ColorOS-based build
- Root: Magisk
- Xposed framework: [Vector](https://github.com/JingMatrix/LSPosed) (JingMatrix's LSPosed fork), Zygisk mode
- Keyboard: Yandex Keyboard (`ru.yandex.androidkeyboard`, AOSP `LatinIME`-derived)
- Global Search: ColorOS Global Search (`com.oppo.quicksearchbox`)
- Launcher: OPlus/OnePlus System Launcher (`com.android.launcher`)

None of the fixes are generic "works on every ROM" hacks — every one of them targets an exact
method on this exact build, found by decompiling the relevant APK with
[jadx](https://github.com/skylot/jadx) and reading the real code. **They will very likely need
re-verifying (method/field names, reason codes, etc. can all shift) after an OS or app update.**
See [How this was built](#how-this-was-built) below for the actual approach if you need to adapt
this to a different device.

## What it fixes

### 1. Back gesture only closes the keyboard, needs a second swipe to actually go back

Stock Android behavior: if the keyboard is open and you swipe/press back, the *first* back only
dismisses the keyboard; you need a *second* back to navigate away from the screen. iOS collapses
both into one gesture. This module makes Android do the same, while leaving the keyboard's own
"collapse" gesture (swipe-down-on-keyboard) untouched — pressing that just closes the keyboard,
same as before, with no extra navigation.

**Where:** `system_server`, scope `android` (system framework).

**How:**

- Hooks `InputMethodManagerService#hideMySoftInputLocked` and checks the `reason` argument for
  `SoftInputShowHideReason.HIDE_SOFT_INPUT_BY_BACK_KEY`.
- Problem: Yandex Keyboard's own on-keyboard "collapse" gesture reports the **exact same** reason
  code as a real back key/gesture (confirmed by logging both — both come back as `reason=29`), so
  the reason alone can't tell them apart.
- Fix: also hooks `BackNavigationController#startBackNavigation` — the single system_server entry
  point for every genuine back key/gesture dispatch (still called even when predictive-back
  animation is off; it just returns early) — and stamps a timestamp. The IME hide is only treated
  as "real back" if it lands within ~250ms of that timestamp. The keyboard's own gesture never
  goes through `startBackNavigation` at all, so it's naturally excluded.
- When a real back-triggered hide is confirmed, replays a synthetic `GLOBAL_ACTION_BACK` via a
  fake `AccessibilityService` bound directly to `AccessibilityManagerService` (the same technique
  used by [EdgeX](https://github.com/fcmfcm1999/EdgeX) / Xposed Edge Pro), with window/transition
  animation scales temporarily zeroed so the second "back" applies instantly instead of playing
  its own transition animation on top of the keyboard's close animation.

### 2. Global Search: pressing Enter doesn't launch the top result

ColorOS Global Search already *has* an "Enter launches the top result" feature — internally
tagged `"TopResult.ImeAction"` — but it silently no-ops in three separate places on this build:

1. `Pk.gdt.gdb()` (`TopResultFeature.isEnableSearchBarAndIme`) gates the entire feature behind a
   remote rollout flag (`TrackingConfigManager` value must be exactly `2`) that isn't enabled for
   this device/account, so the top-result `LiveData` the rest of the flow depends on never gets
   populated in the first place.
2. `SearchBar.r0()` only forwards the Enter press to the best-match listener when its internal
   state enum happens to be in exactly the right value at that moment.
3. `ShelfSearchViewModel.Z0()` additionally requires the top-result payload's cached key to still
   match the current query text, which can be stale due to search debouncing.

**Where:** app process, scope `com.oppo.quicksearchbox`.

**How:** three targeted hooks, one per gate above — force the feature flag to `true`, always
forward Enter to the best-match listener via reflection instead of trusting the state check, and
bypass the stale-key check in `Z0()` by calling the real dispatch function (`Pk.b.gdb`) directly.

### 3. Global Search keeps the previous query text when reopened

The search `Activity` is reused (not recreated) between openings, so whatever you typed last time
is still sitting in the field, unselected — typing just appends to it. Now the field is cleared on
every `onResume`, so reopening search always starts from empty.

### 4. Global Search doesn't close itself after you launch something from it

Leaving an app you launched from Global Search (back or home gesture) landed you back on the
stale search screen instead of the home screen — `SearchActivity` never calls `finish()` after
dispatching a launch, whether from a tap or from fix #2's Enter path.

**Fix:** hooks `Activity#onUserLeaveHint()` (fires precisely when the user intentionally
navigates away — launching something, pressing home — and *not* for incidental focus loss like a
dialog or the notification shade) and calls `finish()` on `SearchActivity` specifically.

### 5. Notifications / Quick Settings only open from the very top edge of the screen

Swiping down from mid-screen already opens Global Search (a stock launcher gesture); swiping down
to open notifications or quick settings was still restricted to a thin strip at the very top-left
/ top-right corner. This splits that same "pull down anywhere" gesture into thirds by horizontal
start position: left third expands notifications, right third expands quick settings, middle
third keeps the existing search behavior — all three reachable from anywhere down the screen.

**Where:** app process, scope `com.android.launcher`.

**How:** hooks `WorkspacePullDownDetectController#startTargetApp` (the point where the launcher
has decided the pull-down gesture crossed its trigger threshold), reads its own `mTouchDownX`
field, and for the outer thirds calls `StatusBarManager#expandNotificationsPanel` /
`#expandSettingsPanel` via reflection (both are `@SystemApi`/hidden, but `com.android.launcher`
is a privileged system app so the call succeeds with no extra permission grant needed) instead of
letting the original method open search. Also calls the controller's own `pullCancel()` and
returns `false`, otherwise the launcher's pull-down animator is left thinking an app/search
activity is about to appear and never restores the workspace (blurred/hidden icons) — which
showed up as a blank home screen after opening notifications/settings this way and then going
home.

## Requirements

- Root (Magisk)
- LSPosed-compatible framework active with **system framework** scope support (tested with
  [Vector](https://github.com/JingMatrix/LSPosed))
- Enabling fix #1 requires the module to have **system framework** scope in your Xposed manager,
  which needs a **reboot** to take effect. Fixes #2–#5 only need their respective app's process
  restarted (`adb shell am force-stop <package>`), no reboot needed.

## Building

```
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Then enable the module in your Xposed manager with scope on: `android` (system framework),
`com.oppo.quicksearchbox`, `com.android.launcher` — adjust package names if your ROM's Global
Search / launcher differ.

## How this was built

Every fix here came from the same loop, not from guessing at public APIs:

1. Identify the exact foreground `Activity`/component for the broken behavior via
   `adb shell dumpsys activity activities` / `dumpsys window`.
2. Pull the real APK off the device (`adb shell pm path <pkg>`, then `su cp` to a world-readable
   path since most APKs live under a directory `adb pull` can't read directly, then `adb pull`).
3. Decompile it with `jadx -d out --no-res -j 4 app.apk` and read the actual (obfuscated but
   real) source to find the method/field that's actually responsible, instead of hooking
   something that merely looks plausible.
4. Hook it with LSPosed's classic Xposed API (`XposedHelpers` / `XC_MethodHook`), verifying via
   `XposedBridge.log` and `adb shell su -c 'grep ... /data/adb/lspd/log/modules_*.log'` that the
   hook actually fires with the values you expect — obfuscated field names in particular can
   differ from what a decompiler shows at build time vs. runtime; when that happened
   (`SearchBar`'s best-match listener field), the fix was to look the field up **by type** at
   runtime instead of trusting a literal name.
5. Test the real behavior, not just "no exception was thrown" — e.g. fix #1 was only proven
   correct by confirming the timing correlation in the logs (`sinceRealBackNavigation` in the
   tens of ms for a genuine back gesture vs. absent for the keyboard's own gesture), and fix #5
   only surfaced once the actual before/after workspace state was checked, not just whether the
   panel opened.

## Disclaimer

This decompiles and hooks internals of proprietary, closed-source system apps (ColorOS Global
Search, the OnePlus/OPlus launcher) and a third-party keyboard (Yandex Keyboard) that were not
designed for this. Method names, field names, and internal logic are unstable across app/OS
updates by nature — expect this to need re-verification (or outright breakage) after updates to
any of the hooked apps. No decompiled third-party source is included in this repository — only
the original hook code, which is what actually runs.
