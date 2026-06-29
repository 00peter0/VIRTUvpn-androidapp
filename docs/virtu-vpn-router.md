# VirtuVPN Router

VirtuVPN Router turns a rooted Android phone into a VPN-protected hotspot router.
The primary supported flow is:

```text
hotspot client -> Android hotspot -> VirtuVPN Router rules -> active VPN tunnel -> internet
```

The router layer is independent from the VPN provider. The active tunnel can come
from VirtuVPN, WireGuard, NordVPN, or another provider, as long as Android exposes
a usable VPN interface such as `tun*` or `wg*`.

## Goals

- Route hotspot client traffic through the active VPN interface.
- Fail closed for hotspot clients when router rules are enabled.
- Keep the hotspot available while router mode is enabled; device hotspot
  auto-shutdown is a router safety risk.
- Keep the normal non-root phone VPN flow unchanged.
- Support multi-uplink detection:
  - mobile data / 5G / LTE,
  - WiFi sharing when the device supports hotspot and WiFi client mode at the same time,
  - ethernet or USB uplink,
  - unknown physical uplink.
- Show router status, uplink status, phone kill switch status, router protection,
  VirtuVPN app download QR, and router-only DNS settings in the VPN Router page.

## Multi-Uplink Model

The router should not assume that mobile data is always the upstream. Android
normally disables WiFi client mode when mobile hotspot starts, so the universal
flow is mobile data through VPN to hotspot clients. Some devices, especially some
Samsung builds, can keep WiFi client mode active while hotspot is running. This
is usually exposed as WiFi sharing.

Detected uplink types:

- Mobile data: interfaces such as `rmnet*`, `ccmni*`, `pdp*`, or `wwan*`.
- WiFi sharing: `wlan*` when it is not the hotspot downstream interface.
- Ethernet/USB: `eth*` or USB uplink interfaces when they are not the tethered
  downstream interface.
- Unknown: any physical default-route interface that does not match a known
  family.

Detection uses the device routing table and excludes:

- the active VPN tunnel,
- hotspot/downstream interfaces,
- loopback.

Router forwarding still targets the active VPN tunnel. The uplink is detected
for status, diagnostics, and future policy decisions; it does not change the
provider-neutral router rule model.

## Router Rules

When enabled, the app installs root rules that:

- enable IPv4 forwarding,
- attach a VirtuVPN NAT chain at the start of `POSTROUTING`,
- attach a VirtuVPN DNS chain at the start of `PREROUTING`,
- attach a VirtuVPN forwarding chain at the start of `FORWARD`,
- attach a VirtuVPN phone-output chain at the start of `OUTPUT`,
- add a policy route for each hotspot interface to the active VPN table,
- add an immediate fallback-block route after the VPN policy route so Android's
  lower-priority mobile tether route cannot carry hotspot traffic if the VPN
  table is temporarily unusable,
- redirect hotspot client TCP/UDP DNS on port 53 to the selected router DNS
  resolver,
- show a VirtuVPN app download link and QR code in the VPN Router page,
- allow the router phone's own internet traffic only through the VPN interface,
- allow the active VPN provider UID or WireGuard fwmark to use the physical
  uplink for tunnel transport,
- reject other router-phone traffic on physical uplinks while router mode is on,
- allow hotspot-to-VPN forwarding immediately,
- allow established VPN return traffic to hotspot clients,
- reject hotspot forwarding to any non-VPN path.

This gives hotspot clients a fail-closed router path. If the VPN interface is not
available, clients should not silently bypass through the phone uplink.

## VPN switch flow

When the user changes VPN provider or switches to another tunnel while VPN
Router is enabled, the router must treat the change as a protected transition.
The professional user-visible flow is a modal on the VPN Router page titled
`Securing VPN Router`. It shows the same steps the system is applying:

1. Lock hotspot fallback so clients cannot use mobile data directly.
2. Detect the active VPN interface.
3. Apply router DNS for hotspot clients.
4. Install VPN-only firewall and route rules.
5. Verify VPN route, DNS, IPv6 block, and mobile fallback block.

