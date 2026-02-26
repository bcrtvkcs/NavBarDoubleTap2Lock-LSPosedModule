package com.navbardoubletap2lock;

import android.content.Context;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

import java.lang.reflect.Method;

public class MainHook implements IXposedHookLoadPackage {

    private static final String TAG = "NavBarDoubleTap2Lock";

    private static final int NAV_MODE_3BUTTON = 0;
    private static final int NAV_MODE_GESTURE = 2;

    private static final long TAP_MAX_DURATION = 300;
    private static final float TAP_MAX_DISTANCE = 100f;

    private static final long LOCK_COOLDOWN_MS = 500;

    private static void log(String msg) {
        String fullMsg = TAG + ": " + msg;
        XposedBridge.log(fullMsg);
        Log.d(TAG, msg);
    }

    // Shared lock cooldown timestamp
    private long lastLockTime;

    // Touch state for gesture nav (TaskbarDragLayer hook — view-local coords)
    private float taskbarDownX, taskbarDownY;
    private long taskbarDownTime;
    private long taskbarLastTapTime;

    // Touch state for 3-button nav (NavigationBarFrame hook — view-local coords)
    private float frameDownX, frameDownY;
    private long frameDownTime;
    private long frameLastTapTime;

    @Override
    public void handleLoadPackage(LoadPackageParam lpparam) throws Throwable {
        if ("com.android.systemui".equals(lpparam.packageName)) {
            log("Loaded in SystemUI");
            // 3-button nav: NavigationBarFrame hook
            hookNavigationBarFrame(lpparam.classLoader);
            return;
        }

        if ("com.android.launcher3".equals(lpparam.packageName)) {
            log("Loaded in Launcher3");
            // Gesture nav: TaskbarDragLayer hook
            hookTaskbarDragLayer(lpparam.classLoader);
            return;
        }
    }

    // =========================================================================
    // Gesture navigation: TaskbarDragLayer hook (runs in com.android.launcher3)
    // =========================================================================

