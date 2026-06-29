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
  and router-only DNS settings in the VPN Router page.

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
- redirect hotspot client TCP/UDP DNS on port 53 to the selected router DNS
  resolver,
- expose a local guest dashboard on the router gateway without transparent HTTP
  hijacking,
- allow the router phone's own internet traffic only through the VPN interface,
- allow the active VPN provider UID or WireGuard fwmark to use the physical
  uplink for tunnel transport,
- reject other router-phone traffic on physical uplinks while router mode is on,
- allow hotspot-to-VPN forwarding immediately,
- allow established VPN return traffic to hotspot clients,
- reject hotspot forwarding to any non-VPN path.

This gives hotspot clients a fail-closed router path. If the VPN interface is not
available, clients should not silently bypass through the phone uplink.

Guest clients must not depend on captive portal HTML for connectivity. Captive
portal behavior is only best-effort because client operating systems cache probe
results, suppress popups, and handle private DNS, VPN, and browser state
differently. The router therefore brings VPN-routed internet up immediately and
exposes the guest page as an optional dashboard at the router gateway. Router
Secure Web still works from that dashboard because it is served locally by the
router phone and performs outbound requests from the router side.

The router phone also gets its own lockdown while router mode is enabled. Normal
phone internet must go through the active VPN interface. The physical uplink is
kept available only for the VPN transport itself, so the tunnel can stay alive
without allowing ordinary phone apps to bypass the VPN. When router mode is
disabled, these OUTPUT rules are removed and the phone returns to normal mobile
internet behavior.

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

## IPv6 leak handling

VPN Router currently treats IPv6 as protected only when the active VPN provider
offers a usable IPv6 tunnel route. Many Android VPN providers expose IPv4-only
tunnels, and Android tethering can still advertise or route IPv6 on the hotspot
side. That creates an IPv6 leak risk because IPv4 NAT/DNS router rules do not
cover IPv6 packets.

The production-safe default is therefore to block hotspot-client IPv6 forwarding
while VPN Router is enabled. Clients keep IPv4 internet through the VPN tunnel,
but IPv6 tests should show no reachable client IPv6 path unless full IPv6
router support is explicitly added later.

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

## Guest protocol and portal

VPN Router exposes a local guest protocol on the router phone while the app
process is alive:

- `http://<router-gateway>:8787/virtuvpn-router/status` returns a VirtuVPN router
  marker for client apps.
- the guest dashboard is reachable manually on the router gateway port. Router
  mode does not transparently hijack client HTTP because captive HTML is
  unreliable and can break normal browsing.
- captive probe endpoints such as `generate_204`, `gen_204`,
  `hotspot-detect.html`, `connecttest.txt`, and `ncsi.txt` redirect to the
  router portal with no-cache headers.
- the guest page opens Router Secure Web by default, links to the guest APK
  download, and shows the manual dashboard address.
- ordinary client browsing works without a portal decision; router-level VPN,
  DNS, IPv6, DoT/DoH, and uplink-bypass rules provide the network protection.

Client-side Secure Browser detection prefers the guest protocol marker and only
uses the known Samsung `192.168.115.0/24` hotspot subnet as a fallback heuristic.

## Router Secure Web

Router Secure Web is the no-install browser path served by the router phone. It
is not the same engine as the native Android Secure Browser, but it copies the
native Secure Browser surface and security model as closely as a hotspot HTML
service can:

- top URL/search bar,
- quick links,
- protected status indicator,
- floating reload/back/forward controls,
- public HTTP blocked by default,
- HTTPS and private/local HTTP allowed,
- WebRTC APIs disabled by injected script on proxied HTML,
- links rewritten back through the router proxy.

The request path is:

```text
client browser -> local router portal -> router proxy -> VPN tunnel -> internet
```

This avoids relying on unreliable HTML deep links into the Android app. It also
reduces client-side DNS and local IP exposure because the destination site is
loaded by the router-side proxy, not by the client as a direct normal browsing
session.

Limits:

- It is a lightweight HTTP/HTML proxy, not a full remote Chromium engine.
- Complex JavaScript apps, banking flows, CSP-heavy pages, video, and federated
  login can break or run slowly.
- It is best for safer quick browsing, leak tests, and simple pages. For full
  client-device protection, install VirtuVPN and use the native Secure Browser.

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
5. Add guest onboarding page without enroll dependency:
   - continue without client kill switch,
   - install VirtuVPN for stronger client-side protection.
6. Add Router Secure Web as a no-install guest browsing path.
7. Keep captive access optional; never block VPN-routed internet while waiting
   for HTML.
8. Disable known hotspot auto-shutdown behavior while router mode is enabled.
9. Validate with:
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
   - captive portal open on Android/Samsung/iOS where available,
   - regular browsing still works when the captive portal does not open.

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
   - `FORWARD` and `POSTROUTING` have one VirtuVPN jump each, and `PREROUTING`
     has the DNS jump only.
4. Verify guest dashboard behavior:
   - new client has internet even if the portal does not open,
   - the router dashboard is reachable at the gateway fallback address,
   - Router Secure Web works from the dashboard,
   - the guest APK install/update link works.
5. Verify DNS behavior:
   - selected router resolver is used,
   - competing DoH/DoT providers are blocked,
   - selected resolver family is not blocked by the DoH blocklist,
   - no mobile-provider DNS appears in repeated client scans.
6. Verify IPv6 behavior:
   - hotspot client IPv6 forwarding is blocked unless full provider IPv6 routing
     has been explicitly implemented,
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
