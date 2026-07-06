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

## Phase 2B Debloat

After phase 2A has survived reboot/restore testing, disable Samsung background
security/smart service packages that are not part of the router data path. Keep
this as a reversible user-0 disable until the device has passed several days of
router operation:

```sh
adb shell su -c 'pm disable-user --user 0 com.samsung.android.sm.devicesecurity'
adb shell su -c 'pm disable-user --user 0 com.samsung.android.scs'
```

- `com.samsung.android.sm.devicesecurity`: Samsung Device Security scanner.
  The dedicated router is protected by VirtuVPN fail-closed rules, Magisk root
  policy, and controlled appliance configuration; this scanner has no observed
  router function.
- `com.samsung.android.scs`: Samsung Core Services / smart suggestions support
  package. It has no observed dependency for VirtuVPN, Magisk, Surfshark,
  NordVPN, tethering, SIM registration, fail-closed routing, or attestation on
  the A52 router rig.

On the A52 router rig, phase 2B survived reboot and left only the expected
router-relevant third-party apps enabled: VirtuVPN, Surfshark, NordVPN, and
Magisk. Post-reboot validation showed Surfshark restored as the provider,
hotspot `swlan0` was up, VPN `tun1` was up, router rules `20900/20901` were
present before Samsung tether fallback `21000`, table `1048` contained
`unreachable default`, and attestation listeners `8788/8789` were available.

## Phase 2C Debloat

After phase 2B has survived reboot/restore testing, disable remaining Samsung
and Google consumer/service packages that are not required for router
operation. These are still reversible user-0 disables:

```sh
adb shell su -c 'pm disable-user --user 0 com.google.android.adservices.api'
adb shell su -c 'pm disable-user --user 0 com.samsung.android.game.gametools'
adb shell su -c 'pm disable-user --user 0 com.sec.android.app.samsungapps'
adb shell su -c 'pm disable-user --user 0 com.samsung.android.mdx'
adb shell su -c 'pm disable-user --user 0 com.samsung.android.mobileservice'
```

- `com.google.android.adservices.api`: Google ad services API; no router
  function.
- `com.samsung.android.game.gametools`: Samsung Game Tools; no router
  function.
- `com.sec.android.app.samsungapps`: Galaxy Store. App distribution for the
  appliance is handled by the VirtuVPN distribution paths, not by Galaxy Store.
- `com.samsung.android.mdx`: Samsung multi-device experience; no router
  function.
- `com.samsung.android.mobileservice`: Samsung account/mobile services; no
  observed dependency for SIM, tethering, VPN provider restore, fail-closed
  routing, or attestation on the A52 router rig.

On the A52 router rig, phase 2C survived reboot. The first early post-boot
sample already had the fail-closed `20901` hotspot-to-unreachable rule before
Samsung tether fallback `21000`; after the normal stabilization window the full
router state was restored: Surfshark provider, hotspot `swlan0`, VPN `tun1`,
route `20900` to table `1047`, table `1047` default via `tun1`, table `1048`
`unreachable default`, and attestation listeners `8788/8789`. Enabled
third-party apps remained limited to VirtuVPN, Surfshark, NordVPN, and Magisk.

## Phase 2D Debloat

After phase 2C has survived reboot/restore testing, disable Samsung
diagnostics, cloud, and policy update packages that are not needed by the
dedicated router data path. These are still reversible user-0 disables:

```sh
adb shell su -c 'pm disable-user --user 0 com.samsung.android.dqagent'
adb shell su -c 'pm disable-user --user 0 com.samsung.android.scloud'
adb shell su -c 'pm disable-user --user 0 com.samsung.android.scpm'
```

- `com.samsung.android.dqagent`: Samsung diagnostics / quality agent. It has
  no router function.
- `com.samsung.android.scloud`: Samsung Cloud. The appliance does not use
  Samsung Cloud backup or account sync.
- `com.samsung.android.scpm`: Samsung Cloud Platform Manager / policy update
  component. The appliance configuration is controlled by VirtuVPN and the
  Magisk watchdog, not Samsung cloud policy.

