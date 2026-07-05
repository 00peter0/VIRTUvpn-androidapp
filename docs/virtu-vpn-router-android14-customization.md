# VirtuVPN Router Android 14 Customization

This document is the per-device Android 14 customization and root-hardening plan
for rooted Android phones used as dedicated VirtuVPN Router appliances. It is
outside the Android app build and must be applied separately on every router
phone before sale or deployment.

Do not mix this document with other operating systems or router platforms. The
baseline below is from the current Android 14 Samsung router device. Other
Android versions or OEMs need their own customization document.

Goal: no Android/OEM power manager, hotspot manager, root policy, provider app,
or background restriction should silently break router activity. If something
fails, hotspot clients must remain fail-closed.

## Scope

This document covers:

- Android 14 system settings.
- Samsung One UI hotspot/power-management behavior.
- Magisk/root policy for VirtuVPN Router.
- Per-device provider UID allowlist review.
- Future root watchdog outside the app process.
- Acceptance checks before a router is considered sale-ready.

This document does not replace:

- App-level router implementation and tests in `docs/virtu-vpn-router.md`.
- Secured Browser protocol and pairing docs in `docs/virtu-secure-browser.md`.
- Generic Android app security hardening in `docs/android-app-security-hardening.md`.

## Android 14 Samsung Baseline

Observed router device:

- Model: Samsung SM-A525F.
- Android: 14 / SDK 34.
- VirtuVPN build audited: 828 / `1.0.20260704.302-vcsinstall`.
- Root provider: Magisk.
- Router app UID: `10306`.
- Router service: `com.wireguard.android.VpnRouterService`.
- Router attestation listeners:
  - hotspot side: `<router-gateway>:8788`
  - app side: `127.0.0.1:8789`

Known-good Android 14/Samsung state from the audited router:

- VirtuVPN is in Android Doze user whitelist.
- VirtuVPN app ops allow background/router activity:
  - `RUN_IN_BACKGROUND: allow`
  - `RUN_ANY_IN_BACKGROUND: allow`
  - `START_FOREGROUND: allow`
  - `ACTIVATE_VPN: allow`
  - `ESTABLISH_VPN_SERVICE: allow`
  - `SYSTEM_ALERT_WINDOW: allow`
  - `REQUEST_INSTALL_PACKAGES: allow`
- `VpnRouterService` is a foreground service, `START_STICKY`, with active router
  notification.
- Samsung hotspot auto-timeout is disabled:
  - `settings get secure wifi_ap_timeout_setting` -> `0`
- Android tether offload is disabled:
  - `settings get global tether_offload_disabled` -> `1`
- Router fail-closed rules are installed:
  - hotspot VPN rule priority `20900`
  - hotspot unreachable fallback priority `20901`
  - unreachable route table `1048`
  - IPv4/IPv6 reject chains
  - DNS DNAT for hotspot clients

## Current Router Phase 0 Capture

Captured on: 2026-07-05.

Connection path:

- Control host: `vcs-prod-edge-01`.
- Android host: `vcs-llm@10.76.1.2`.
- ADB path on Android host: `/opt/homebrew/bin/adb`.
- Router serial: `RZ8T61J44CA`.

Device identity:

- Model: `SM-A525F`.
- Device: `a52q`.
- Manufacturer: `samsung`.
- Android: `14`.
- SDK: `34`.
- Build: `UP1A.231005.007.A525FXXSCFYF1`.
- Verified boot state: `orange` because the device is unlocked/rooted.

VirtuVPN app:

- Package: `com.virtuvpn.android`.
- Version code: `828`.
- Version name: `1.0.20260704.302-vcsinstall`.
- UID: `10306`.
- Installed/enabled: yes.
- Installer package: `null`, expected for direct router APK installation.

Root:

- `su -c id` returns `uid=0(root) gid=0(root)`.
- SELinux context reported by root shell: `u:r:magisk:s0`.
- Magisk database exists at `/data/adb/magisk.db`.
- Full Magisk policy query was not available with the current on-device shell
  tools; Phase 2 must still verify permanent VirtuVPN root policy from Magisk UI
  or a working SQLite/Magisk query path.

Hotspot and network:

