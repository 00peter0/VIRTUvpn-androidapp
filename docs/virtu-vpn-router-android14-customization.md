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
