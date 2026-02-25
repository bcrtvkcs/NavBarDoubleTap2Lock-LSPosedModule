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
import android.view.ViewGroup;

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

    // Touch state for gesture nav (NSWV hook — uses rawX/rawY)
    private float downRawX, downRawY;
    private long downTime;
    private long lastTapTime;
    private long lastLockTime;

    // Touch state for 3-button nav (NavigationBarFrame hook — uses view-local coords)
    private float frameDownX, frameDownY;
    private long frameDownTime;
    private long frameLastTapTime;

    // Screen metrics for nav region filtering
    private volatile int screenHeight = -1;
    private volatile int navRegionThreshold = -1;

    @Override
    public void handleLoadPackage(LoadPackageParam lpparam) throws Throwable {
        if (!"com.android.systemui".equals(lpparam.packageName)) {
            return;
        }

        log("Loaded in SystemUI");

        // 1. Gesture nav: NotificationShadeWindowView hook (primary)
        hookNotificationShadeWindowView(lpparam.classLoader);

        // 2. 3-button nav: NavigationBarFrame hook (fallback)
        hookNavigationBarFrame(lpparam.classLoader);
    }

    // --- NotificationShadeWindowView hook (gesture navigation) ---

    private Class<?> findNotificationShadeWindowViewClass(ClassLoader cl) {
        String[] paths = {
                "com.android.systemui.shade.NotificationShadeWindowView",           // Android 13+
                "com.android.systemui.statusbar.phone.NotificationShadeWindowView", // Android 11-12
        };
        for (String path : paths) {
            try {
                Class<?> cls = Class.forName(path, false, cl);
                log("Found NotificationShadeWindowView at " + path);
                return cls;
            } catch (ClassNotFoundException ignored) {
            }
        }
        log("NotificationShadeWindowView not found");
        return null;
    }

    private void hookNotificationShadeWindowView(ClassLoader classLoader) {
        Class<?> nswvClass = findNotificationShadeWindowViewClass(classLoader);
        if (nswvClass == null) return;

        try {
            Method dispatchTouchEvent = nswvClass.getMethod(
                    "dispatchTouchEvent", MotionEvent.class);

            XposedBridge.hookMethod(dispatchTouchEvent, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        View view = (View) param.thisObject;
                        MotionEvent event = (MotionEvent) param.args[0];
                        handleNavRegionTouch(view, event);
                        // IMPORTANT: never call param.setResult() — event must not be consumed
                    } catch (Throwable t) {
                        log("Error in NSWV hook: " + t.getMessage());
                    }
                }
            });
            log("Hooked NotificationShadeWindowView.dispatchTouchEvent");
        } catch (Throwable t) {
            log("Failed to hook NotificationShadeWindowView: " + t.getMessage());
        }
    }

    private void initScreenMetrics(View view) {
        if (screenHeight > 0) return;
        try {
            Context ctx = view.getContext();
            android.view.WindowManager wm = (android.view.WindowManager)
                    ctx.getSystemService(Context.WINDOW_SERVICE);
            android.graphics.Point realSize = new android.graphics.Point();
            wm.getDefaultDisplay().getRealSize(realSize);
            screenHeight = realSize.y;

            int resId = ctx.getResources().getIdentifier(
                    "navigation_bar_height", "dimen", "android");
            int navBarHeight = (resId != 0)
                    ? ctx.getResources().getDimensionPixelSize(resId)
                    : 84;

            // Nav region = bottom navBarHeight*1.8 pixels (tolerance for imprecise taps)
            navRegionThreshold = screenHeight - (int) (navBarHeight * 1.8f);
            log("Screen metrics: screenH=" + screenHeight
                    + " navBarH=" + navBarHeight
                    + " threshold=" + navRegionThreshold);
        } catch (Throwable t) {
            screenHeight = 3216;
            navRegionThreshold = 3216 - 151;
            log("Screen metrics fallback: " + t.getMessage());
        }
    }

    private void handleNavRegionTouch(View view, MotionEvent event) {
        initScreenMetrics(view);

        int action = event.getActionMasked();

        switch (action) {
            case MotionEvent.ACTION_DOWN:
                if (event.getRawY() < navRegionThreshold) {
                    downTime = 0; // outside nav region — ignore
                    return;
                }
                downRawX = event.getRawX();
                downRawY = event.getRawY();
                downTime = event.getEventTime();
                break;

            case MotionEvent.ACTION_MOVE:
                if (downTime == 0) return;
                float moveDx = event.getRawX() - downRawX;
                float moveDy = event.getRawY() - downRawY;
                if (Math.sqrt(moveDx * moveDx + moveDy * moveDy) > TAP_MAX_DISTANCE) {
                    // Finger moved too far — this is a swipe (home gesture, etc.)
                    downTime = 0;
                    lastTapTime = 0;
                }
                break;

            case MotionEvent.ACTION_UP:
                if (downTime == 0) break;
                long duration = event.getEventTime() - downTime;
                float upDx = event.getRawX() - downRawX;
                float upDy = event.getRawY() - downRawY;
                float dist = (float) Math.sqrt(upDx * upDx + upDy * upDy);

                // UP must also be in nav region (rejects upward swipes)
                if (duration <= TAP_MAX_DURATION
                        && dist <= TAP_MAX_DISTANCE
                        && event.getRawY() >= navRegionThreshold) {

                    long now = event.getEventTime();
                    int doubleTapTimeout = ViewConfiguration.getDoubleTapTimeout();

                    if (lastTapTime > 0 && (now - lastTapTime) <= doubleTapTimeout) {
                        lastTapTime = 0;

                        long currentTime = SystemClock.uptimeMillis();
                        if (currentTime - lastLockTime >= LOCK_COOLDOWN_MS) {
                            lastLockTime = currentTime;
                            log("Double tap in nav region — locking screen");
                            Context context = view.getContext();
                            lockScreen(context, view);
                        }
                    } else {
                        lastTapTime = now;
                    }
                } else {
                    lastTapTime = 0;
                }
                downTime = 0;
                break;

            case MotionEvent.ACTION_CANCEL:
                downTime = 0;
                lastTapTime = 0;
                break;
        }
    }

    // --- NavigationBarFrame hook (3-button navigation fallback) ---

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

    // --- Double-tap detection for NavigationBarFrame (3-button mode) ---

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
            return true; // gesture mode uses NSWV hook with rawY filtering
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

    // --- Screen lock methods ---

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
