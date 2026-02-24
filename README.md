# NavBar DoubleTap2Lock

An LSPosed / Xposed module that locks the screen when you double-tap anywhere on the navigation bar.

## How It Works

The module hooks `NavigationBarView.dispatchTouchEvent` inside `com.android.systemui`. It monitors touch events, detects two consecutive quick taps (double tap), and turns off the screen.

### Behavior by Navigation Mode

| Navigation Mode | Condition | Result |
|---|---|---|
| 3-button navigation | - | Always active |
| 2-button navigation | - | Always active |
| Gesture navigation | Hint bar **visible** | Active |
| Gesture navigation | Hint bar **hidden** | Disabled |

In gesture navigation, when the hint bar (the thin line at the bottom) is hidden, touches pass through the navigation bar to the app underneath. The double-tap feature is disabled in this case to avoid conflicts.

## Requirements

- Android 10+ (API 29)
- Root access (Magisk / KernelSU)
- LSPosed or Xposed Framework (with modern libxposed API support)

## Installation

### 1. Build the Project

Open the project in Android Studio and build. The required `libxposed-api-100.jar` (modern Xposed API) is already included in `app/lib/`.

```
Build > Build Bundle(s) / APK(s) > Build APK(s)
```

### 2. Install the APK

Install the compiled APK on your device.

### 3. Enable the Module

1. Open LSPosed Manager
2. Find **NavBar DoubleTap2Lock** in the module list and enable it
3. Make sure **System UI** is selected as the scope
4. Reboot the device

## Project Structure

```
app/src/main/
├── java/com/navbardoubletap2lock/
│   └── MainHook.java                          # All hook and logic code
├── resources/META-INF/xposed/
│   ├── java_init.list                          # Module entry class
│   ├── module.prop                             # API version and scope config
│   └── scope.list                              # Target package (SystemUI)
├── res/values/
│   └── strings.xml                             # Module name and description
└── AndroidManifest.xml                         # App manifest
```

## Technical Details

### API

This module uses the modern [libxposed API](https://github.com/libxposed/api) (v100). The main class extends `XposedModule` and uses `SimpleHooker<Method>` for hooking, replacing the legacy `IXposedHookLoadPackage` / `XC_MethodHook` pattern.

### Double-Tap Detection

- On `ACTION_DOWN`, the finger position and timestamp are recorded
- On `ACTION_UP`, duration (`< 300ms`) and distance (`< 100px`) are checked; if both pass, it counts as a valid tap
- If two consecutive taps occur within `ViewConfiguration.getDoubleTapTimeout()`, a double tap is confirmed
- Swipes and long presses are automatically filtered out

### Navigation Mode Detection

The `config_navBarInteractionMode` value is read from the system resource (the same method SystemUI uses internally). If that fails, it falls back to `Settings.Secure "navigation_mode"`.

- `0` = 3-button navigation
- `1` = 2-button navigation
- `2` = Gesture navigation

### Hint Bar Visibility

In gesture navigation mode, the hint bar visibility inside the navigation bar is checked using two methods:

1. Search for the view by the `home_handle` resource ID
2. If not found, recursively search by the `NavigationHandle` class name

The view must have both `visibility == VISIBLE` and `alpha > 0` to be considered visible.

### Screen Lock

Since the SystemUI process holds the `DEVICE_POWER` permission, the screen is turned off by calling `PowerManager.goToSleep()` via reflection.

## Compatibility

| Android Version | NavigationBarView Path |
|---|---|
| 12+ (S) | `com.android.systemui.navigationbar.NavigationBarView` |
| 10-11 (Q, R) | `com.android.systemui.statusbar.phone.NavigationBarView` |

The module tries both class paths in order and hooks whichever one is found.

## License

GPL-3.0
