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
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

import java.lang.reflect.Method;

public class MainHook implements IXposedHookLoadPackage {

    private static final String TAG = "NavBarDoubleTap2Lock";

    private static final int NAV_MODE_3BUTTON = 0;
    private static final int NAV_MODE_GESTURE = 2;

    private static final long TAP_MAX_DURATION = 300;
    private static final float TAP_MAX_DISTANCE = 100f;

    private float downX, downY;
    private long downTime;
    private long lastTapTime;

    @Override
    public void handleLoadPackage(LoadPackageParam lpparam) throws Throwable {
        if (!"com.android.systemui".equals(lpparam.packageName)) {
            return;
        }

        XposedBridge.log(TAG + ": Loaded in SystemUI");

        Class<?> navBarViewClass = findNavigationBarViewClass(lpparam.classLoader);
        if (navBarViewClass == null) {
            XposedBridge.log(TAG + ": NavigationBarView class not found, aborting");
            return;
        }

        hookNavigationBarTouch(navBarViewClass);
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
                XposedBridge.log(TAG + ": Found NavigationBarView at " + path);
                return cls;
            } catch (ClassNotFoundException ignored) {
            }
        }

        return null;
    }

    private void hookNavigationBarTouch(Class<?> navBarViewClass) {
        try {
            Method dispatchTouchEvent = navBarViewClass.getMethod(
                    "dispatchTouchEvent", MotionEvent.class);

            XposedBridge.hookMethod(dispatchTouchEvent, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        View navBarView = (View) param.thisObject;
                        MotionEvent event = (MotionEvent) param.args[0];
                        handleTouchForDoubleTap(navBarView, event);
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + ": Error in hook callback: " + t.getMessage());
                    }
                }
            });

            XposedBridge.log(TAG + ": dispatchTouchEvent hook installed on NavigationBarView");
        } catch (NoSuchMethodException e) {
            XposedBridge.log(TAG + ": dispatchTouchEvent method not found: " + e.getMessage());
        }
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
                    long now = event.getEventTime();
                    int doubleTapTimeout = ViewConfiguration.getDoubleTapTimeout();

                    if (lastTapTime > 0 && (now - lastTapTime) <= doubleTapTimeout) {
                        lastTapTime = 0;

                        Context context = navBarView.getContext();
                        if (shouldHandleDoubleTap(context, navBarView, downX, downY)) {
                            XposedBridge.log(TAG + ": Double tap detected - locking screen");
                            lockScreen(context);
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
            XposedBridge.log(TAG + ": Error checking excluded buttons: " + t.getMessage());
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
            XposedBridge.log(TAG + ": Error reading nav mode from resource: " + t.getMessage());
        }

        try {
            return android.provider.Settings.Secure.getInt(
                    context.getContentResolver(), "navigation_mode", NAV_MODE_3BUTTON);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": Error reading nav mode from settings: " + t.getMessage());
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
            XposedBridge.log(TAG + ": Error checking hint bar visibility: " + t.getMessage());
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

    private void lockScreen(Context context) {
        try {
            PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            if (pm == null) {
                XposedBridge.log(TAG + ": PowerManager is null");
                return;
            }

            try {
                Method goToSleep = PowerManager.class.getMethod(
                        "goToSleep", long.class, int.class, int.class);
                goToSleep.invoke(pm, SystemClock.uptimeMillis(), 0, 0);
            } catch (NoSuchMethodException e) {
                Method goToSleep = PowerManager.class.getMethod("goToSleep", long.class);
                goToSleep.invoke(pm, SystemClock.uptimeMillis());
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": Error locking screen: " + t.getMessage());
        }
    }
}
