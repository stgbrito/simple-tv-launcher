# Simple TV Launcher

A minimalist, tvOS-inspired Android TV launcher featuring a Wallpaper Showcase mode, Floating Glass Dock, optional Continue Watching ("Up Next") shelf, and a full-screen All Apps grid.

## Installation via ADB

1. Enable **Developer Options** and **USB/Network Debugging** on your Android TV.
2. Connect to your TV over ADB:
   ```bash
   adb connect <TV_IP_ADDRESS>:5555
   ```
3. Install the APK:
   ```bash
   adb install -r app-debug.apk
   ```

## Grant Continue Watching Permission

To enable the optional **Continue Watching** shelf (`Launcher Settings > Appearance > Continue Watching Shelf`), grant `READ_TV_LISTINGS` via ADB:

```bash
adb shell pm grant com.appletv.launcher android.permission.READ_TV_LISTINGS
```

## Set as Default Launcher (Disable Stock Launcher)

To make Simple TV Launcher your default home screen, disable the stock Android TV / Google TV launcher for user `0`:

**Android TV (Leanback Launcher):**
```bash
adb shell pm disable-user --user 0 com.google.android.tvlauncher
```

**Google TV:**
```bash
adb shell pm disable-user --user 0 com.google.android.apps.tv.launcherx
```

### Re-enable Stock Launcher

If you ever want to restore the stock launcher:

```bash
# Android TV
adb shell pm enable --user 0 com.google.android.tvlauncher

# Google TV
adb shell pm enable --user 0 com.google.android.apps.tv.launcherx
```
