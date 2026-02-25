package com.navbardoubletap2lock;

import android.content.Context;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.InputEventReceiver;
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

    // Diagnostic counters (new — comprehensive diagnostics)
    private volatile int diagTapCount = 0;
    private static final int DIAG_MAX_TAP_LOG = 20;
    private static final int DIAG_MAX_CLASS_LOG = 30;
    private volatile boolean diagFrameConstructed = false;
    private volatile boolean diagNavBarViewConstructed = false;
    private final Set<String> diagAllDownClasses =
            Collections.synchronizedSet(new LinkedHashSet<>());

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
                    diagNavBarViewConstructed = true;
                    log("DIAG-CTOR: NavigationBarView CONSTRUCTED: "
                            + param.thisObject.getClass().getName());
                    navBarViewRuntimeClass = param.thisObject.getClass();
                    if (param.thisObject instanceof View) {
                        View v = (View) param.thisObject;
                        v.post(() -> {
                            try {
                                int[] loc = new int[2];
                                v.getLocationOnScreen(loc);
                                log("DIAG-CTOR-LAYOUT: NavigationBarView pos=["
                                        + loc[0] + "," + loc[1] + "] size="
                                        + v.getWidth() + "x" + v.getHeight());
                            } catch (Throwable t) {
                                log("DIAG-CTOR-LAYOUT: error " + t.getMessage());
                            }
                        });
                    }
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

        // 4. Diagnostic-only hooks — comprehensive 5-layer touch tracing
        if (DIAGNOSTIC_MODE) {
            hookAlternativeTouchMethods();
            hookWindowLevelTouch(lpparam.classLoader);
            hookInputEventReceiver();

            // Delayed summary report after 10 seconds
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                log("DIAG-SUMMARY: NavigationBarFrame constructed=" + diagFrameConstructed);
                log("DIAG-SUMMARY: NavigationBarView constructed=" + diagNavBarViewConstructed);
                log("DIAG-SUMMARY: Unique dispatch classes seen=" + diagAllDownClasses.size());
                log("DIAG-SUMMARY: Total taps logged=" + diagTapCount);
                log("DIAG-SUMMARY: screenH=" + diagScreenHeight
                        + " navBarH=" + diagNavBarHeight);
            }, 10000);
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

                // DIAGNOSTIC: Hook all constructors to confirm/deny instantiation
                if (DIAGNOSTIC_MODE) {
                    XposedBridge.hookAllConstructors(frameClass, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            diagFrameConstructed = true;
                            View v = (View) param.thisObject;
                            log("DIAG-CTOR: NavigationBarFrame CONSTRUCTED! class="
                                    + param.thisObject.getClass().getName()
                                    + " id=" + Integer.toHexString(v.getId()));
                            v.post(() -> {
                                try {
                                    int[] loc = new int[2];
                                    v.getLocationOnScreen(loc);
                                    log("DIAG-CTOR-LAYOUT: NavigationBarFrame pos=["
                                            + loc[0] + "," + loc[1] + "] size="
                                            + v.getWidth() + "x" + v.getHeight()
                                            + " visibility=" + v.getVisibility()
                                            + " attached=" + v.isAttachedToWindow());
                                } catch (Throwable t) {
                                    log("DIAG-CTOR-LAYOUT: error " + t.getMessage());
                                }
                            });
                        }
                    });
                    log("DIAG: Hooked NavigationBarFrame constructors");
                }

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

    // --- Diagnostic hook methods (comprehensive — 5-layer) ---

    private void hookAlternativeTouchMethods() {
        final Set<String> diagOnTouchClasses =
                Collections.synchronizedSet(new LinkedHashSet<>());
        final Set<String> diagInterceptClasses =
                Collections.synchronizedSet(new LinkedHashSet<>());

        // Hook View.onTouchEvent
        try {
            Method onTouchEvent = View.class.getMethod("onTouchEvent", MotionEvent.class);
            XposedBridge.hookMethod(onTouchEvent, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (!DIAGNOSTIC_MODE) return;
                    View v = (View) param.thisObject;
                    MotionEvent event = (MotionEvent) param.args[0];
                    if (event.getActionMasked() != MotionEvent.ACTION_DOWN) return;
                    if (diagOnTouchClasses.size() >= 30) return;

                    float rawY = event.getRawY();
                    if (rawY >= diagScreenHeight - 200) {
                        String cls = v.getClass().getName();
                        if (diagOnTouchClasses.add(cls)) {
                            int[] loc = new int[2];
                            try { v.getLocationOnScreen(loc); } catch (Throwable ignored) {}
                            log("DIAG-ONTOUCH: " + cls
                                    + " rawY=" + rawY
                                    + " pos=[" + loc[0] + "," + loc[1] + "]"
                                    + " size=" + v.getWidth() + "x" + v.getHeight());
                        }
                    }
                }
            });
            log("DIAG: Hooked View.onTouchEvent");
        } catch (Throwable t) {
            log("DIAG: Failed to hook View.onTouchEvent: " + t.getMessage());
        }

        // Hook ViewGroup.onInterceptTouchEvent
        try {
            Method onInterceptTouchEvent = ViewGroup.class.getMethod(
                    "onInterceptTouchEvent", MotionEvent.class);
            XposedBridge.hookMethod(onInterceptTouchEvent, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (!DIAGNOSTIC_MODE) return;
                    ViewGroup vg = (ViewGroup) param.thisObject;
                    MotionEvent event = (MotionEvent) param.args[0];
                    if (event.getActionMasked() != MotionEvent.ACTION_DOWN) return;
                    if (diagInterceptClasses.size() >= 30) return;

                    float rawY = event.getRawY();
                    if (rawY >= diagScreenHeight - 200) {
                        String cls = vg.getClass().getName();
                        if (diagInterceptClasses.add(cls)) {
                            int[] loc = new int[2];
                            try { vg.getLocationOnScreen(loc); } catch (Throwable ignored) {}
                            log("DIAG-INTERCEPT: " + cls
                                    + " rawY=" + rawY
                                    + " pos=[" + loc[0] + "," + loc[1] + "]"
                                    + " size=" + vg.getWidth() + "x" + vg.getHeight());
                        }
                    }
                }
            });
            log("DIAG: Hooked ViewGroup.onInterceptTouchEvent");
        } catch (Throwable t) {
            log("DIAG: Failed to hook ViewGroup.onInterceptTouchEvent: " + t.getMessage());
        }
    }

    private void hookWindowLevelTouch(ClassLoader classLoader) {
        final Set<String> diagWindowClasses =
                Collections.synchronizedSet(new LinkedHashSet<>());
        final int[] diagWindowTapCount = {0};

        // Hook DecorView.dispatchTouchEvent — entry point for ALL touch events in every window
        try {
            Class<?> decorViewClass = Class.forName(
                    "com.android.internal.policy.DecorView", false, classLoader);
            Method dispatchTouch = decorViewClass.getMethod(
                    "dispatchTouchEvent", MotionEvent.class);

            XposedBridge.hookMethod(dispatchTouch, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (!DIAGNOSTIC_MODE) return;
                    MotionEvent event = (MotionEvent) param.args[0];
                    if (event.getActionMasked() != MotionEvent.ACTION_DOWN) return;

                    View decorView = (View) param.thisObject;
                    float rawY = event.getRawY();

                    if (rawY >= diagScreenHeight - 300 && diagWindowTapCount[0] < 30) {
                        diagWindowTapCount[0]++;
                        int[] loc = new int[2];
                        try { decorView.getLocationOnScreen(loc); } catch (Throwable ignored) {}

                        String windowInfo = "unknown";
                        try {
                            android.view.WindowManager.LayoutParams lp =
                                    (android.view.WindowManager.LayoutParams)
                                    decorView.getLayoutParams();
                            if (lp != null) {
                                windowInfo = "type=" + lp.type
                                        + " title=" + lp.getTitle()
                                        + " flags=0x" + Integer.toHexString(lp.flags);
                            }
                        } catch (Throwable ignored) {}

                        log("DIAG-WINDOW#" + diagWindowTapCount[0]
                                + ": rawX=" + event.getRawX()
                                + " rawY=" + rawY
                                + " decorView=" + decorView.getClass().getName()
                                + " pos=[" + loc[0] + "," + loc[1] + "]"
                                + " size=" + decorView.getWidth() + "x" + decorView.getHeight()
                                + " " + windowInfo);
                    }
                }
            });
            log("DIAG: Hooked DecorView.dispatchTouchEvent");
        } catch (Throwable t) {
            log("DIAG: Failed to hook DecorView: " + t.getMessage());
        }

        // Hook PhoneWindow.superDispatchTouchEvent
        try {
            Class<?> phoneWindowClass = Class.forName(
                    "com.android.internal.policy.PhoneWindow", false, classLoader);
            Method superDispatch = phoneWindowClass.getMethod(
                    "superDispatchTouchEvent", MotionEvent.class);

            XposedBridge.hookMethod(superDispatch, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (!DIAGNOSTIC_MODE) return;
                    MotionEvent event = (MotionEvent) param.args[0];
                    if (event.getActionMasked() != MotionEvent.ACTION_DOWN) return;

                    float rawY = event.getRawY();
                    if (rawY >= diagScreenHeight - 300) {
                        String windowClass = param.thisObject.getClass().getName();
                        if (diagWindowClasses.add(windowClass + "@rawY=" + (int) rawY)) {
                            log("DIAG-PHONEWINDOW: " + windowClass
                                    + " rawY=" + rawY
                                    + " rawX=" + event.getRawX());
                        }
                    }
                }
            });
            log("DIAG: Hooked PhoneWindow.superDispatchTouchEvent");
        } catch (Throwable t) {
            log("DIAG: Failed to hook PhoneWindow: " + t.getMessage());
        }
    }

    private void hookInputEventReceiver() {
        final int[] diagInputCount = {0};

        try {
            Method onInputEvent = InputEventReceiver.class.getDeclaredMethod(
                    "onInputEvent", InputEvent.class);

            XposedBridge.hookMethod(onInputEvent, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (!DIAGNOSTIC_MODE) return;
                    InputEvent inputEvent = (InputEvent) param.args[0];
                    if (!(inputEvent instanceof MotionEvent)) return;

                    MotionEvent event = (MotionEvent) inputEvent;
                    if (event.getActionMasked() != MotionEvent.ACTION_DOWN) return;

                    float rawY = event.getRawY();
                    if (rawY >= diagScreenHeight - 300 && diagInputCount[0] < 20) {
                        diagInputCount[0]++;
                        log("DIAG-INPUT-RECV#" + diagInputCount[0]
                                + ": rawX=" + event.getRawX()
                                + " rawY=" + rawY
                                + " receiver=" + param.thisObject.getClass().getName()
                                + " source=0x" + Integer.toHexString(event.getSource()));
                    }
                }
            });
            log("DIAG: Hooked InputEventReceiver.onInputEvent");
        } catch (Throwable t) {
            log("DIAG: Failed to hook InputEventReceiver: " + t.getMessage());
        }
    }

    // --- Diagnostic helper methods ---

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
        // Log raw coordinates for first N taps (NO position filter)
        if (diagTapCount < DIAG_MAX_TAP_LOG) {
            diagTapCount++;
            initDiagScreenMetrics(vg);
            int[] loc = new int[2];
            try {
                vg.getLocationOnScreen(loc);
            } catch (Throwable ignored) {}
            log("DIAG-TAP#" + diagTapCount + ": rawX=" + event.getRawX()
                    + " rawY=" + event.getRawY()
                    + " class=" + vg.getClass().getName()
                    + " pos=[" + loc[0] + "," + loc[1] + "]"
                    + " size=" + vg.getWidth() + "x" + vg.getHeight());
        }

        // Log unique classes (unfiltered) up to cap
        if (diagAllDownClasses.size() < DIAG_MAX_CLASS_LOG) {
            String cls = vg.getClass().getName();
            if (diagAllDownClasses.add(cls)) {
                initDiagScreenMetrics(vg);
                int[] loc = new int[2];
                try {
                    vg.getLocationOnScreen(loc);
                } catch (Throwable ignored) {}
                log("DIAG-CLASS#" + diagAllDownClasses.size() + ": " + cls
                        + " pos=[" + loc[0] + "," + loc[1] + "]"
                        + " size=" + vg.getWidth() + "x" + vg.getHeight()
                        + " children=" + vg.getChildCount());

                // Parent chain
                StringBuilder sb = new StringBuilder("  PARENTS: ");
                android.view.ViewParent p = vg.getParent();
                for (int i = 0; i < 8 && p instanceof View; i++) {
                    if (i > 0) sb.append(" <- ");
                    sb.append(p.getClass().getName());
                    p = ((View) p).getParent();
                }
                log(sb.toString());
            }
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
