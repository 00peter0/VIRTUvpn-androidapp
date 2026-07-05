# VirtuVPN Router Root Hardening

This document is a per-device hardening checklist for rooted Android phones used
as dedicated VirtuVPN Router appliances. These steps are outside the Android app
build and must be verified separately on every router device before sale or
deployment.

Goal: no external Android/OEM power manager, hotspot manager, root policy, or
provider app should be able to silently break router activity. If something does
fail, hotspot clients must remain fail-closed.

## Current Baseline From Router Audit

Observed device:

- Model: Samsung SM-A525F.
- Android: 14 / SDK 34.
- VirtuVPN build: 828 / `1.0.20260704.302-vcsinstall`.
- Root provider: Magisk.
- Router app UID: `10306`.
- Router service: `com.wireguard.android.VpnRouterService`.
- Router attestation listeners:
  - hotspot side: `<router-gateway>:8788`
  - app side: `127.0.0.1:8789`

Current good state:

- VirtuVPN is in the Android Doze user whitelist.
- VirtuVPN app ops allow background execution:
  - `RUN_IN_BACKGROUND: allow`
  - `RUN_ANY_IN_BACKGROUND: allow`
  - `START_FOREGROUND: allow`
  - `ACTIVATE_VPN: allow`
  - `ESTABLISH_VPN_SERVICE: allow`
  - `SYSTEM_ALERT_WINDOW: allow`
  - `REQUEST_INSTALL_PACKAGES: allow`
- `VpnRouterService` is a foreground service, `START_STICKY`, with an active
  router notification.
- Hotspot auto-timeout is disabled:
  - Samsung key: `wifi_ap_timeout_setting=0`
- Tether offload is disabled:
  - Android global key: `tether_offload_disabled=1`
- Router fail-closed rules are installed:
  - hotspot VPN rule priority `20900`
  - hotspot unreachable fallback priority `20901`
  - unreachable route table `1048`
  - IPv4/IPv6 reject chains
  - DNS DNAT for hotspot clients

## Per-Device Hardening Checklist

Run this checklist on every router phone after root, app install, SIM/provider
setup, and final VPN provider selection.

### Root And Magisk

1. Verify root shell works:
   - `su -c id`
   - expected UID: `0`.
2. Verify root commands needed by the router are available:
   - `iptables`
   - `ip6tables`
   - `ip`
   - `settings`
   - `ndc`
   - `ss`
   - `toybox nc`
3. Verify Magisk grants VirtuVPN permanent root access.
4. Root access must not require a prompt while router mode is running.
5. Root logging should remain available for audit. Do not silently disable
   Magisk logging/notifications as a product default.
6. Verify `su` remains available after reboot and after app update.

### Android Power Management

1. Add VirtuVPN to Doze whitelist:
   - `dumpsys deviceidle whitelist`
   - expected: `com.virtuvpn.android`.
2. Verify app standby bucket is not restricted:
   - `am get-standby-bucket com.virtuvpn.android`
   - expected: active/exempt/working-set style value, not restricted.
3. Verify app ops:
   - `appops get com.virtuvpn.android`
   - expected:
     - `RUN_IN_BACKGROUND: allow`
     - `RUN_ANY_IN_BACKGROUND: allow`
     - `START_FOREGROUND: allow`
     - `ACTIVATE_VPN: allow`
     - `ESTABLISH_VPN_SERVICE: allow`
4. On Samsung devices, check Device Care / sleeping apps manually:
   - VirtuVPN must not be in Sleeping apps or Deep sleeping apps.
   - VPN provider apps used by router mode must not be sleeping.
5. Keep the phone powered. Prefer USB power with stable cable/charger.

### Hotspot And Tethering

1. Disable hotspot idle timeout:
   - Samsung: `settings get secure wifi_ap_timeout_setting`
   - expected: `0`.
2. Disable tether offload unless a specific device/provider has been validated:
   - `settings get global tether_offload_disabled`
   - expected: `1`.
3. Verify hotspot remains up longer than the old OEM timeout.
4. Verify hotspot interface name and gateway:
   - common observed interface: `swlan0`
   - common observed gateway: `192.168.115.186`
5. Verify router attestation listener:
   - `<gateway>:8788` is listening.
   - `127.0.0.1:8789` is listening in the app process.
6. Verify hotspot clients can fetch signed attestation through the hotspot
   interface. Router-local requests to `<gateway>:8788` may be rejected by
   firewall policy and are not the supported test path.

### Foreground Service And Notification