The important implementation rule is fail-closed ordering. The router prepares
the unreachable fallback route first and installs the `20901` hotspot block rule
before replacing the `20900` VPN policy route. That means if the VPN route is
missing, slow, or temporarily invalid during a provider switch, hotspot clients
lose internet instead of falling through to Android's lower-priority mobile
tether route.

The same deny-first rule applies to the first enable. Before IPv4 forwarding is
enabled, VirtuVPN creates the router forward chains, installs the hotspot
unreachable fallback route, adds `20901` for each hotspot interface, and inserts
temporary hotspot forwarding rejects. The final `20900` VPN route is added only
after the full DNS, IPv4, IPv6, and FORWARD rules are in place.

Current route priority model:

```text
20900: hotspot interface -> active VPN table
20901: hotspot interface -> unreachable fallback table 1048
21000: Android tether fallback -> physical uplink
```

`20901` must remain present during transition work. Do not clear both `20900`
and `20901` at the start of reconcile, because that opens a switch window where
Android's `21000` route can carry hotspot traffic over mobile data.

The modal is not only cosmetic. It is the operator-facing audit trail for the
active transition. If the flow fails, the router should remain blocked and show
the error instead of silently leaving clients on a direct uplink.

## Reconcile and performance

VPN Router disables Android tethering offload while router mode is enabled. This
is intentional because hardware/BPF tether offload can bypass normal iptables
visibility on some devices. The tradeoff is lower peak throughput than plain
Android tethering.

To avoid unnecessary slowdown, the reconcile loop must not rebuild router rules
when the effective router configuration has not changed. The app stores a
signature of:

- active VPN interface,
- hotspot downstream interfaces,
- router DNS resolvers,
- physical uplink interfaces.

On each reconcile, the app performs a lightweight health check for the required
policy routes and chain hooks. If the signature is unchanged and rules are
healthy, reconcile exits without flushing iptables chains, rewriting DNS
forwarders, or replacing policy routes. A full rebuild is allowed only when the
signature changes or the health check fails.

The hotspot fallback block rule is kept in place during rebuilds. Reconcile only
adds priority `20901` when it is missing instead of deleting and re-adding it,
so clients do not get a transient direct-uplink window during ordinary rule
refreshes.

The UI may show router protection as active only when the health check also sees
the policy routes, fallback unreachable route, IPv4/IPv6 hooks, and fail-closed
OUTPUT/FORWARD tails. Chain existence alone is not enough for an active status.
Background, Home, and VPN Router page refresh paths all reconcile both
`ENABLED` and degraded `ERROR` states so a fail-closed router can self-heal
without requiring a manual toggle.

This protects speed tests and large downloads from repeated route/firewall churn
while keeping the router fail-closed model intact.

Hotspot clients get internet through the router VPN path immediately. For safer
browsing on the client device itself, install VirtuVPN on that device and use
client-side protection. The VPN Router page shows a VirtuVPN app download link
and QR code for connected devices.

Secure Browser has its own detailed design document:
`docs/virtu-secure-browser.md`.

When VPN Router is enabled, the router phone also exposes a local attestation
endpoint on the hotspot gateway at
`/virtuvpn-router/attestation` port `8788`. VirtuVPN Secure Browser on a hotspot
client can use this nonce-bound signed response to verify that the current WiFi
gateway is the paired VirtuVPN Router before allowing browser traffic without a
local VPN transport. Pairing uses a random per-router secret shown as a QR code
on the VPN Router page while router protection is active. The endpoint is
inactive unless router protection is enabled.

