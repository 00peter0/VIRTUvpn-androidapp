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
- allow the router phone's own internet traffic only through the VPN interface,
- allow the active VPN provider UID or WireGuard fwmark to use the physical
  uplink for tunnel transport,
- reject other router-phone traffic on physical uplinks while router mode is on,
- allow hotspot-to-VPN forwarding,
- allow established VPN return traffic to hotspot clients,
- reject hotspot forwarding to any non-VPN path.

This gives hotspot clients a fail-closed router path. If the VPN interface is not
available, clients should not silently bypass through the phone uplink.

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

## Guest protocol and portal

VPN Router exposes a local guest protocol on the router phone while the app
process is alive:

- `http://<router-gateway>:8787/virtuvpn-router/status` returns a VirtuVPN router
  marker for client apps.
- hotspot HTTP traffic is redirected to a VirtuVPN guest page while router mode is
  enabled.
- the guest page recommends VirtuVPN Secure Browser for safest browsing, links to
  VirtuVPN install/open actions, and offers an explicit regular-browser bypass.
- choosing the regular-browser bypass inserts a client-IP return rule in the
  router portal chain so the page stops appearing for that hotspot session.

Client-side Secure Browser detection prefers the guest protocol marker and only
uses the known Samsung `192.168.115.0/24` hotspot subnet as a fallback heuristic.

## Reconcile

When router rules are already enabled, the Home page and VPN Router page reconcile
the rules during status refresh. Reconcile re-installs the router chains using
the currently detected VPN tunnel, hotspot interfaces, uplink state, and DNS
mode. This covers common changes such as VPN reconnect, hotspot restart, DNS
mode change, and tunnel interface/table changes.

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
6. Validate with:
   - VirtuVPN tunnel,
   - third-party VPN providers,
   - mobile data uplink,
   - WiFi sharing uplink where supported,
   - hotspot restart,
   - VPN reconnect,
   - VPN drop.

## Limits

WiFi sharing is device and firmware dependent. Android does not guarantee that
WiFi client mode can stay active while hotspot is enabled. VirtuVPN should detect
and use it when available, but the universal supported mode remains mobile data
or another physical uplink routed through the active VPN tunnel.
