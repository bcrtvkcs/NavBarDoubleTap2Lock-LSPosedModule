package com.navbardoubletap2lock;

import android.content.Context;
import android.os.PowerManager;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

import java.lang.reflect.Method;

public class MainHook implements IXposedHookLoadPackage {

    private static final String TAG = "NavBarDoubleTap2Lock";

    // Navigation mode constants (matches WindowManagerPolicyConstants)
    private static final int NAV_MODE_3BUTTON = 0;
    private static final int NAV_MODE_GESTURE = 2;

    // Double-tap detection thresholds
    private static final long TAP_MAX_DURATION = 300;
    private static final float TAP_MAX_DISTANCE = 100f;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (!"com.android.systemui".equals(lpparam.packageName)) {
            return;
        }

        XposedBridge.log(TAG + ": Loaded in SystemUI");

        // Find NavigationBarView class - different paths for different Android versions
        Class<?> navBarViewClass = findNavigationBarViewClass(lpparam.classLoader);
        if (navBarViewClass == null) {
            XposedBridge.log(TAG + ": NavigationBarView class not found, aborting");
            return;
        }

        hookNavigationBarTouch(navBarViewClass);
    }

    private Class<?> findNavigationBarViewClass(ClassLoader classLoader) {
        // Android 12+ (S and above)
        String[] classPaths = {
                "com.android.systemui.navigationbar.NavigationBarView",
                // Android 10-11 (Q, R)
                "com.android.systemui.statusbar.phone.NavigationBarView"
        };

        for (String path : classPaths) {
            try {
                Class<?> cls = XposedHelpers.findClass(path, classLoader);
                XposedBridge.log(TAG + ": Found NavigationBarView at " + path);
                return cls;
            } catch (Throwable ignored) {
            }
        }

        return null;
    }

    private void hookNavigationBarTouch(Class<?> navBarViewClass) {
        XposedHelpers.findAndHookMethod(navBarViewClass, "dispatchTouchEvent",
                MotionEvent.class, new XC_MethodHook() {

                    // Per-hook-instance state for double-tap detection
                    private float downX, downY;
                    private long downTime;
                    private long lastTapTime;

                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        View navBarView = (View) param.thisObject;
                        MotionEvent event = (MotionEvent) param.args[0];

                        handleTouchForDoubleTap(navBarView, event);
                    }

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
                                    // Valid tap detected
                                    long now = event.getEventTime();
                                    int doubleTapTimeout = ViewConfiguration.getDoubleTapTimeout();

                                    if (lastTapTime > 0 && (now - lastTapTime) <= doubleTapTimeout) {
                                        // Double tap confirmed
                                        lastTapTime = 0;

                                        Context context = navBarView.getContext();
                                        if (shouldHandleDoubleTap(context, navBarView)) {
                                            XposedBridge.log(TAG + ": Double tap detected - locking screen");
                                            lockScreen(context);
                                        }
                                    } else {
                                        lastTapTime = now;
                                    }
                                } else {
                                    // Swipe or long press - not a tap, reset
                                    lastTapTime = 0;
                                }
                                break;

                            case MotionEvent.ACTION_CANCEL:
                                lastTapTime = 0;
                                break;
                        }
                    }
                });

        XposedBridge.log(TAG + ": dispatchTouchEvent hook installed on NavigationBarView");
    }

    /**
     * Determines whether the double-tap should lock the screen based on the
     * current navigation mode and hint bar visibility.
     *
     * - 3-button navigation: always enabled
     * - Gesture navigation with visible hint bar: enabled
     * - Gesture navigation with hidden hint bar: disabled
     *   (touches pass through to the active app, so the feature would conflict)
     */
    private boolean shouldHandleDoubleTap(Context context, View navBarView) {
        int navMode = getNavigationMode(context);

        if (navMode == NAV_MODE_GESTURE) {
            return isHintBarVisible(navBarView);
        }

        // 3-button or 2-button navigation - always handle
        return true;
    }

    /**
     * Gets the current navigation mode from system resources.
     * Falls back to 3-button mode if the value cannot be determined.
     */
    private int getNavigationMode(Context context) {
        try {
            // Primary: read from framework resource (same method SystemUI uses internally)
            int resId = context.getResources().getIdentifier(
                    "config_navBarInteractionMode", "integer", "android");
            if (resId != 0) {
                return context.getResources().getInteger(resId);
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": Error reading nav mode from resource: " + t.getMessage());
        }

        try {
            // Fallback: Settings.Secure
            return android.provider.Settings.Secure.getInt(
                    context.getContentResolver(), "navigation_mode", NAV_MODE_3BUTTON);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": Error reading nav mode from settings: " + t.getMessage());
        }

        return NAV_MODE_3BUTTON;
    }

    /**
     * Checks whether the gesture navigation hint bar (the thin line at the bottom)
     * is currently visible inside the NavigationBarView.
     *
     * Looks for the view by resource ID "home_handle" first, then falls back
     * to searching by class name "NavigationHandle".
     */
    private boolean isHintBarVisible(View navBarView) {
        try {
            // Try finding by resource ID (most reliable)
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

            // Fallback: search by class name
            if (navBarView instanceof ViewGroup) {
                View handle = findViewByClassName((ViewGroup) navBarView, "NavigationHandle");
                if (handle != null) {
                    return handle.getVisibility() == View.VISIBLE
                            && handle.getAlpha() > 0f;
                }
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": Error checking hint bar visibility: " + t.getMessage());
        }

        // Default: assume visible if we can't determine
        return true;
    }

    /**
     * Recursively searches a ViewGroup for a child view whose simple class name
     * matches the given name.
     */
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

    /**
     * Locks the screen by calling PowerManager.goToSleep().
     * SystemUI has the DEVICE_POWER permission, so this works from its process.
     */
    private void lockScreen(Context context) {
        try {
            PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            if (pm == null) {
                XposedBridge.log(TAG + ": PowerManager is null");
                return;
            }

            try {
                // Android 6+ signature: goToSleep(long time, int reason, int flags)
                Method goToSleep = PowerManager.class.getMethod(
                        "goToSleep", long.class, int.class, int.class);
                goToSleep.invoke(pm, SystemClock.uptimeMillis(), 0, 0);
            } catch (NoSuchMethodException e) {
                // Older fallback: goToSleep(long time)
                Method goToSleep = PowerManager.class.getMethod("goToSleep", long.class);
                goToSleep.invoke(pm, SystemClock.uptimeMillis());
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": Error locking screen: " + t.getMessage());
        }
    }
}