Secure Browser must not trust ordinary private WiFi addressing as proof of
router protection. On client devices it is allowed only when the process can bind
to an Android VPN network. On the router phone itself it may also run while VPN
Router is active, because router OUTPUT lockdown enforces VPN egress.
It listens for VPN network changes with `ConnectivityManager.NetworkCallback`
instead of periodic polling, so a lost VPN network locks the WebView immediately.
WebRTC blocking is installed at document start when the WebView provider supports
AndroidX WebKit document-start scripts, with the older runtime injection kept as
a compatibility fallback.
Secure Browser is an ephemeral session: pause/destroy clears cookies, WebStorage,
cache, form data, and WebView history. It is not exported to other apps, and it
blocks private-address subresources from public HTTPS pages to reduce DNS
rebinding/LAN probing risk.
Secure Browser intentionally does not enable Android cleartext traffic. `http:`,
`ws:`, and top-level `data:` URLs are blocked; private LAN administration over
cleartext should use the separate Web Terminal flow. Android Safe Browsing stays
enabled as a malware/phishing protection tradeoff; any Safe Browsing lookups
egress through the bound VPN network.
Secure Browser also blocks common advertising and tracking hosts in
`shouldInterceptRequest` using a local in-app host/suffix matcher. This avoids a
network-fetched filter dependency, reduces third-party requests, and generally
improves both page speed and privacy.
The browser header shows the real protection path: a known VirtuVPN/WireGuard
tunnel when the app can identify one, a generic Android VPN provider for
third-party VPNs, or the active VPN Router tunnel on the router phone. After the
protected path is active, the browser performs a short egress identity check and
adds the apparent exit country and public IP without delaying page navigation.

The router phone also gets its own lockdown while router mode is enabled. Normal
phone internet must go through the active VPN interface. IPv4 and IPv6 phone
OUTPUT chains are fail-closed: loopback, the active VPN interface, WireGuard
transport marks, and the active VPN provider UID are allowed, then all other
phone output is rejected. This avoids relying only on known OEM uplink interface
names. When router mode is disabled, these OUTPUT rules are removed and the
phone returns to normal mobile internet behavior.

## DNS

Router DNS is separate from the phone non-root VPN DNS behavior.

Default:

- Copy DNS from tunnel: recommended for compatibility with the active VPN
  provider.

Selectable router resolvers:

- Fast: `1.1.1.1`, low-latency general resolver with no content filtering.
- Recommended secure: `9.9.9.9`, malware blocking and DNSSEC validation. This is
  the typical security-focused configuration, but it can be slower.
- Kid friendly: `1.1.1.3`, blocks malware and adult content for hotspot clients.

Google Public DNS is intentionally not offered for router mode because it can
expose EDNS Client Subnet data in resolver tests. Existing saved `google`
preferences are migrated to Quad9 during DNS mode resolution.

DNS settings apply only to hotspot clients while VPN Router is enabled. Phone and
non-root DNS behavior remain unchanged.

The active resolver is applied with hotspot-only DNAT rules for TCP/UDP port 53.
When Copy DNS from tunnel is selected, VirtuVPN first tries the active
Virtu/WireGuard tunnel config, then Android resolver properties, then falls back
to Quad9 secure DNS if no tunnel resolver can be read.

The router also blocks DoT, DoQ, UDP/443 QUIC, and common DoH resolver endpoints
so automatic and opportunistic encrypted DNS is pushed back to plaintext DNS on
port 53, where router DNAT applies the selected resolver. Blocking UDP/443
disables HTTP/3 for hotspot clients, but normal HTTPS falls back to TCP.

This is a DNS policy control, not a cryptographic content filter. A targeted
client can still tunnel DNS through an unknown HTTPS endpoint, WebSocket,
domain-fronted service, or ECH-protected connection. Blocking that transparently
would require whitelist-only egress or a MITM proxy, both of which break the
zero-config guest hotspot model. Future hardening can add SNI-based blocking for
known DoH hostnames and DDR/SVCB stripping. For DDR/RFC 9462, the resolver behind
router DNAT should avoid returning SVCB/HTTPS type 65 records for
`_dns.resolver.arpa`, so clients do not discover a designated encrypted resolver
automatically. ECH means even SNI is not a final guarantee.