- Hotspot interface: `swlan0`.
- Hotspot gateway: `192.168.115.186/24`.
- WiFi client subnet: `192.168.115.0/24`.
- Active VPN interface: `tun0`.
- Active VPN address: `10.5.0.2/16`.
- Active mobile interfaces observed: `rmnet_data0`, `rmnet_data2`.
- `settings get secure wifi_ap_timeout_setting` -> `0`.
- `settings get global tether_offload_disabled` -> `1`.
- `settings get global mobile_data` -> `1`.
- `settings get global airplane_mode_on` -> `0`.

Router runtime:

- `VpnRouterService` is running as foreground service.
- `isForeground=true`.
- `startRequested=true`.
- `startCommandResult=1`.
- Router process observed as `com.virtuvpn.android/u0a306`.
- Attestation listeners are active:
  - `192.168.115.186:8788`
  - `127.0.0.1:8789`

Android power/app policy:

- VirtuVPN is present in Doze user whitelist.
- `am get-standby-bucket com.virtuvpn.android` -> `5`.
- Required appops observed as allowed:
  - `SYSTEM_ALERT_WINDOW`
  - `ACTIVATE_VPN`
  - `RUN_IN_BACKGROUND`
  - `REQUEST_INSTALL_PACKAGES`
  - `RUN_ANY_IN_BACKGROUND`
  - `START_FOREGROUND`
  - `ESTABLISH_VPN_SERVICE`

Provider packages observed:

- `com.nordvpn.android` -> UID `10309`.
- `com.surfshark.vpnclient.android` -> UID `10310`.
- `com.virtuvpn.android` -> UID `10306`.
- Android VPN system packages also present:
  - `com.android.vpndialogs` -> UID `10137`
  - `com.knox.vpn.proxyhandler` -> UID `1002`

Router fail-closed state:

- Hotspot routing rules are present:
  - `20900: from all iif swlan0 lookup 1047`
  - `20901: from all iif swlan0 lookup 1048`
- Table `1048` contains `unreachable default`.
- IPv4 router chains are present:
  - `VIRTUVPN_ROUTER_FWD`
  - `VIRTUVPN_ROUTER_OUT`
  - `VIRTUVPN_ROUTER_DNS`
- IPv6 router chains are present:
  - `VIRTUVPN_ROUTER6_FWD`
  - `VIRTUVPN_ROUTER6_OUT`
- DNS DNAT currently points hotspot client DNS to Quad9:
  - UDP/TCP `53` -> `9.9.9.9`.
- Phone OUTPUT lockdown allows loopback, `tun0`, WireGuard transport mark,
  selected provider/system UIDs, local hotspot subnet, and then rejects
  physical uplinks `rmnet_data0` and `rmnet_data2`.

Phase 0 status:

- Device identity captured: pass.
- Root available: pass.
- VirtuVPN installed and running: pass.
- Hotspot active: pass.
- VPN tunnel active: pass.
- Router fail-closed invariant present: pass.
- Follow-up for Phase 2: verify Magisk policy details through reliable
  SQLite/Magisk tooling or Magisk UI.
- Follow-up for Phase 5: validate UID allowlist after every provider install,
  update, or removal.

## Current Router Phase 1 Capture

Captured on: 2026-07-05 after applying the Android 14 background policy to
router serial `RZ8T61J44CA`.

Actions applied:

- Re-added VirtuVPN to Android Doze whitelist:
  - `cmd deviceidle whitelist +com.virtuvpn.android`
- Re-applied required appops as `allow`:
  - `RUN_IN_BACKGROUND`
  - `RUN_ANY_IN_BACKGROUND`
  - `START_FOREGROUND`
  - `ACTIVATE_VPN`
  - `ESTABLISH_VPN_SERVICE`
  - `SYSTEM_ALERT_WINDOW`
  - `REQUEST_INSTALL_PACKAGES`
- Re-applied standby bucket target:
  - `am set-standby-bucket com.virtuvpn.android active`

Observed result:

- Doze whitelist still contains:
  - `user,com.virtuvpn.android,10306`
- App standby bucket reports:
  - `5`
- Required appops report `allow`.
- `VpnRouterService` remained active:
  - `isForeground=true`
  - `startRequested=true`
  - `startCommandResult=1`

Phase 1 status:

- Android Doze policy: pass.
- Appops policy: pass.
- Foreground router service after applying policy: pass.
- Manual Samsung checks still required on the physical device:
  - VirtuVPN is not in Sleeping apps.
  - VirtuVPN is not in Deep sleeping apps.
  - Approved provider apps are not in Sleeping/Deep sleeping apps.
  - Device Care/Battery does not classify VirtuVPN as restricted.