1. Verify `VpnRouterService` is running as foreground service:
   - `dumpsys activity services com.virtuvpn.android`
   - expected: `isForeground=true`, `startRequested=true`, `startCommandResult=1`.
2. Verify process priority is protected by foreground service:
   - `dumpsys activity processes`
   - expected: VirtuVPN has foreground service or top/visible state while router
     mode is active.
3. Verify router notification is visible and not blocked:
   - channel: `vpn_router`
   - notification title: router protection active.

### Router Firewall Invariant

Verify these while VPN Router is ON:

1. Hotspot routing:
   - `ip rule show | grep 20900`
   - `ip rule show | grep 20901`
   - `ip route show table 1048`
   - expected: `unreachable default`.
2. IPv4 forwarding:
   - `iptables -S VIRTUVPN_ROUTER_FWD`
   - expected:
     - hotspot to tunnel `ACCEPT` when tunnel exists,
     - established return path,
     - final hotspot `REJECT`.
3. Router phone output:
   - `iptables -S VIRTUVPN_ROUTER_OUT`
   - expected:
     - loopback/VPN/fwmark/provider exceptions,
     - hotspot-local egress allowed,
     - physical uplink rejected,
     - final reject.
4. IPv6:
   - `ip6tables -S VIRTUVPN_ROUTER6_FWD`
   - `ip6tables -S VIRTUVPN_ROUTER6_OUT`
   - expected: hotspot IPv6 forwarding and phone IPv6 uplink output rejected
     unless full IPv6 provider routing was intentionally implemented.
5. DNS:
   - `iptables -t nat -S VIRTUVPN_ROUTER_DNS`
   - expected: hotspot DNS DNAT points to the selected router resolver.

### Provider Apps And UID Allowlist

The router may allow selected provider app UIDs so provider VPN transport can
create or maintain the tunnel while router phone output remains blocked.

Observed provider/app UIDs on the audited device:

- VirtuVPN: `10306`
- NordVPN: `10309`
- Surfshark: `10310`
- Android NetworkStack/Tethering: `1073`
- Android system: `1000`
- Samsung Fast / nearby service: `10196` (review before shipping)

Per-device action:

1. Map every UID in `VIRTUVPN_ROUTER_OUT` to a package:
   - `cmd package list packages -U | grep 'uid:<uid>'`
2. Keep only UIDs required for:
   - active VPN provider transport,
   - VirtuVPN app/router service,
   - Android system/tether/network stack.
3. Review and remove OEM convenience services unless proven necessary.
4. Re-test NordVPN, Surfshark, and VirtuVPN provider switching after any
   allowlist cleanup.

## Recommended Future Hardening Outside App

### Root Watchdog

Add a root-level watchdog for production router appliances. It should be outside
the normal Android app process and should not depend on Activity lifecycle.

Watchdog responsibilities:

- Check VirtuVPN process exists.
- Check `VpnRouterService` foreground service exists when router is expected ON.
- Check `<gateway>:8788` and `127.0.0.1:8789` listeners.
- Check hotspot interface exists.
- Check `20901` unreachable fallback and table `1048` still exist.
- If app process dies while rules remain active, restart VirtuVPN/router
  service.
- If router rules disappear unexpectedly, install an emergency fail-closed block
  or disable hotspot until VirtuVPN restores normal rules.

Do not use the watchdog to turn router mode on from a clean OFF state unless the
device has an explicit appliance policy saying router mode must always be on.

### Appliance Policy

For sold router devices, define an appliance profile:

- Router phone is dedicated to VPN Router use.
- VirtuVPN and approved provider apps are the only user-facing apps required.
- Automatic OS updates should be controlled and tested before rollout.
- Battery/power optimization must stay disabled for VirtuVPN and approved
  providers.
- Hotspot timeout must stay disabled.
- Root access for VirtuVPN must stay permanent.
- Any factory reset or provider change requires rerunning this checklist.

## Acceptance Criteria

A router device is ready for sale/deployment only when:

- It passes the router app checklist in `virtu-vpn-router.md`.
- It passes this root hardening checklist.
- It survives:
  - app update while router is ON,
  - provider tunnel blip,
  - provider switch,
  - hotspot reconnect,
  - screen off / idle period,
  - reboot and router restore procedure if configured.
- Hotspot client leak tests show:
  - VPN egress only,
  - no mobile-provider DNS,
  - no client IPv6 uplink leak,
  - browser protection works through VirtuVPN Secured Browser.