    private void hookTaskbarDragLayer(ClassLoader classLoader) {
        try {
            Class<?> dragLayerClass = Class.forName(
                    "com.android.launcher3.taskbar.TaskbarDragLayer", false, classLoader);
            log("Found TaskbarDragLayer");

            Method dispatchTouchEvent = dragLayerClass.getMethod(
                    "dispatchTouchEvent", MotionEvent.class);

            XposedBridge.hookMethod(dispatchTouchEvent, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        View view = (View) param.thisObject;
                        MotionEvent event = (MotionEvent) param.args[0];
                        handleTaskbarTouch(view, event);
                        // IMPORTANT: never consume — let normal gesture nav work
                    } catch (Throwable t) {
                        log("Error in TaskbarDragLayer hook: " + t.getMessage());
                    }
                }
            });
            log("Hooked TaskbarDragLayer.dispatchTouchEvent");
        } catch (Throwable t) {
            log("Failed to hook TaskbarDragLayer: " + t.getMessage());
        }
    }

    private void handleTaskbarTouch(View view, MotionEvent event) {
        int action = event.getActionMasked();

        switch (action) {
            case MotionEvent.ACTION_DOWN:
                taskbarDownX = event.getX();
                taskbarDownY = event.getY();
                taskbarDownTime = event.getEventTime();
                break;

            case MotionEvent.ACTION_MOVE:
                if (taskbarDownTime == 0) return;
                float moveDx = event.getX() - taskbarDownX;
                float moveDy = event.getY() - taskbarDownY;
                if (Math.sqrt(moveDx * moveDx + moveDy * moveDy) > TAP_MAX_DISTANCE) {
                    taskbarDownTime = 0;
                    taskbarLastTapTime = 0;
                }
                break;

            case MotionEvent.ACTION_UP:
                if (taskbarDownTime == 0) break;
                long duration = event.getEventTime() - taskbarDownTime;
                float dx = event.getX() - taskbarDownX;
                float dy = event.getY() - taskbarDownY;
                float dist = (float) Math.sqrt(dx * dx + dy * dy);

                if (duration <= TAP_MAX_DURATION && dist <= TAP_MAX_DISTANCE) {
                    long now = event.getEventTime();
                    int doubleTapTimeout = ViewConfiguration.getDoubleTapTimeout();

                    if (taskbarLastTapTime > 0 && (now - taskbarLastTapTime) <= doubleTapTimeout) {
                        taskbarLastTapTime = 0;
                        long currentTime = SystemClock.uptimeMillis();
                        if (currentTime - lastLockTime >= LOCK_COOLDOWN_MS) {
                            lastLockTime = currentTime;
                            log("Double tap on taskbar — locking screen");
                            lockScreen(view.getContext(), view);
                        }
                    } else {
                        taskbarLastTapTime = now;
                    }
                } else {
                    taskbarLastTapTime = 0;
                }
                taskbarDownTime = 0;
                break;

            case MotionEvent.ACTION_CANCEL:
                taskbarDownTime = 0;
                taskbarLastTapTime = 0;
                break;
        }
    }

    // =========================================================================
    // 3-button navigation: NavigationBarFrame hook (runs in com.android.systemui)
    // =========================================================================

    private boolean hookNavigationBarFrame(ClassLoader classLoader) {
        String[] framePaths = {
                "com.android.systemui.navigationbar.views.NavigationBarFrame", // Android 16+
                "com.android.systemui.navigationbar.NavigationBarFrame",       // Android 12L-15
                "com.android.systemui.statusbar.phone.NavigationBarFrame"      // older
        };

        for (String path : framePaths) {
            try {
                Class<?> frameClass = Class.forName(path, false, classLoader);
                log("Found NavigationBarFrame at " + path);

                try {
                    Method declared = frameClass.getDeclaredMethod(
                            "dispatchTouchEvent", MotionEvent.class);

                    XposedBridge.hookMethod(declared, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                View navFrame = (View) param.thisObject;
                                MotionEvent event = (MotionEvent) param.args[0];
                                handleFrameTouchForDoubleTap(navFrame, event);
                            } catch (Throwable t) {
                                log("Error in NavigationBarFrame hook: " + t.getMessage());
                            }
                        }
                    });

                    log("Hooked NavigationBarFrame.dispatchTouchEvent");
                    return true;

                } catch (NoSuchMethodException e) {
                    log("NavigationBarFrame found but no dispatchTouchEvent override");
                    return true;
                }

            } catch (ClassNotFoundException ignored) {
            }
        }

        return false;
    }

    private void handleFrameTouchForDoubleTap(View navBarView, MotionEvent event) {
        int action = event.getActionMasked();

        switch (action) {
            case MotionEvent.ACTION_DOWN:
                frameDownX = event.getX();
                frameDownY = event.getY();
                frameDownTime = event.getEventTime();
                break;

            case MotionEvent.ACTION_UP:
                float dx = event.getX() - frameDownX;
                float dy = event.getY() - frameDownY;
                long duration = event.getEventTime() - frameDownTime;
                float distance = (float) Math.sqrt(dx * dx + dy * dy);

                if (duration < TAP_MAX_DURATION && distance < TAP_MAX_DISTANCE) {
                    long now = event.getEventTime();
                    int doubleTapTimeout = ViewConfiguration.getDoubleTapTimeout();

                    if (frameLastTapTime > 0 && (now - frameLastTapTime) <= doubleTapTimeout) {
                        frameLastTapTime = 0;

                        long currentTime = SystemClock.uptimeMillis();
                        if (currentTime - lastLockTime < LOCK_COOLDOWN_MS) {
                            break;
                        }

                        Context context = navBarView.getContext();
                        if (shouldHandleDoubleTap(context, navBarView, frameDownX, frameDownY)) {
                            lastLockTime = currentTime;
                            log("Double tap detected on NavigationBarFrame — locking screen");
                            lockScreen(context, navBarView);
                        }
                    } else {
                        frameLastTapTime = now;
                    }
                } else {
                    frameLastTapTime = 0;
                }
                break;

            case MotionEvent.ACTION_CANCEL:
                frameLastTapTime = 0;
                break;
        }
    }

    private boolean shouldHandleDoubleTap(Context context, View navBarView, float tapX, float tapY) {
        int navMode = getNavigationMode(context);

        if (navMode == NAV_MODE_GESTURE) {
            return true;
        }

        // 3-button / 2-button mode: ignore taps on back and recent_apps buttons
        return !isTapOnExcludedButton(navBarView, tapX, tapY);
    }

    private boolean isTapOnExcludedButton(View navBarView, float tapX, float tapY) {
        try {
            Context context = navBarView.getContext();
            String[] excludedIds = {"back", "recent_apps"};

            int[] navLoc = new int[2];
            navBarView.getLocationOnScreen(navLoc);
            float screenX = navLoc[0] + tapX;
            float screenY = navLoc[1] + tapY;

            for (String id : excludedIds) {
                int resId = context.getResources().getIdentifier(
                        id, "id", "com.android.systemui");
                if (resId != 0) {
                    View button = navBarView.findViewById(resId);
                    if (button != null && button.getVisibility() == View.VISIBLE) {
                        int[] btnLoc = new int[2];
                        button.getLocationOnScreen(btnLoc);
                        if (screenX >= btnLoc[0] && screenX <= btnLoc[0] + button.getWidth()
                                && screenY >= btnLoc[1] && screenY <= btnLoc[1] + button.getHeight()) {
                            return true;
                        }
                    }
                }
            }
        } catch (Throwable t) {
            log("Error checking excluded buttons: " + t.getMessage());
        }
        return false;
    }

    private int getNavigationMode(Context context) {
        try {
            int resId = context.getResources().getIdentifier(
                    "config_navBarInteractionMode", "integer", "android");
            if (resId != 0) {
                return context.getResources().getInteger(resId);
            }
        } catch (Throwable t) {
            log("Error reading nav mode from resource: " + t.getMessage());
        }

        try {
            return android.provider.Settings.Secure.getInt(
                    context.getContentResolver(), "navigation_mode", NAV_MODE_3BUTTON);
        } catch (Throwable t) {
            log("Error reading nav mode from settings: " + t.getMessage());
        }

        return NAV_MODE_3BUTTON;
    }

    // =========================================================================
    // Screen lock methods (shared by both hooks)
    // =========================================================================

    private void lockScreen(Context context, View view) {
        if (!tryInjectSleepKey(context)) {
            if (!tryGoToSleep(context)) {
                tryShellSleepCommand();
            }
        }

        view.postDelayed(() -> {
            try {
                PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
                if (pm != null && pm.isInteractive()) {
                    log("WARNING: Screen still interactive 200ms after lock attempt");
                }
            } catch (Throwable ignored) {}
        }, 200);
    }

    private boolean tryInjectSleepKey(Context context) {
        try {
            log("Trying KEYCODE_SLEEP injection...");
            Object inputManager = context.getSystemService(Context.INPUT_SERVICE);
            if (inputManager == null) {
                log("InputManager is null from getSystemService, trying getInstance...");
                try {
                    Method getInstance = Class.forName("android.hardware.input.InputManager")
                            .getMethod("getInstance");
                    inputManager = getInstance.invoke(null);
                } catch (Throwable t2) {
                    log("InputManager not available: " + t2.getMessage());
                    return false;
                }
            }
            if (inputManager == null) {
                log("InputManager is null");
                return false;
            }

            long now = SystemClock.uptimeMillis();
            KeyEvent down = new KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SLEEP,
                    0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0,
                    KeyEvent.FLAG_FROM_SYSTEM, InputDevice.SOURCE_KEYBOARD);
            KeyEvent up = KeyEvent.changeAction(down, KeyEvent.ACTION_UP);

            Method injectMethod = inputManager.getClass().getMethod(
                    "injectInputEvent", InputEvent.class, int.class);
            injectMethod.invoke(inputManager, down, 0);
            injectMethod.invoke(inputManager, up, 0);

            log("KEYCODE_SLEEP injection completed");
            return true;
        } catch (Throwable t) {
            Throwable cause = (t instanceof java.lang.reflect.InvocationTargetException && t.getCause() != null)
                    ? t.getCause() : t;
            log("KEYCODE_SLEEP injection failed: " + cause.getClass().getSimpleName()
                    + ": " + cause.getMessage());
            return false;
        }
    }

    private boolean tryGoToSleep(Context context) {
        try {
            PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            if (pm == null) return false;

            log("Trying goToSleep...");
            try {
                Method goToSleep = PowerManager.class.getMethod(
                        "goToSleep", long.class, int.class, int.class);
                goToSleep.invoke(pm, SystemClock.uptimeMillis(), 4, 0);
            } catch (NoSuchMethodException e) {
                Method goToSleep = PowerManager.class.getMethod("goToSleep", long.class);
                goToSleep.invoke(pm, SystemClock.uptimeMillis());
            }

            log("goToSleep call completed");
            return true;
        } catch (Throwable t) {
            Throwable cause = (t instanceof java.lang.reflect.InvocationTargetException && t.getCause() != null)
                    ? t.getCause() : t;
            log("goToSleep failed: " + cause.getClass().getSimpleName()
                    + ": " + cause.getMessage());
            return false;
        }
    }

    private void tryShellSleepCommand() {
        try {
            log("Trying shell command fallback...");
            Runtime.getRuntime().exec(new String[]{"input", "keyevent", "223"});
            log("Shell command dispatched");
        } catch (Throwable t) {
            log("Shell command failed: " + t.getMessage());
        }
    }
}