## Current Router Phase 2 Capture

Captured on: 2026-07-05 after applying Magisk/root policy to router serial
`RZ8T61J44CA`.

Discovery:

- Magisk binary: `/product/bin/magisk`.
- Magisk version: `30.7`, version code `30700`.
- Magisk database: `/data/adb/magisk.db`.
- VirtuVPN UID: `10306`.
- Required root tools are available from the root shell:
  - `/system/bin/iptables`
  - `/system/bin/ip6tables`
  - `/system/bin/ip`
  - `/system/bin/settings`
  - `/system/bin/ndc`
  - `/system/bin/ss`
  - `/system/bin/toybox`

Actions applied:

- Set VirtuVPN Magisk policy to permanent allow:
  - `policy=2`
  - `until=0`
- Re-enabled Magisk audit visibility for VirtuVPN:
  - `logging=1`
  - `notification=1`

Observed result:

- `magisk --sqlite` reports:
  - `uid=10306|policy=2|until=0|logging=1|notification=1`
- `su -c id` returns:
  - `uid=0(root) gid=0(root) groups=0(root) context=u:r:magisk:s0`
- Root smoke check can read router chains.
- Attestation listeners remained active:
  - `192.168.115.186:8788`
  - `127.0.0.1:8789`
- `VpnRouterService` remained active:
  - `isForeground=true`
  - `startRequested=true`
  - `startCommandResult=1`

Phase 2 status:

- Permanent VirtuVPN root access: pass.
- Root tooling required by router mode: pass.
- Root audit visibility: pass.
- Router service after policy change: pass.

## Current Router Phase 3 Capture

Captured on: 2026-07-05 after applying Samsung hotspot/tethering customization
to router serial `RZ8T61J44CA`.

Actions applied:

- Keep Samsung hotspot idle timeout disabled:
  - `settings put secure wifi_ap_timeout_setting 0`
- Keep Android tether offload disabled:
  - `settings put global tether_offload_disabled 1`
- Keep mobile data enabled for router uplink:
  - `settings put global mobile_data 1`
- Keep airplane mode disabled:
  - `settings put global airplane_mode_on 0`
- Previous experimental Settings UI locks were rolled back:
  - `cmd statusbar send-disable-flag none`
  - hotspot/WiFi/mobile-data Settings activities returned to default component
    state with root `pm default-state`

Observed result:

- `settings get secure wifi_ap_timeout_setting` -> `0`.
- `settings get global tether_offload_disabled` -> `1`.
- `settings get global mobile_data` -> `1`.
- `settings get global airplane_mode_on` -> `0`.
- No router-hostile Settings components remain in the explicit disabled list.
- `VpnRouterService` remained active:
  - `isForeground=true`
  - `startRequested=true`
  - `startCommandResult=1`
- Attestation listeners remained active:
  - `192.168.115.186:8788`
  - `127.0.0.1:8789`
- Hotspot interface remained active:
  - `swlan0`
  - `192.168.115.186/24`

Policy decision:

- Do not protect the router by hiding or disabling Settings UI. It is brittle,
  hard to support, and can break normal service workflows.
- Protect the router by enforcing the required Android state with a root
  watchdog. If a user or Samsung UI changes a router-critical setting, the
  watchdog restores the required value.

Phase 3 status:

- Hotspot timeout disabled: pass.
- Tether offload disabled: pass.
- Settings UI locks: rolled back.
- Router service after rollback: pass.
- Follow-up for Phase 6: keep the root watchdog active so this phase is
  re-applied after reboot or manual tampering.

## Current Router Phase 6 Capture

Captured on: 2026-07-05 after installing the root watchdog on router serial
`RZ8T61J44CA`.

Installed watchdog:

- Path: `/data/adb/service.d/virtu-router-watchdog.sh`.
- Owner/runtime: root via Magisk `service.d`.
- Current runtime process:
  - `sh /data/adb/service.d/virtu-router-watchdog.sh`
- Interval: 30 seconds.
- Log path:
  - `/data/local/tmp/virtu-router-watchdog.log`
- PID path:
  - `/data/local/tmp/virtu-router-watchdog.pid`

The watchdog does not disable Settings UI. It only re-asserts required router
state:

- `secure/wifi_ap_timeout_setting=0`
- `global/tether_offload_disabled=1`
- `global/mobile_data=1`
- `global/airplane_mode_on=0`
- VirtuVPN in Doze whitelist
- VirtuVPN appops:
  - `RUN_IN_BACKGROUND=allow`
  - `RUN_ANY_IN_BACKGROUND=allow`
  - `START_FOREGROUND=allow`
  - `ACTIVATE_VPN=allow`
  - `ESTABLISH_VPN_SERVICE=allow`
  - `SYSTEM_ALERT_WINDOW=allow`
  - `REQUEST_INSTALL_PACKAGES=allow`
- VirtuVPN Magisk root policy:
  - `policy=2`
  - `until=0`
  - `logging=1`
  - `notification=1`

Validation:

- The watchdog script is executable:
  - `-rwx------ /data/adb/service.d/virtu-router-watchdog.sh`
- Exactly one watchdog process is running.
- Perturbation test:
  - set `secure/wifi_ap_timeout_setting` to `600`
  - after the watchdog interval, it returned to `0`
- `VpnRouterService` remained active during the watchdog test.
- Attestation listeners remained active during the watchdog test:
  - `192.168.115.186:8788`
  - `127.0.0.1:8789`

Phase 6 status:

- Root watchdog installed: pass.
- Watchdog starts through Magisk boot path: pass.
- Watchdog running now: pass.
- Critical setting self-heal: pass.
- UI lock replacement strategy: pass.
- Reboot validation: pass for OS/root hardening, incomplete for automatic router
  runtime restore.

Reboot validation captured on 2026-07-05:

- Android returned after reboot:
  - `sys.boot_completed=1`
  - Android `14`, SDK `34`
  - verified boot state `orange`
- Watchdog restarted automatically from Magisk `service.d`:
  - process observed as
    `busybox sh /data/adb/service.d/virtu-router-watchdog.sh`
  - PID file contained the running watchdog PID
- Required OS state after reboot:
  - `settings get secure wifi_ap_timeout_setting` -> `0`
  - `settings get global tether_offload_disabled` -> `1`
  - `settings get global mobile_data` -> `1`
  - `settings get global airplane_mode_on` -> `0`
- VirtuVPN remained in Doze whitelist:
  - `user,com.virtuvpn.android,10306`
- Required appops remained `allow`.
- Magisk policy remained:
  - `uid=10306|policy=2|until=0|logging=1|notification=1`

Boot-time watchdog note:

- The watchdog can log warning lines during the first seconds of boot because
  Android settings/appops services are not fully ready yet.
- This is acceptable only if later checks show the final required values above.
  Treat persistent warnings with wrong final values as a failed Phase 6
  validation.

Runtime restore gap found during reboot validation:

- After a cold reboot, the OS/root hardening persisted, but the router runtime
  did not automatically return by itself:
  - no `VpnRouterService` instance observed
  - no `swlan0` hotspot interface observed
  - no `tun0` VPN interface observed
  - no `8788` / `8789` attestation listeners observed
- This means Phase 6 currently protects Android settings and root policy, but
  does not by itself make a powered-off/rebooted router appliance ready for
  clients.
- Before sale-ready status, add or validate a boot restore path that starts the
  VirtuVPN router runtime only when the router had previously been enabled, and
  keeps fail-closed behavior if VPN/provider restore fails.

## Current Router Phase 4 Capture

Captured on: 2026-07-05 after Phase 6 watchdog installation on router serial
`RZ8T61J44CA`.

Runtime service:

- `VpnRouterService` is running.
- `isForeground=true`.
- `startRequested=true`.
- `startCommandResult=1`.

Interfaces and listeners:

- Hotspot interface:
  - `swlan0`
  - `192.168.115.186/24`
- VPN interface:
  - `tun0`
  - `10.5.0.2/16`
- Attestation listeners:
  - `192.168.115.186:8788`
  - `127.0.0.1:8789`

Routing invariant:

- Hotspot VPN route:
  - `20900: from all iif swlan0 lookup 1047`
  - table `1047`: `default dev tun0 scope link`
- Hotspot fail-closed route:
  - `20901: from all iif swlan0 lookup 1048`
  - table `1048`: `unreachable default`
- Android tether fallback remains lower priority:
  - `21000: from all iif swlan0 lookup rmnet_data0`

Firewall invariant:

- `VIRTUVPN_ROUTER_FWD` exists.
- `VIRTUVPN_ROUTER_OUT` exists.
- `VIRTUVPN_ROUTER6_FWD` exists.
- `VIRTUVPN_ROUTER6_OUT` exists.
- `VIRTUVPN_ROUTER_DNS` exists.
- Hotspot client forwarding allows `swlan0 -> tun0` and rejects downstream
  fallback.