## IPv6 leak handling

VPN Router currently treats IPv6 as protected only when the active VPN provider
offers a usable IPv6 tunnel route. Many Android VPN providers expose IPv4-only
tunnels, and Android tethering can still advertise or route IPv6 on the hotspot
side. That creates an IPv6 leak risk because IPv4 NAT/DNS router rules do not
cover IPv6 packets.

The production-safe default is therefore to block hotspot-client IPv6 forwarding
while VPN Router is enabled. The IPv6 FORWARD chain has per-downstream rejects
and a final default reject, so an unexpected tether interface name does not fall
back to native Android IPv6 forwarding. Clients keep IPv4 internet through the
VPN tunnel, but IPv6 tests should show no reachable client IPv6 path unless full
IPv6 router support is explicitly added later.

VPN Router also disables Android tethering offload while router mode is enabled.
Hardware/BPF offload can bypass ordinary iptables/ip6tables chains on some
devices, which would make the router phone scan look clean while hotspot clients
still leak. The previous offload setting is restored when router mode is
disabled.

Android tethering may also refresh its DNS forwarders from the cellular upstream
after hotspot clients are already connected. VPN Router reconciles those
forwarders back to the selected router resolver so clients do not fall back to
mobile-provider DNS during later scans.

## Hotspot lifetime and device configuration

Android and OEM builds can stop the hotspot without the VPN app explicitly
calling a stop API. On Samsung devices this can happen through the mobile hotspot
auto-timeout setting. In observed logs, SoftAP stopped with:

```text
CMD_SET_AP 0
CMD_AP_STOPPED
default_shutdown_timeout_setting=600000
```

`600000` is a 10-minute default shutdown timeout. That is unsafe for a router
device because clients disappear from WiFi and operators may misread the event as
router failure or VPN failure.

When VPN Router is enabled, VirtuVPN disables the Samsung hotspot timeout with:

```text
settings put secure wifi_ap_timeout_setting 0
```

The previous value is saved and restored when VPN Router is disabled. Reconcile
also reapplies this setting while router rules are installed, so app updates or
temporary hotspot changes do not leave the device with timeout enabled.

New router devices must be checked for hotspot lifetime behavior before being
trusted:

- Verify the hotspot does not auto-disable while VPN Router is on.
- On Samsung, verify `settings get secure wifi_ap_timeout_setting` returns `0`
  while router mode is enabled.
- Check `dumpsys wifi` for SoftAP stop events and shutdown timeout fields.
- If a firmware uses another OEM setting for hotspot timeout, add it to router
  setup before declaring the device production-ready.
- If hotspot is manually disabled or stopped by the OS, router rules must remain
  fail-closed; clients must not receive mobile uplink internet outside VPN.

VirtuVPN currently prevents the known Samsung timeout case. It does not yet
guarantee automatic SoftAP restart after a manual user/system stop. That can be
added later with a stored SoftAP profile and `cmd wifi start-softap`, but it must
not guess or overwrite the user's hotspot password.

## Client app download

The VPN Router page shows a VirtuVPN app download link and QR code for connected
client devices:

```text
https://vcs.virtucomputing.com/api/mobile/android/apk/guest
```

Router VPN protects the hotspot network path. For safer browsing on the client
device, download VirtuVPN to that device and use client-side protection there.
The client app is the supported path for device-local browser protection.

## Reconcile

When router rules are already enabled, the Home page and VPN Router page reconcile
the rules during status refresh. Reconcile re-installs the router chains using
the currently detected VPN tunnel, hotspot interfaces, uplink state, and DNS
mode. This covers common changes such as VPN reconnect, hotspot restart, DNS
mode change, and tunnel interface/table changes.

Reconcile also keeps router-only safety settings active:

- disables tethering offload,
- restores router DNS forwarders,
- disables known hotspot auto-timeout settings while router mode is on,
- removes duplicate router chain jumps before re-attaching chains.

## UI