Keep `com.samsung.klmsagent` enabled. It is Samsung KLMS Agent / Knox License
Management Service, installed as `/system/priv-app/KLMSAgent/KLMSAgent.apk`.
The appliance does not use Knox licensing, but on the A52 Android 14 build
Samsung protects this package and `pm disable-user` returns `Failed to change
state of package`. Treat it as a known enabled Knox system component unless
runtime logs show that it interferes with router hotspot, VPN, or fail-closed
rules. Do not force-remove or hard-mask it during the standard router
commissioning flow.

On the A52 router rig, phase 2D passed the initial runtime watch and reboot
acceptance. The first early post-boot sample already had the fail-closed
`20901` rule before Samsung tether fallback `21000`; after the normal
stabilization window the full router state was restored: Surfshark provider,
hotspot `swlan0`, VPN `tun1`, route `20900` to table `1047`, table `1047`
default via `tun1`, table `1048` `unreachable default`, and attestation
listeners `8788/8789`.

## Phase 2E Debloat

After phase 2D has survived reboot/restore testing, disable Samsung
customization, beacon, and nearby multi-connectivity packages that are not
required for router operation. These are still reversible user-0 disables:

```sh
adb shell su -c 'pm disable-user --user 0 com.samsung.android.rubin.app'
adb shell su -c 'pm disable-user --user 0 com.samsung.android.beaconmanager'
adb shell su -c 'pm disable-user --user 0 com.samsung.android.mcfserver'
adb shell su -c 'pm disable-user --user 0 com.samsung.android.mcfds'
```

- `com.samsung.android.rubin.app`: Samsung Customization Service. The
  appliance does not use Samsung personalization or recommendation services.
- `com.samsung.android.beaconmanager`: Samsung beacon / nearby detection
  service. The appliance does not use Bluetooth beacon or nearby pairing flows.
- `com.samsung.android.mcfserver`: Samsung Multi Connectivity Framework server.
  The appliance does not use Samsung multi-device continuity or nearby device
  discovery.
- `com.samsung.android.mcfds`: Samsung MCF discovery service. The appliance
  does not use Samsung nearby multi-connectivity discovery.

On the A52 router rig, phase 2E passed the initial runtime watch and reboot
acceptance. The first early post-boot sample already had the fail-closed
`20901` hotspot-to-unreachable rule before Samsung tether fallback `21000`;
after the normal stabilization window the full router state was restored:
Surfshark provider, hotspot `swlan0`, VPN `tun1`, route `20900` to table
`1047`, table `1047` default via `tun1`, table `1048` `unreachable default`,
and attestation listeners `8788/8789`. Enabled third-party apps remained
limited to VirtuVPN, Surfshark, NordVPN, and Magisk.

## Phase 2F Debloat

After phase 2E has survived reboot/restore testing, disable remaining Google
assistant/consumer services and Samsung consumer UI/media services that are not
required for router operation. Keep Play Store, Camera/QR, and Messages enabled
for provider update fallback, QR workflows, and SIM/OTP/operator SMS.

```sh
adb shell su -c 'pm disable-user --user 0 com.google.android.googlequicksearchbox'
adb shell su -c 'pm disable-user --user 0 com.google.android.apps.tachyon'
adb shell su -c 'pm disable-user --user 0 com.google.android.apps.turbo'
adb shell su -c 'pm disable-user --user 0 com.google.android.as'
adb shell su -c 'pm disable-user --user 0 com.google.android.as.oss'
adb shell su -c 'pm disable-user --user 0 com.google.android.projection.gearhead'
adb shell su -c 'pm disable-user --user 0 com.sec.android.easyMover'
adb shell su -c 'pm disable-user --user 0 com.sec.android.easyMover.Agent'
adb shell su -c 'pm disable-user --user 0 com.sec.android.gallery3d'
adb shell su -c 'pm disable-user --user 0 com.samsung.android.app.spage'
adb shell su -c 'pm disable-user --user 0 com.samsung.android.smartmirroring'
adb shell su -c 'pm disable-user --user 0 com.samsung.android.app.smartcapture'
adb shell su -c 'pm disable-user --user 0 com.samsung.android.app.aodservice'
adb shell su -c 'pm disable-user --user 0 com.samsung.android.stickercenter'
adb shell su -c 'pm disable-user --user 0 com.sec.android.app.soundalive'
```

