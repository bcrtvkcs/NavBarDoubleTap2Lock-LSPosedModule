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
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

public class MainHook implements IXposedHookLoadPackage {

    private static final String TAG = "NavBarDoubleTap2Lock";

    private static final int NAV_MODE_3BUTTON = 0;
    private static final int NAV_MODE_GESTURE = 2;

    private static final long TAP_MAX_DURATION = 300;
    private static final float TAP_MAX_DISTANCE = 100f;

    private static final long LOCK_COOLDOWN_MS = 500;

    // Diagnostic mode — set to false for production
    private static final boolean DIAGNOSTIC_MODE = true;

    private static void log(String msg) {
        String fullMsg = TAG + ": " + msg;
        XposedBridge.log(fullMsg);
        Log.d(TAG, msg);
    }

    private float downX, downY;
    private long downTime;
    private long lastTapTime;
    private long lastLockTime;
    private volatile Class<?> navBarViewRuntimeClass = null;

    // Diagnostic fields
    private final Set<String> diagLoggedClasses =
            Collections.synchronizedSet(new LinkedHashSet<>());
    private volatile int diagScreenHeight = -1;
    private volatile int diagNavBarHeight = -1;

    @Override
    public void handleLoadPackage(LoadPackageParam lpparam) throws Throwable {
        if (!"com.android.systemui".equals(lpparam.packageName)) {
            return;
        }

        log("Loaded in SystemUI");

        // 1. NavigationBarView diagnostic — constructor hook to verify instantiation
        Class<?> navBarViewClass = findNavigationBarViewClass(lpparam.classLoader);
        if (navBarViewClass != null) {
            XposedBridge.hookAllConstructors(navBarViewClass, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    log("NavigationBarView constructed: "
                            + param.thisObject.getClass().getName());
                    navBarViewRuntimeClass = param.thisObject.getClass();
                }
            });
        }

        // 2. NavigationBarFrame — primary hook target for Android 16+
        boolean frameHooked = hookNavigationBarFrame(lpparam.classLoader);
        if (frameHooked) {
            log("Using NavigationBarFrame as primary hook target");
        }

        // 3. Fallback: ViewGroup blanket hook (diagnostic + backup)
        if (!frameHooked || DIAGNOSTIC_MODE) {
            hookNavigationBarTouch();
        }
    }

    private Class<?> findNavigationBarViewClass(ClassLoader classLoader) {
        String[] classPaths = {
                "com.android.systemui.navigationbar.views.NavigationBarView", // Android 16+
                "com.android.systemui.navigationbar.NavigationBarView",       // Android 12L-15
                "com.android.systemui.statusbar.phone.NavigationBarView"      // Android ≤ 12
        };

        for (String path : classPaths) {
            try {
                Class<?> cls = Class.forName(path, false, classLoader);
                log("Found NavigationBarView at " + path);
                return cls;
            } catch (ClassNotFoundException ignored) {
            }
        }

        return null;
    }

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
                    // getDeclaredMethod: only finds methods declared in THIS class
                    // If NavigationBarFrame overrides dispatchTouchEvent, this hook
                    // fires ONLY for NavigationBarFrame instances — not all ViewGroups
                    Method declared = frameClass.getDeclaredMethod(
                            "dispatchTouchEvent", MotionEvent.class);

                    XposedBridge.hookMethod(declared, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                View navFrame = (View) param.thisObject;
                                MotionEvent event = (MotionEvent) param.args[0];
                                handleTouchForDoubleTap(navFrame, event);
                            } catch (Throwable t) {
                                log("Error in NavigationBarFrame hook: " + t.getMessage());
                            }
                        }
                    });

                    log("Hooked NavigationBarFrame.dispatchTouchEvent (declared)");
                    navBarViewRuntimeClass = frameClass;
                    return true;

                } catch (NoSuchMethodException e) {
                    // NavigationBarFrame doesn't override dispatchTouchEvent
                    // Set the class so ViewGroup fallback hook can match by identity
                    log("NavigationBarFrame found but no dispatchTouchEvent override");
                    navBarViewRuntimeClass = frameClass;
                    return true;
                }

            } catch (ClassNotFoundException ignored) {
            }
        }

        return false;
    }

    private void hookNavigationBarTouch() {
        try {
            Method dispatchTouchEvent = ViewGroup.class.getMethod(
                    "dispatchTouchEvent", MotionEvent.class);

            XposedBridge.hookMethod(dispatchTouchEvent, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        ViewGroup vg = (ViewGroup) param.thisObject;
                        MotionEvent event = (MotionEvent) param.args[0];

                        // Diagnostic: log ViewGroups in the nav bar screen region
                        if (DIAGNOSTIC_MODE
                                && event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                            diagCheckView(vg, event);
                        }

                        // Production filter
                        if (navBarViewRuntimeClass != null) {
                            if (vg.getClass() != navBarViewRuntimeClass) return;
                        } else {
                            String name = vg.getClass().getSimpleName();
                            if (!name.endsWith("NavigationBarView")
                                    && !name.equals("NavigationBarFrame")) return;
                            navBarViewRuntimeClass = vg.getClass();
                            log("Detected nav bar class: "
                                    + navBarViewRuntimeClass.getName());
                        }

                        handleTouchForDoubleTap((View) vg, event);
                    } catch (Throwable t) {
                        log("Error in hook callback: " + t.getMessage());
                    }
                }
            });

            log("dispatchTouchEvent hook installed");
        } catch (NoSuchMethodException e) {
            log("dispatchTouchEvent method not found: " + e.getMessage());
        }
    }

    // --- Diagnostic methods ---

    private void initDiagScreenMetrics(View view) {
        if (diagScreenHeight > 0) return;
        try {
            Context ctx = view.getContext();
            android.util.DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
            diagScreenHeight = dm.heightPixels;
            int resId = ctx.getResources().getIdentifier(
                    "navigation_bar_height", "dimen", "android");
            diagNavBarHeight = (resId != 0)
                    ? ctx.getResources().getDimensionPixelSize(resId)
                    : (int) (48 * dm.density);
            log("DIAG: screenH=" + diagScreenHeight + " navBarH=" + diagNavBarHeight);
        } catch (Throwable t) {
            diagScreenHeight = 2400;
            diagNavBarHeight = 126;
        }
    }

    private void diagCheckView(ViewGroup vg, MotionEvent event) {
        if (diagLoggedClasses.size() >= 50) return;
        initDiagScreenMetrics(vg);
        try {
            int[] loc = new int[2];
            vg.getLocationOnScreen(loc);
            int viewBottom = loc[1] + vg.getHeight();
            int navRegionTop = diagScreenHeight - diagNavBarHeight - 20;

            // Is this view in the nav bar region of the screen?
            if (viewBottom >= diagScreenHeight - 5 && loc[1] >= navRegionTop) {
                String cls = vg.getClass().getName();
                if (diagLoggedClasses.add(cls)) {
                    log("DIAG-NAV: " + cls
                            + " pos=[" + loc[0] + "," + loc[1] + "]"
                            + " size=" + vg.getWidth() + "x" + vg.getHeight()
                            + " children=" + vg.getChildCount());

                    // Log parent chain (up to 8 levels)
                    StringBuilder sb = new StringBuilder("DIAG-PARENTS: " + cls);
                    android.view.ViewParent p = vg.getParent();
                    for (int i = 0; i < 8 && p instanceof View; i++) {
                        sb.append(" <- ").append(p.getClass().getName());
                        p = ((View) p).getParent();
                    }
                    log(sb.toString());
                }
            }
        } catch (Throwable ignored) {
        }
    }

    // --- Double-tap detection ---

    private void handleTouchForDoubleTap(View navBarView, MotionEvent event) {
        int action = event.getActionMasked();

        switch (action) {
            case MotionEvent.ACTION_DOWN:
                downX = event.getX();
                downY = event.getY();
                downTime = event.getEventTime();
                break;

            case MotionEvent.ACTION_UP:
                float dx = event.getX() - downX;
                float dy = event.getY() - downY;
                long duration = event.getEventTime() - downTime;
                float distance = (float) Math.sqrt(dx * dx + dy * dy);

                if (duration < TAP_MAX_DURATION && distance < TAP_MAX_DISTANCE) {
                    long now = event.getEventTime();
                    int doubleTapTimeout = ViewConfiguration.getDoubleTapTimeout();

                    if (lastTapTime > 0 && (now - lastTapTime) <= doubleTapTimeout) {
                        lastTapTime = 0;

                        long currentTime = SystemClock.uptimeMillis();
                        if (currentTime - lastLockTime < LOCK_COOLDOWN_MS) {
                            break;
                        }

                        Context context = navBarView.getContext();
                        if (shouldHandleDoubleTap(context, navBarView, downX, downY)) {
                            lastLockTime = currentTime;
                            log("Double tap detected - locking screen");
                            navBarView.postDelayed(() -> lockScreen(context, navBarView), 150);
                        }
                    } else {
                        lastTapTime = now;
                    }
                } else {
                    lastTapTime = 0;
                }
                break;

            case MotionEvent.ACTION_CANCEL:
                lastTapTime = 0;
                break;
        }
    }

    private boolean shouldHandleDoubleTap(Context context, View navBarView, float tapX, float tapY) {
        int navMode = getNavigationMode(context);

        if (navMode == NAV_MODE_GESTURE) {
            return isHintBarVisible(navBarView);
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

    private boolean isHintBarVisible(View navBarView) {
        try {
            Context context = navBarView.getContext();
            int handleId = context.getResources().getIdentifier(
                    "home_handle", "id", "com.android.systemui");
            if (handleId != 0) {
                View handleView = navBarView.findViewById(handleId);
                if (handleView != null) {
                    return handleView.getVisibility() == View.VISIBLE
                            && handleView.getAlpha() > 0f;
                }
            }

            if (navBarView instanceof ViewGroup) {
                View handle = findViewByClassName((ViewGroup) navBarView, "NavigationHandle");
                if (handle != null) {
                    return handle.getVisibility() == View.VISIBLE
                            && handle.getAlpha() > 0f;
                }
            }
        } catch (Throwable t) {
            log("Error checking hint bar visibility: " + t.getMessage());
        }

        return true;
    }

    private View findViewByClassName(ViewGroup parent, String className) {
        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            if (child.getClass().getSimpleName().equals(className)) {
                return child;
            }
            if (child instanceof ViewGroup) {
                View found = findViewByClassName((ViewGroup) child, className);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    // --- Screen lock methods ---

    private void lockScreen(Context context, View navBarView) {
        // Primary: InputManager (framework input pipeline — most reliable across OEMs)
        if (!tryInjectSleepKey(context)) {
            // Fallback 1: PowerManager.goToSleep
            if (!tryGoToSleep(context)) {
                // Fallback 2: shell command (last resort)
                tryShellSleepCommand();
            }
        }

        // Deferred diagnostic check — does not affect control flow
        navBarView.postDelayed(() -> {
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
            injectMethod.invoke(inputManager, down, 0); // INJECT_INPUT_EVENT_MODE_ASYNC
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
                // 3-arg version: reason=4 (GO_TO_SLEEP_REASON_POWER_BUTTON), flags=0
                Method goToSleep = PowerManager.class.getMethod(
                        "goToSleep", long.class, int.class, int.class);
                goToSleep.invoke(pm, SystemClock.uptimeMillis(), 4, 0);
            } catch (NoSuchMethodException e) {
                // Older API: single-arg version
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
