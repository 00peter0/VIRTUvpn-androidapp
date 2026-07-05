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
  $(find /tmp/virtu-tether-classes -type f -name '*.class' | sort)

cp /tmp/virtu-tether-dex/classes.dex /tmp/virtu-tether.dex
```

Do not dex only `TetherStarter.class`: the helper also contains generated
inner classes for the executor and tethering callback. A dex missing those
classes fails at runtime with `NoClassDefFoundError`.

## Install On Router

```sh
adb push tools/appliance/android14-router/virtu-router-watchdog.sh /sdcard/
adb push /tmp/virtu-tether.dex /sdcard/
adb shell su -c 'cp /sdcard/virtu-router-watchdog.sh /data/adb/service.d/virtu-router-watchdog.sh'
adb shell su -c 'cp /sdcard/virtu-tether.dex /data/adb/virtu-tether.dex'
adb shell su -c 'chmod 0755 /data/adb/service.d/virtu-router-watchdog.sh'
adb shell su -c 'chmod 0644 /data/adb/virtu-tether.dex'
```

## New Router Commissioning

Use this checklist for every Android 14 router device. Do not skip the reboot
acceptance test; the router must prove that fail-closed protection comes back
without human interaction.

1. Install and verify Magisk/root.
2. Install the current `vcsinstall` VirtuVPN APK.
3. Build `virtu-tether.dex` with all generated `TetherStarter*.class` files.
4. Install `virtu-router-watchdog.sh` into `/data/adb/service.d/`.
5. Install `virtu-tether.dex` into `/data/adb/`.
6. Start the desired upstream VPN provider and enable VPN Router in VirtuVPN.
7. Verify the router status is `On: <tun> routes hotspot traffic via <tether>`.
8. Reboot the phone.
9. Verify the acceptance checks below.

## Provider Restore

Fail-closed protection does not depend on Android always-on VPN. The watchdog
pre-blocks hotspot traffic before tethering starts, so clients cannot fall back
to mobile data while the upstream VPN is still restoring.

Optional provider restore is used only to help third-party VPN providers come
back faster after reboot:

- Virtu/WgQuick tunnels do not use Android `always_on_vpn_app`; they are
  restored by VirtuVPN's own WgQuick boot restore path.
- If VirtuVPN can unambiguously resolve a single third-party VPN provider
  package while enabling router mode, it records that package in
  `secure/virtu_router_always_on_pkg`.
- The watchdog mirrors `secure/virtu_router_always_on_pkg` into
  `secure/always_on_vpn_app` during boot.
- The watchdog never enables always-on VPN lockdown. Lockdown can prevent a
  third-party provider from bootstrapping its own tunnel and is not required for
  router leak protection.
- If provider ownership is unknown or ambiguous, VirtuVPN records nothing and
  the router still remains fail-closed.

## Phase 1 Debloat

For a dedicated router, remove consumer apps that have no router function and
may register boot receivers, push receivers, media/background work, or ad
attribution. Disable them for user 0 instead of deleting system partitions:

```sh
adb shell su -c 'pm disable-user --user 0 com.facebook.katana'
adb shell su -c 'pm disable-user --user 0 com.facebook.appmanager'
adb shell su -c 'pm disable-user --user 0 com.facebook.services'
adb shell su -c 'pm disable-user --user 0 com.facebook.system'
adb shell su -c 'pm disable-user --user 0 com.google.android.apps.photos'
adb shell su -c 'pm disable-user --user 0 com.google.android.apps.youtube.music'
adb shell su -c 'pm disable-user --user 0 com.google.android.videos'
adb shell su -c 'pm disable-user --user 0 com.samsung.android.app.tips'
adb shell su -c 'pm disable-user --user 0 ch.profital.android'
```

After disabling, reboot and run the acceptance checks again. On the A52 router
rig this phase left only the router-relevant third-party apps enabled:

- `com.virtuvpn.android`
- `com.surfshark.vpnclient.android`
- `com.nordvpn.android`
- `com.topjohnwu.magisk`

`ch.profital.android` is a Samsung Store-installed retail/marketing app
(`versionName=48.22.2`) with internet, ad attribution, push messaging,
WorkManager, and `BOOT_COMPLETED`. It is not used by VirtuVPN, Magisk, Nord,
Surfshark, tethering, or SIM provisioning in the observed router state. It was
disabled after verifying Sunrise SIM registration, LTE data, Surfshark, hotspot
routing, fail-closed rules, and attestation remained healthy.

## Phase 2A Debloat

After phase 1 has survived reboot/restore testing, disable low-risk Samsung
assistant/consumer services. These are still disabled, not removed, and should
be watched for several days before considering a deeper image-level cleanup:

```sh
adb shell su -c 'pm disable-user --user 0 com.samsung.android.smartsuggestions'
adb shell su -c 'pm disable-user --user 0 com.samsung.android.game.gos'
adb shell su -c 'pm disable-user --user 0 com.samsung.android.app.routines'
```

- `com.samsung.android.smartsuggestions`: Samsung smart/context suggestions;
  no router function.
- `com.samsung.android.game.gos`: Samsung Game Optimizing Service; no router
  function.
- `com.samsung.android.app.routines`: Samsung Modes and Routines; not needed
  because router automation is handled by VirtuVPN plus the Magisk watchdog.

On the A52 router rig, phase 2A survived reboot and left Surfshark, hotspot,
fail-closed routes, and attestation healthy.

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
- `ip rule show` has one active `20900` and one active `20901` for the active
  tether interface, with no duplicated active rules after router off/on or
  reboot restore.
- If third-party provider restore is used, `settings get secure
  virtu_router_always_on_pkg` and `settings get secure always_on_vpn_app` match
  the provider package, and `settings get secure always_on_vpn_lockdown` remains
  `0` or `null`.