- `com.google.android.googlequicksearchbox`: Google app / Search / Assistant.
- `com.google.android.apps.tachyon`: Google Meet / Duo.
- `com.google.android.apps.turbo`: Android Device Health Services suggestions.
- `com.google.android.as` and `com.google.android.as.oss`: Android System
  Intelligence.
- `com.google.android.projection.gearhead`: Android Auto.
- `com.sec.android.easyMover` and `com.sec.android.easyMover.Agent`: Samsung
  Smart Switch.
- `com.sec.android.gallery3d`: Samsung Gallery.
- `com.samsung.android.app.spage`: Samsung Daily / Bixby page.
- `com.samsung.android.smartmirroring`: Samsung Smart View / screen mirroring.
- `com.samsung.android.app.smartcapture`: Samsung Smart Capture UI.
- `com.samsung.android.app.aodservice`: Samsung Always On Display.
- `com.samsung.android.stickercenter`: Samsung Sticker Center.
- `com.sec.android.app.soundalive`: Samsung SoundAlive effects.

Do not disable these packages in this phase:

- `com.android.vending`: Play Store, kept for VPN provider update fallback.
- `com.sec.android.app.camera`: Camera / QR, kept for QR workflows.
- `com.google.android.apps.messaging`: Messages / SMS / RCS, kept for SIM,
  OTP, and operator messages.

On the A52 router rig, phase 2F passed the initial runtime watch and reboot
acceptance. The first post-boot sample already had Surfshark, hotspot `swlan0`,
VPN `tun1`, router rules `20900/20901`, table `1047` via `tun1`, table `1048`
`unreachable default`, and listener `8788`; after the normal stabilization
window listener `8789` was also available. Play Store, Camera/QR, and Messages
remained enabled. Enabled third-party apps remained limited to VirtuVPN,
Surfshark, NordVPN, and Magisk.

## Phase 2G Debloat

After phase 2F has survived reboot/restore testing, disable remaining Samsung
account, diagnostics, edge UI, transfer, push, and accessibility/audio
packages that are not required for router operation. Keep Play Store,
Camera/QR, Messages, Wi-Fi Guider, Samsung Settings Helper, Samsung SDM config,
and KMX enabled in this phase.

```sh
adb shell su -c 'pm disable-user --user 0 com.samsung.android.mapsagent'
adb shell su -c 'pm disable-user --user 0 com.samsung.android.inputshare'
adb shell su -c 'pm disable-user --user 0 com.sec.hearingadjust'
adb shell su -c 'pm disable-user --user 0 com.samsung.android.samsungpass'
adb shell su -c 'pm disable-user --user 0 com.samsung.android.samsungpassautofill'
adb shell su -c 'pm disable-user --user 0 com.samsung.android.app.appsedge'
adb shell su -c 'pm disable-user --user 0 com.samsung.android.app.taskedge'
adb shell su -c 'pm disable-user --user 0 com.samsung.android.app.clipboardedge'
adb shell su -c 'pm disable-user --user 0 com.samsung.android.app.cocktailbarservice'
adb shell su -c 'pm disable-user --user 0 com.samsung.android.easysetup'
adb shell su -c 'pm disable-user --user 0 com.sec.android.daemonapp'
adb shell su -c 'pm disable-user --user 0 com.sec.android.diagmonagent'
adb shell su -c 'pm disable-user --user 0 com.sec.spp.push'
adb shell su -c 'pm disable-user --user 0 com.osp.app.signin'
```

- `com.samsung.android.mapsagent`: Samsung map/location helper.
- `com.samsung.android.inputshare`: Samsung keyboard/mouse sharing.
- `com.sec.hearingadjust`: Samsung hearing/audio adjustment.
- `com.samsung.android.samsungpass` and
  `com.samsung.android.samsungpassautofill`: Samsung Pass and autofill.
