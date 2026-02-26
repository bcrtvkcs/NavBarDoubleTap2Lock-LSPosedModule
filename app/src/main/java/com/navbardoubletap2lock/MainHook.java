package com.navbardoubletap2lock;

import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

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

    private static final String LOCK_ACTION = "com.navbardoubletap2lock.LOCK_SCREEN";

    private static void log(String msg) {
        String fullMsg = TAG + ": " + msg;
        XposedBridge.log(fullMsg);
        Log.d(TAG, msg);
    }

    // Process identity — each process gets its own MainHook instance
    private boolean isSystemUiProcess = false;

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

    // Button exclusion flag — set by KeyButtonView.onTouchEvent hook
    private volatile boolean touchingNonHomeButton = false;

    // Broadcast receiver registration guard
    private boolean receiverRegistered = false;

    @Override
    public void handleLoadPackage(LoadPackageParam lpparam) throws Throwable {
        if ("com.android.systemui".equals(lpparam.packageName)) {
            isSystemUiProcess = true;
            log("Loaded in SystemUI");
            hookNavigationBarFrame(lpparam.classLoader);
            hookKeyButtonView(lpparam.classLoader);
            hookSystemUiApplication(lpparam.classLoader);
            return;
        }

        if ("com.android.launcher3".equals(lpparam.packageName)) {
            log("Loaded in Launcher3");
            hookTaskbarDragLayer(lpparam.classLoader);
            return;
        }
    }

    // =========================================================================
    // SystemUI: Register broadcast receiver for cross-process lock requests
    // =========================================================================

    private void hookSystemUiApplication(ClassLoader classLoader) {
        try {
            XposedBridge.hookMethod(
                    Application.class.getDeclaredMethod("onCreate"),
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (receiverRegistered) return;
                            try {
                                Application app = (Application) param.thisObject;
                                registerLockReceiver(app.getApplicationContext());
                            } catch (Throwable t) {
                                log("Failed to register lock receiver: " + t.getMessage());
                            }
                        }
                    });
            log("Hooked Application.onCreate for broadcast receiver registration");
        } catch (Throwable t) {
            log("Failed to hook Application.onCreate: " + t.getMessage());
        }
    }

    private void registerLockReceiver(Context context) {
        if (context == null || receiverRegistered) return;

        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent intent) {
                log("Lock broadcast received in SystemUI");
                tryGoToSleep(ctx);
            }
        };

        IntentFilter filter = new IntentFilter(LOCK_ACTION);
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            context.registerReceiver(receiver, filter);
        }

        receiverRegistered = true;
        log("Lock broadcast receiver registered in SystemUI");
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
                            lockScreen(view.getContext());
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

    private void hookNavigationBarFrame(ClassLoader classLoader) {
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
                    return;

                } catch (NoSuchMethodException e) {
                    log("NavigationBarFrame found but no dispatchTouchEvent override");
                    return;
                }

            } catch (ClassNotFoundException ignored) {
            }
        }
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

                        // Exclude taps on back/recent buttons
                        if (touchingNonHomeButton) {
                            log("Double tap excluded — on non-home button");
                            break;
                        }

                        lastLockTime = currentTime;
                        log("Double tap detected on NavigationBarFrame — locking screen");
                        lockScreen(navBarView.getContext());
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
    // 3-button nav: KeyButtonView hook for button exclusion
    // =========================================================================

    private void hookKeyButtonView(ClassLoader classLoader) {
        String[] keyButtonPaths = {
                "com.android.systemui.navigationbar.buttons.KeyButtonView", // Android 16+
                "com.android.systemui.statusbar.policy.KeyButtonView",      // older
        };

        for (String path : keyButtonPaths) {
            try {
                Class<?> kbvClass = Class.forName(path, false, classLoader);
                log("Found KeyButtonView at " + path);

                XposedBridge.hookMethod(
                        kbvClass.getMethod("onTouchEvent", MotionEvent.class),
                        new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) {
                                try {
                                    MotionEvent event = (MotionEvent) param.args[0];
                                    if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                                        View btn = (View) param.thisObject;
                                        int mCode = getKeyButtonCode(btn);
                                        touchingNonHomeButton = (mCode != KeyEvent.KEYCODE_HOME && mCode > 0);
                                        log("KeyButtonView touched mCode=" + mCode
                                                + " nonHome=" + touchingNonHomeButton);
                                    }
                                } catch (Throwable t) {
                                    log("Error in KeyButtonView hook: " + t.getMessage());
                                }
                            }
                        });

                log("Hooked KeyButtonView.onTouchEvent");
                return;

            } catch (ClassNotFoundException ignored) {
            } catch (NoSuchMethodException e) {
                log("KeyButtonView found but no onTouchEvent: " + e.getMessage());
            } catch (Throwable t) {
                log("Failed to hook KeyButtonView: " + t.getMessage());
            }
        }

        log("KeyButtonView not found — button exclusion disabled");
    }

    private int getKeyButtonCode(View keyButtonView) {
        try {
            // Walk up the class hierarchy to find mCode field
            Class<?> cls = keyButtonView.getClass();
            while (cls != null && cls != View.class) {
                try {
                    Field codeField = cls.getDeclaredField("mCode");
                    codeField.setAccessible(true);
                    return codeField.getInt(keyButtonView);
                } catch (NoSuchFieldException e) {
                    cls = cls.getSuperclass();
                }
            }
            log("mCode field not found in class hierarchy of "
                    + keyButtonView.getClass().getName());
        } catch (Throwable t) {
            log("Could not read mCode: " + t.getMessage());
        }
        return -1;
    }

    // =========================================================================
    // Screen lock (shared by both hooks)
    // =========================================================================

    private void lockScreen(Context context) {
        if (isSystemUiProcess) {
            // SystemUI has DEVICE_POWER permission — call goToSleep directly
            log("lockScreen — direct goToSleep (SystemUI process)");
            tryGoToSleep(context);
        } else {
            // Launcher3 (or other) — send broadcast to SystemUI
            log("lockScreen — sending broadcast to SystemUI");
            try {
                Intent intent = new Intent(LOCK_ACTION);
                context.sendBroadcast(intent);
                log("Lock broadcast sent from Launcher3");
            } catch (Throwable t) {
                log("Failed to send lock broadcast: " + t.getMessage());
            }
        }
    }

    private boolean tryGoToSleep(Context context) {
        try {
            PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            if (pm == null) {
                log("PowerManager is null");
                return false;
            }

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
