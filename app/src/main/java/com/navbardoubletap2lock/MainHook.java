package com.navbardoubletap2lock;

import android.content.Context;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam;

import java.lang.reflect.Method;

public class MainHook extends XposedModule {

    private static final String TAG = "NavBarDoubleTap2Lock";

    private static final int NAV_MODE_3BUTTON = 0;
    private static final int NAV_MODE_GESTURE = 2;

    private static final long TAP_MAX_DURATION = 300;
    private static final float TAP_MAX_DISTANCE = 100f;

    @Override
    public void onPackageLoaded(PackageLoadedParam param) {
        if (!"com.android.systemui".equals(param.getPackageName())) {
            return;
        }

        log(Log.INFO, TAG, "Loaded in SystemUI", null);

        Class<?> navBarViewClass = findNavigationBarViewClass(param.getClassLoader());
        if (navBarViewClass == null) {
            log(Log.ERROR, TAG, "NavigationBarView class not found, aborting", null);
            return;
        }

        hookNavigationBarTouch(navBarViewClass);
    }

    private Class<?> findNavigationBarViewClass(ClassLoader classLoader) {
        String[] classPaths = {
                "com.android.systemui.navigationbar.NavigationBarView",
                "com.android.systemui.statusbar.phone.NavigationBarView"
        };

        for (String path : classPaths) {
            try {
                Class<?> cls = Class.forName(path, false, classLoader);
                log(Log.INFO, TAG, "Found NavigationBarView at " + path, null);
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

            hook(dispatchTouchEvent, new DispatchTouchHooker());

            log(Log.INFO, TAG, "dispatchTouchEvent hook installed on NavigationBarView", null);
        } catch (NoSuchMethodException e) {
            log(Log.ERROR, TAG, "dispatchTouchEvent method not found", e);
        }
    }

    private class DispatchTouchHooker implements XposedInterface.SimpleHooker<Method> {

        private float downX, downY;
        private long downTime;
        private long lastTapTime;

        @Override
        public void after(XposedInterface.AfterHookCallback<Method> callback) {
            View navBarView = (View) callback.getThisObject();
            MotionEvent event = (MotionEvent) callback.getArgs()[0];
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
                        long now = event.getEventTime();
                        int doubleTapTimeout = ViewConfiguration.getDoubleTapTimeout();

                        if (lastTapTime > 0 && (now - lastTapTime) <= doubleTapTimeout) {
                            lastTapTime = 0;

                            Context context = navBarView.getContext();
                            if (shouldHandleDoubleTap(context, navBarView)) {
                                log(Log.INFO, TAG, "Double tap detected - locking screen", null);
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
    }

    private boolean shouldHandleDoubleTap(Context context, View navBarView) {
        int navMode = getNavigationMode(context);

        if (navMode == NAV_MODE_GESTURE) {
            return isHintBarVisible(navBarView);
        }

        return true;
    }

    private int getNavigationMode(Context context) {
        try {
            int resId = context.getResources().getIdentifier(
                    "config_navBarInteractionMode", "integer", "android");
            if (resId != 0) {
                return context.getResources().getInteger(resId);
            }
        } catch (Throwable t) {
            log(Log.WARN, TAG, "Error reading nav mode from resource: " + t.getMessage(), t);
        }

        try {
            return android.provider.Settings.Secure.getInt(
                    context.getContentResolver(), "navigation_mode", NAV_MODE_3BUTTON);
        } catch (Throwable t) {
            log(Log.WARN, TAG, "Error reading nav mode from settings: " + t.getMessage(), t);
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
            log(Log.WARN, TAG, "Error checking hint bar visibility: " + t.getMessage(), t);
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
                log(Log.ERROR, TAG, "PowerManager is null", null);
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
            log(Log.ERROR, TAG, "Error locking screen: " + t.getMessage(), t);
        }
    }
}