- Router phone output allows loopback, `tun0`, WireGuard mark, selected
  provider/system UIDs, local hotspot subnet, then rejects physical uplinks
  `rmnet_data0` and `rmnet_data2`.
- IPv6 client forwarding is rejected.
- DNS DNAT sends hotspot TCP/UDP `53` to `9.9.9.9`.

Watchdog:

- Exactly one watchdog process is running:
  - `sh /data/adb/service.d/virtu-router-watchdog.sh`
- Critical settings remain:
  - `secure/wifi_ap_timeout_setting=0`
  - `global/tether_offload_disabled=1`
  - `global/mobile_data=1`
  - `global/airplane_mode_on=0`

Attestation validation:

- Router-local app-side test against `127.0.0.1:8789` returned `HTTP 200`.
- Hotspot client test from client serial `RFCX703NXYP` to
  `192.168.115.186:8788` returned `HTTP 200`.
- Signed attestation fields:
  - `kind=vpn-router-attestation`
  - `version=2`
  - `protected=true`
  - `availability=ENABLED`
  - `tunnelOnline=true`
  - `tunnel=tun0`
- Note: attestation nonce must match the app's nonce policy
  (`^[A-Za-z0-9_-]{24,96}$`). Short manual test nonces correctly return
  `503 Unavailable` because no signed response is generated.

Phase 4 status:

- Foreground router service: pass.
- Hotspot listener: pass.
- App-side listener: pass.
- Routing fail-closed invariant: pass.
- Firewall fail-closed invariant: pass.
- DNS DNAT invariant: pass.
- Client attestation: pass.
- Watchdog does not interfere with router runtime: pass.

## Current Router Phase 5 Capture

Captured on: 2026-07-05 on router serial `RZ8T61J44CA`.

Current OUTPUT UID allowlist in both IPv4 and IPv6 router phone lockdown chains:

- `10309`
- `10196`
- `10306`
- `10310`
- `1000`
- `1051`
- `1052`
- `1073`

Mapped packages/processes:

- `10306`:
  - `com.virtuvpn.android`
  - required for VirtuVPN router service and in-app provider flow
- `10309`:
  - `com.nordvpn.android`
  - installed provider bootstrap / active provider candidate
- `10310`:
  - `com.surfshark.vpnclient.android`
  - installed provider bootstrap / active provider candidate
- `10196`:
  - `com.samsung.android.fast`
  - Samsung Secure Wi-Fi/Fast package
  - declares `android.permission.BIND_VPN_SERVICE` through
    `.vpn.logic.CharonVpnService`
  - keep for now because it is a Samsung VPN-capable system app; remove only
    after provider-switch tests prove it is not needed on this ROM
- `1000`:
  - Android/Samsung system UID
  - required by Android VPN/connectivity orchestration on this build
- `1051`:
  - documented in app code as DNS resolver bootstrap UID on Android builds that
    use `AID_DNS`
- `1052`:
  - documented in app code as tether/system DNS helper UID on Samsung builds
- `1073`:
  - `com.google.android.networkstack`
  - `com.google.android.networkstack.tethering`
  - `com.google.android.cellbroadcastservice`
  - required for NetworkStack/Tethering validation and bootstrap plumbing

Implementation alignment:

- App code intentionally writes:
  - active VPN provider UID
  - installed VPN provider bootstrap UIDs
  - Android VPN bootstrap system UIDs `1000`, `1051`, `1052`, `1073`
- The current allowlist therefore matches the app model. It is broader than a
  single-active-provider allowlist by design, because router mode must be able
  to switch providers and create a new tunnel while the router phone OUTPUT
  chain is fail-closed.

Phase 5 decision:

- Do not remove any UID in this pass.
- Treat `10196` as a review item, not a removal item, because it maps to a
  Samsung VPN-capable system package.
- Revisit only after testing all intended provider switches:
  - VirtuVPN tunnel
  - NordVPN
  - Surfshark
  - any future provider intended for sale

Phase 5 status:

- UID mapping completed: pass.
- NordVPN UID present: pass.
- Surfshark UID present: pass.
- VirtuVPN UID present: pass.
- Android NetworkStack/Tethering UID present: pass.
- Bootstrap/system UID rationale documented: pass.
- Cleanup action: none now; retest required before any narrowing.