- `com.samsung.android.app.appsedge`, `taskedge`, `clipboardedge`, and
  `cocktailbarservice`: Samsung Edge panel UI services.
- `com.samsung.android.easysetup`: Samsung easy setup / device discovery.
- `com.sec.android.daemonapp`: Samsung weather/daemon app.
- `com.sec.android.diagmonagent`: Samsung diagnostic monitor agent.
- `com.sec.spp.push`: Samsung push service.
- `com.osp.app.signin`: Samsung account sign-in.

On the A52 router rig, phase 2G passed the initial runtime watch and reboot
acceptance. `com.sec.android.diagmonagent` may continue running briefly after
`pm disable-user` because it is a system process, but after reboot
stabilization it was no longer running. The first post-boot sample already had
Surfshark, hotspot `swlan0`, VPN `tun1`, router rules `20900/20901`, table
`1047` via `tun1`, table `1048` `unreachable default`, and listener `8788`;
after the normal stabilization window listener `8789` was also available. Play
Store, Camera/QR, Messages, Wi-Fi Guider, Samsung Settings Helper, Samsung SDM
config, and KMX remained enabled. Enabled third-party apps remained limited to
VirtuVPN, Surfshark, NordVPN, and Magisk.

## Phase 2H Google Debloat - Keep SDK Sandbox Enabled

Do not disable `com.google.android.sdksandbox`. The first Google
package-disable attempt included SDK Sandbox and the A52 router entered Android
Recovery / RescueParty instead of completing a normal boot. The recovery screen
reported that the phone could not start normally and offered only `Try again`,
`Erase app data`, `Power off`, and `View rescue log`.

The rescue log confirmed the boot-critical failure:

```text
FATAL EXCEPTION IN SYSTEM PROCESS: main
java.lang.RuntimeException: There should exactly one sdk sandbox package; found 0; matches=[]
at com.android.server.pm.PackageManagerService.getRequiredSdkSandboxPackageName(...)
```

This confirms that `com.google.android.sdksandbox` is boot-critical on the A52
Android 14 build. Disabling it can prevent `system_server` from starting during
normal boot. Safe mode may still boot with the package disabled, but normal boot
must have exactly one enabled SDK sandbox package.

The device was recovered without flashing by entering Safe mode, authorizing
ADB, and running:

```sh
adb shell pm enable --user 0 com.google.android.sdksandbox
```

`Erase app data` / RescueParty recovery allowed the phone to boot and Magisk
root was still present afterward, but it reset the router appliance state:
VirtuVPN, NordVPN, and Surfshark were no longer installed/visible, router secure
settings were `null`, and router rules were no longer active. Rebuild the
router from the documented commissioning flow before using it again.

Samsung may keep showing a large `Safe mode` watermark after RescueParty even
when Android is otherwise booted normally. Do not rely only on
`persist.sys.safemode` / `ro.boot.safe_mode`: on the A52 this watermark was
caused by Samsung WindowManager state:

```text
SafeModeReason={ persist.sys.emergency_reset[1] }
```

The recovery fix is to clear the Samsung emergency-reset property and reboot:

```sh
adb shell su -c 'setprop persist.sys.emergency_reset 0'
adb reboot
```

Acceptance for this fix:

```sh
adb shell getprop persist.sys.emergency_reset
adb shell dumpsys window extension | grep -i SafeModeReason
adb shell su -c id
```

Expected result: `persist.sys.emergency_reset` is `0`, `SafeModeReason` is not
reported, the `Safe mode` watermark is gone from the launcher screenshot, and
Magisk root still works.

Rules for future Google debloat work:

- Never disable `com.google.android.sdksandbox`.
- Keep SDK Sandbox enabled before reboot acceptance.

With SDK Sandbox kept enabled, the following Google packages were disabled and
the A52 completed a normal reboot with Magisk root still working:

```sh
adb shell su -c 'pm disable-user --user 0 com.google.android.federatedcompute'
adb shell su -c 'pm disable-user --user 0 com.google.android.ondevicepersonalization.services'
adb shell su -c 'pm disable-user --user 0 com.google.mainline.adservices'
adb shell su -c 'pm disable-user --user 0 com.google.mainline.telemetry'
adb shell su -c 'pm disable-user --user 0 com.google.android.feedback'
adb shell su -c 'pm disable-user --user 0 com.google.android.apps.restore'
adb shell su -c 'pm disable-user --user 0 com.google.android.printservice.recommendation'
adb shell su -c 'pm disable-user --user 0 com.google.android.tts'
adb shell su -c 'pm disable-user --user 0 com.google.ar.core'
adb shell su -c 'pm disable-user --user 0 com.google.audio.hearing.visualization.accessibility.scribe'
adb shell su -c 'pm disable-user --user 0 com.google.android.healthconnect.controller'
adb shell su -c 'pm disable-user --user 0 com.google.android.health.connect.backuprestore'
adb shell su -c 'pm disable-user --user 0 com.google.android.partnersetup'
adb shell su -c 'pm disable-user --user 0 com.google.android.onetimeinitializer'
adb shell su -c 'pm disable-user --user 0 com.google.android.setupwizard'
```

Do not disable these Google packages:

- `com.google.android.sdksandbox`: boot-critical SDK Sandbox package.
- `com.android.vending`: Play Store, kept for VPN provider update fallback.
- `com.google.android.apps.messaging`: Messages / SMS / RCS, kept for SIM,
  OTP, and operator messages.
- `com.google.android.gms` and `com.google.android.gsf`: Google core services.
- `com.google.android.webview`: required for WebView-based app/browser flows.
- `com.google.android.configupdater`, `com.google.android.networkstack`,
  `com.google.android.networkstack.tethering`,
  `com.google.android.captiveportallogin`,
  `com.google.android.permissioncontroller`, and
  `com.google.android.packageinstaller`: kept for system networking,
  permissions, install/update, and captive-portal flows.

The Google-without-SDK-Sandbox test passed boot/package acceptance: after reboot
`sys.boot_completed=1`, `su -c id` returned root, SDK Sandbox remained enabled,
and the listed Google packages remained disabled. Because RescueParty reset the
router appliance app state before this retest, full router acceptance for phase
2H must be repeated after VirtuVPN, VPN providers, and router configuration are
reinstalled.

## Phase 2I Debloat - Microsoft and Partner Consumer Apps

After phase 2H survives boot/root acceptance, disable remaining Microsoft and
partner consumer packages that are not part of the router data path. These are
user-0 disables only:

```sh
adb shell su -c 'pm disable-user --user 0 com.microsoft.office.outlook'
adb shell su -c 'pm disable-user --user 0 com.microsoft.office.officehubrow'
adb shell su -c 'pm disable-user --user 0 com.microsoft.skydrive'
adb shell su -c 'pm disable-user --user 0 com.microsoft.appmanager'
adb shell su -c 'pm disable-user --user 0 com.spotify.music'
adb shell su -c 'pm disable-user --user 0 com.linkedin.android'
adb shell su -c 'pm disable-user --user 0 com.netflix.partner.activation'
adb shell su -c 'pm disable-user --user 0 de.axelspringer.yana.zeropage'
```

- Microsoft Outlook, Office, OneDrive, and Link-to-Windows AppManager are not
  used by the appliance.
- Spotify, LinkedIn, Netflix partner activation, and Axel Springer zero page
  are consumer / partner preload apps with no router role.

On the A52 router rig, phase 2I passed reboot acceptance. After reboot:
`sys.boot_completed=1`, `persist.sys.emergency_reset=0`, `su -c id` returned
root, `com.google.android.sdksandbox` remained enabled, no `SafeModeReason` was
reported by `dumpsys window extension`, Microsoft / Spotify / LinkedIn /
Netflix / Axel Springer were not enabled, and the enabled package count was
`377`.

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