Home keeps the compact router enable/disable control and links to the dedicated
VPN Router page.

The VPN Router page shows:

- router status,
- detected uplink,
- router protection status,
- VirtuVPN app download link and QR code for connected devices,
- router DNS options.

When router protection is active, hotspot clients stay associated with WiFi but
only receive internet through the VPN tunnel. If the tunnel path is unavailable,
client internet stops instead of falling back to the phone uplink. The router
phone itself is also locked down so ordinary phone traffic cannot use mobile data
outside the VPN while router mode is enabled.

## Implementation Phases

1. Detect root, VPN tunnel, hotspot interfaces, and physical uplink.
2. Install fail-closed router NAT, forwarding, and policy-routing rules.
3. Reconcile rules when VPN, hotspot, or uplink changes.
4. Apply router-only DNS behavior for hotspot clients.
5. Show VirtuVPN app download link and QR in the VPN Router page.
6. Disable known hotspot auto-shutdown behavior while router mode is enabled.
7. Validate with:
   - VirtuVPN tunnel,
   - third-party VPN providers,
   - mobile data uplink,
   - WiFi sharing uplink where supported,
   - hotspot restart,
   - hotspot idle period longer than the OEM timeout,
   - VPN reconnect,
   - VPN drop,
   - client reconnect with reused DHCP address,
   - DNS leak scans,
   - IPv6 leak scans,
   - Router page QR contains the VirtuVPN app download link,
   - regular browsing works without opening any router page.

## New device checklist

Before using a new rooted Android device as a production router:

1. Unlock/root and verify root shell can run `iptables`, `ip6tables`, `ip rule`,
   `settings`, and `ndc tether dns`.
2. Enable mobile hotspot and record:
   - hotspot interface name,
   - gateway address,
   - DHCP subnet,
   - whether WiFi sharing remains active.
3. Enable VPN Router and verify:
   - traffic leaves through the VPN egress,
   - direct mobile uplink is blocked for clients,
   - router phone ordinary output is blocked outside VPN except VPN transport,
   - router phone IPv6 output is blocked outside VPN except VPN transport,
   - `FORWARD` and `POSTROUTING` have one VirtuVPN jump each, and `PREROUTING`
     has the DNS jump only,
   - `ip rule` has hotspot VPN routing before a hotspot unreachable fallback,
     and both are before Android's mobile tether fallback.
   - interface names beginning with `-` are rejected before being used in shell
     commands.
4. Verify client app download behavior:
   - new client has internet through the router VPN path,
   - the Router page shows the VirtuVPN app download link,
   - the Router page QR code contains the same download link,
   - the download link serves the current guest APK.
5. Verify DNS behavior:
   - selected router resolver is used,
   - competing DoH/DoT providers are blocked,
   - UDP/443 is blocked so HTTP/3 and unknown DoH-over-QUIC fall back to TCP,
   - selected resolver family is not blocked by the DoH blocklist,
   - no mobile-provider DNS appears in repeated client scans.
6. Verify IPv6 behavior:
   - hotspot client IPv6 forwarding is blocked unless full provider IPv6 routing
     has been explicitly implemented,
   - router phone IPv6 output is blocked outside VPN except VPN transport,
   - DNS leak tools do not show client IPv6 egress outside the VPN.
7. Verify hotspot lifetime:
   - Samsung `wifi_ap_timeout_setting` or equivalent OEM timeout is disabled,
   - hotspot remains up beyond the device's old idle timeout,
   - `dumpsys wifi` does not show new unexpected `CMD_AP_STOPPED` events.
8. Verify cleanup:
   - disabling VPN Router removes router chains,
   - previous hotspot/offload settings are restored,
   - re-enabling does not duplicate chain jumps.

## Limits

WiFi sharing is device and firmware dependent. Android does not guarantee that
WiFi client mode can stay active while hotspot is enabled. VirtuVPN should detect
and use it when available, but the universal supported mode remains mobile data
or another physical uplink routed through the active VPN tunnel.
