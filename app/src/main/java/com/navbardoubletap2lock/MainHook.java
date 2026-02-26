package com.navbardoubletap2lock;

import android.content.Context;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

public class MainHook implements IXposedHookLoadPackage {

    private static final String TAG = "NavBarDoubleTap2Lock";

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
            hookNavigationBarFrame(lpparam.classLoader);
            return;
        }

        if ("com.android.launcher3".equals(lpparam.packageName)) {
            log("Loaded in Launcher3");
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

                        // Exclude taps on back/recent buttons — only allow home button area
                        if (isTapOnNonHomeButton(navBarView, frameDownX, frameDownY)) {
                            log("Double tap excluded — tap is on back/recent button");
                            break;
                        }

                        lastLockTime = currentTime;
                        log("Double tap detected on NavigationBarFrame — locking screen");
                        lockScreen(navBarView.getContext(), navBarView);
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

    // =========================================================================
    // 3-button nav: exclude taps on Back/Recent buttons via KeyButtonView.mCode
    // =========================================================================

    /**
     * Walk the view hierarchy under navBarView, find KeyButtonView descendants,
     * and check if the tap landed on one whose mCode != KEYCODE_HOME.
     * Returns true if tap is on a non-home KeyButtonView (back, recent, etc.).
     */
    private boolean isTapOnNonHomeButton(View navBarView, float tapX, float tapY) {
        try {
            int[] navLoc = new int[2];
            navBarView.getLocationOnScreen(navLoc);
            float screenX = navLoc[0] + tapX;
            float screenY = navLoc[1] + tapY;

            return findNonHomeKeyButton(navBarView, screenX, screenY);
        } catch (Throwable t) {
            log("Error in button exclusion: " + t.getMessage());
        }
        return false;
    }

    private static int debugButtonLogCount = 0;
    private static final int DEBUG_BUTTON_LOG_MAX = 3;

    private boolean findNonHomeKeyButton(View view, float screenX, float screenY) {
        // Check if this view is a KeyButtonView (by class name, works across package renames)
        String className = view.getClass().getName();
        if (className.contains("KeyButtonView")) {
            int keyCode = getKeyButtonCode(view);
            int[] loc = new int[2];
            view.getLocationOnScreen(loc);
            boolean inBounds = screenX >= loc[0] && screenX <= loc[0] + view.getWidth()
                    && screenY >= loc[1] && screenY <= loc[1] + view.getHeight();

            if (debugButtonLogCount < DEBUG_BUTTON_LOG_MAX) {
                log("  KeyButtonView: " + className + " mCode=" + keyCode
                        + " visible=" + (view.getVisibility() == View.VISIBLE)
                        + " bounds=[" + loc[0] + "," + loc[1] + " " + view.getWidth() + "x" + view.getHeight() + "]"
                        + " tap=[" + screenX + "," + screenY + "]"
                        + " inBounds=" + inBounds);
            }

            if (view.getVisibility() != View.VISIBLE) return false;

            if (inBounds) {
                log("Tap on KeyButtonView mCode=" + keyCode
                        + " (HOME=" + KeyEvent.KEYCODE_HOME + ")");
                return keyCode != KeyEvent.KEYCODE_HOME;
            }
        }

        // Recurse into children
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;

            // Debug: log first few view trees to see what's inside NavigationBarFrame
            if (debugButtonLogCount < DEBUG_BUTTON_LOG_MAX && view == group) {
                boolean isRoot = className.contains("NavigationBarFrame");
                if (isRoot) {
                    debugButtonLogCount++;
                    log("NavBarFrame view tree (" + group.getChildCount() + " children):");
                    logViewTree(group, "  ");
                }
            }

            for (int i = 0; i < group.getChildCount(); i++) {
                if (findNonHomeKeyButton(group.getChildAt(i), screenX, screenY)) {
                    return true;
                }
            }
        }

        return false;
    }

    private void logViewTree(ViewGroup group, String indent) {
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            String name = child.getClass().getName();
            int keyCode = -1;
            if (name.contains("KeyButtonView")) {
                keyCode = getKeyButtonCode(child);
            }
            log(indent + name
                    + " vis=" + (child.getVisibility() == View.VISIBLE)
                    + " size=" + child.getWidth() + "x" + child.getHeight()
                    + (keyCode >= 0 ? " mCode=" + keyCode : ""));
            if (child instanceof ViewGroup) {
                logViewTree((ViewGroup) child, indent + "  ");
            }
        }
    }

    private int getKeyButtonCode(View keyButtonView) {
        try {
            // KeyButtonView stores its keycode in mCode field
            Field codeField = keyButtonView.getClass().getDeclaredField("mCode");
            codeField.setAccessible(true);
            return codeField.getInt(keyButtonView);
        } catch (Throwable t) {
            log("Could not read mCode from " + keyButtonView.getClass().getName()
                    + ": " + t.getMessage());
        }
        return -1;
    }

    // =========================================================================
    // Screen lock methods (shared by both hooks)
    // =========================================================================

    /**
     * Lock the screen using root command. LSPosed requires Magisk (root),
     * so "su" is always available. This works from ANY process — no need
     * to detect SystemUI vs Launcher3.
     */
    private void lockScreen(Context context, View view) {
        log("lockScreen v3 — using root command");
        try {
            Runtime.getRuntime().exec(new String[]{"su", "-c", "input keyevent 223"});
            log("Root sleep command dispatched");
        } catch (Throwable t) {
            log("Root command failed: " + t.getMessage() + " — trying fallbacks");
            // Fallback chain for non-root environments (unlikely with LSPosed)
            if (!tryGoToSleep(context)) {
                try {
                    Runtime.getRuntime().exec(new String[]{"input", "keyevent", "223"});
                    log("Shell fallback dispatched");
                } catch (Throwable t2) {
                    log("All lock methods failed");
                }
            }
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
}
