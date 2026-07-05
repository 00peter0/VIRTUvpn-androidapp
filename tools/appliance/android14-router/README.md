# VirtuVPN Android 14 Router Appliance

This folder contains the root-side appliance files used by dedicated rooted
Android 14 router phones. These files are outside the normal Android APK and
must be installed per router device or packaged into a Magisk module.

## Files

- `virtu-router-watchdog.sh`
  - Magisk `service.d` watchdog.
  - Re-asserts Android router-critical settings.
  - Arms fail-closed hotspot routing before starting tethering.
  - Starts the VirtuVPN router service when the app recorded router mode as
    desired-active.
- `TetherStarter.java`
  - Small framework helper run through `app_process`.
  - Calls Android `TetheringManager.startTethering(WIFI)`, the same framework
    path used by Settings.

## Build Helper Dex

Build on a machine with Android SDK build-tools available:

```sh
javac -source 8 -target 8 \
  -bootclasspath "$ANDROID_HOME/platforms/android-36/android.jar" \
  -d /tmp/virtu-tether-classes \
  tools/appliance/android14-router/TetherStarter.java

mkdir -p /tmp/virtu-tether-dex
"$ANDROID_HOME/build-tools/36.0.0/d8" \
  --min-api 30 \
  --output /tmp/virtu-tether-dex \
  /tmp/virtu-tether-classes/TetherStarter.class

cp /tmp/virtu-tether-dex/classes.dex /tmp/virtu-tether.dex
```

## Install On Router

```sh
adb push tools/appliance/android14-router/virtu-router-watchdog.sh /sdcard/
adb push /tmp/virtu-tether.dex /sdcard/
adb shell su -c 'cp /sdcard/virtu-router-watchdog.sh /data/adb/service.d/virtu-router-watchdog.sh'
adb shell su -c 'cp /sdcard/virtu-tether.dex /data/adb/virtu-tether.dex'
adb shell su -c 'chmod 0755 /data/adb/service.d/virtu-router-watchdog.sh'
adb shell su -c 'chmod 0644 /data/adb/virtu-tether.dex'
```

## Acceptance

After install, reboot the router and verify:

- `sh /data/adb/service.d/virtu-router-watchdog.sh` is running.
- If router mode was enabled before reboot, `ip rule show` contains the hotspot
  block rule `20901` before Samsung tether fallback `21000`.
- `ip route show table 1048` contains `unreachable default`.
- Hotspot appears only after the block rule is armed.
- With an APK that contains `VpnRouterManager.restoreRouterIfDesired()`,
  `VpnRouterService` returns and attestation listeners `8788/8789` become
  available.