## Implementation Phases

### Phase 0 - Device Baseline Capture

Purpose: identify exactly what the device is before applying router appliance
customization.

Capture and store:

- Android version and SDK:
  - `getprop ro.build.version.release`
  - `getprop ro.build.version.sdk`
- Device model:
  - `getprop ro.product.model`
- VirtuVPN version:
  - `dumpsys package com.virtuvpn.android | grep -E 'versionCode|versionName'`
- Root status:
  - `su -c id`
- Hotspot interface/gateway after hotspot starts:
  - `ip -4 addr`
  - `ip route`
- Active provider apps and package UIDs:
  - `cmd package list packages -U`

Exit criteria:

- Device is rooted and `su -c id` returns UID `0`.
- VirtuVPN is installed and launchable.
- Hotspot can be enabled manually.
- At least one intended VPN provider can establish a tunnel.

### Phase 1 - Android 14 Power And Background Customization

Purpose: prevent Android 14 and Samsung power management from stopping router
activity.

Required state:

- VirtuVPN is whitelisted from Doze:
  - `dumpsys deviceidle whitelist | grep com.virtuvpn.android`
- VirtuVPN standby bucket is not restricted:
  - `am get-standby-bucket com.virtuvpn.android`
- App ops allow router activity:
  - `RUN_IN_BACKGROUND: allow`
  - `RUN_ANY_IN_BACKGROUND: allow`
  - `START_FOREGROUND: allow`
  - `ACTIVATE_VPN: allow`
  - `ESTABLISH_VPN_SERVICE: allow`

Samsung manual checks:

- VirtuVPN must not be in Sleeping apps.
- VirtuVPN must not be in Deep sleeping apps.
- Approved provider apps used by router mode must not be Sleeping/Deep sleeping.
- Device Care or Battery settings must not classify VirtuVPN as restricted.

Exit criteria:

- `VpnRouterService` can run as foreground service with screen on and after a
  screen-off idle period.
- Router notification remains active.
- No logcat evidence of Samsung/Android killing VirtuVPN during normal router
  operation.

### Phase 2 - Magisk And Root Policy

Purpose: make root access deterministic for router operations.

Required state:

- VirtuVPN has permanent Magisk root access.
- Root access does not show prompts while router mode is running.
- Root shell can run:
  - `iptables`
  - `ip6tables`
  - `ip`
  - `settings`
  - `ndc`
  - `ss`
  - `toybox nc`
- Root logging remains available for audit. Do not silently disable Magisk
  logging or notifications as a production default.

Exit criteria:

- Router can enable, reconcile, and disable without manual Magisk prompt.
- Root remains available after reboot and after APK update.
- Magisk policy can be inspected during support/audit.

### Phase 3 - Samsung Hotspot And Tethering Customization

Purpose: prevent Samsung hotspot logic from stopping or rewriting router behavior
in a way that breaks appliance use.

Required state:

- Hotspot idle timeout disabled:
  - `settings get secure wifi_ap_timeout_setting` -> `0`
- Tether offload disabled unless specifically validated on this device/provider:
  - `settings get global tether_offload_disabled` -> `1`
- Record hotspot details:
  - interface name, commonly `swlan0`
  - gateway address, observed `192.168.115.186`
  - DHCP subnet
  - whether WiFi sharing is available

Validation:

- Hotspot remains active beyond the old OEM idle timeout.
- `dumpsys wifi` / logcat does not show unexpected hotspot stop events.
- Hotspot clients can reconnect and keep the same fail-closed router posture.

Exit criteria:

- Hotspot stays enabled while VPN Router is ON.
- If hotspot is manually disabled or crashes, clients lose internet instead of
  leaking through mobile uplink.

### Phase 4 - Router Runtime Verification

Purpose: verify that app-level router protections are active on this Android 14
customized device.

Foreground service checks:

- `dumpsys activity services com.virtuvpn.android`
- Expected:
  - `VpnRouterService`
  - `isForeground=true`
  - `startRequested=true`
  - `startCommandResult=1`

Listener checks:

- `ss -ltn | grep -E '8788|8789'`
- Expected:
  - `<router-gateway>:8788`
  - `127.0.0.1:8789`

Router firewall invariant:

- `ip rule show | grep 20900`
- `ip rule show | grep 20901`
- `ip route show table 1048`
- `iptables -S VIRTUVPN_ROUTER_FWD`
- `iptables -S VIRTUVPN_ROUTER_OUT`
- `ip6tables -S VIRTUVPN_ROUTER6_FWD`
- `ip6tables -S VIRTUVPN_ROUTER6_OUT`
- `iptables -t nat -S VIRTUVPN_ROUTER_DNS`

Expected:

- hotspot VPN route exists before Android tether fallback,
- hotspot unreachable fallback exists,
- table `1048` contains `unreachable default`,
- IPv4/IPv6 reject chains are present,
- DNS DNAT points to selected router resolver,
- router phone physical uplink output is rejected outside VPN/provider transport.

Exit criteria:

- Attestation from a hotspot client returns signed JSON.
- `protected=true` when fail-closed rules hold.
- `tunnelOnline` reflects tunnel quality separately from `protected`.

### Phase 5 - Provider UID Allowlist Review

Purpose: prevent provider apps from being blocked while avoiding unnecessary
router-phone egress exceptions.

Observed UIDs on audited Android 14 Samsung router:

- VirtuVPN: `10306`
- NordVPN: `10309`
- Surfshark: `10310`
- Android NetworkStack/Tethering: `1073`
- Android system: `1000`
- Samsung Fast / nearby service: `10196` - review before shipping

Per-device action:

1. Map every UID present in `VIRTUVPN_ROUTER_OUT`:
   - `cmd package list packages -U | grep 'uid:<uid>'`
2. Keep only UIDs required for:
   - active VPN provider transport,
   - VirtuVPN app/router service,
   - Android system/tether/network stack.
3. Review OEM convenience services and remove them unless proven required.
4. Re-test each approved provider after cleanup:
   - VirtuVPN tunnel,
   - NordVPN,
   - Surfshark,
   - any other provider intended for sale.

Exit criteria:

- Provider switching works.
- Router phone ordinary egress remains blocked outside VPN/provider transport.
- Hotspot clients remain fail-closed during failed provider switches.

### Phase 6 - Root Watchdog Design

Purpose: add an appliance-grade safety net outside the normal Android app
process. This is future hardening and should be implemented after the current
Android 14 baseline is stable.

Watchdog responsibilities:

- Check VirtuVPN process exists.
- Check `VpnRouterService` foreground service exists when router is expected ON.
- Check `<gateway>:8788` and `127.0.0.1:8789` listeners.
- Check hotspot interface exists.
- Check `20901` unreachable fallback and table `1048` exist.
- If app process dies while rules remain active, restart VirtuVPN/router
  service.
- If router rules disappear unexpectedly, install an emergency fail-closed block
  or disable hotspot until VirtuVPN restores normal rules.

Important rule:

- Do not use the watchdog to turn router mode on from a clean OFF state unless
  this specific sold appliance profile explicitly says router must always be on.

Exit criteria for future implementation:

- Watchdog survives app process death.
- Watchdog does not fight explicit user/router OFF.
- Watchdog does not weaken fail-closed firewall posture.

### Phase 7 - Sale/Deployment Acceptance

A router device is ready for sale/deployment only when it passes all checks
below on the actual device being sold.

Required survival tests:

- APK update while router is ON.
- Provider tunnel blip.
- Provider switch to healthy tunnel.
- Provider switch to failed tunnel.
- Hotspot client reconnect.
- Screen-off idle period.
- Reboot and configured restore procedure, if that appliance profile requires
  automatic restore.

Required leak tests from hotspot clients:

- VPN egress only.
- No mobile-provider DNS.
- No client IPv6 uplink leak.
- VirtuVPN Secured Browser verifies router pairing and stays fail-closed when
  router cannot be verified.

Required documentation per device:

- Android version and build.
- Root/Magisk version and policy state.
- VirtuVPN version.
- Approved VPN providers and package UIDs.
- Hotspot interface/gateway/subnet.
- DNS resolver mode.
- Date of final leak test.

## Appliance Policy

For sold Android 14 router devices:

- The phone is dedicated to VirtuVPN Router use.
- VirtuVPN and approved provider apps are the only required user-facing apps.
- Automatic OS updates should be controlled and tested before rollout.
- Battery/power optimization must stay disabled for VirtuVPN and approved
  providers.
- Hotspot timeout must stay disabled.
- Root access for VirtuVPN must stay permanent.
- Any factory reset, Android update, Magisk update, SIM/provider change, or VPN
  provider change requires rerunning this document.
