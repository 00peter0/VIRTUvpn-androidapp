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
- add a policy route for each hotspot interface to the active VPN table,
- redirect hotspot client TCP/UDP DNS on port 53 to the selected router DNS
  resolver,
- allow hotspot-to-VPN forwarding,
- allow established VPN return traffic to hotspot clients,
- reject hotspot forwarding to any non-VPN path.

This gives hotspot clients a fail-closed router path. If the VPN interface is not
available, clients should not silently bypass through the phone uplink.

## DNS

Router DNS is separate from the phone non-root VPN DNS behavior.

Default:

- Copy DNS from tunnel: recommended for compatibility with the active VPN
  provider.

Selectable router resolvers:

- Fast: `1.1.1.1`, low-latency general resolver with no content filtering.
- Reliable: `8.8.8.8`, globally available resolver with strong compatibility.
- Recommended secure: `9.9.9.9`, malware blocking and DNSSEC validation. This is
  the typical security-focused configuration, but it can be slower.
- Kid friendly: `1.1.1.3`, blocks malware and adult content for hotspot clients.

DNS settings apply only to hotspot clients while VPN Router is enabled. Phone and
non-root DNS behavior remain unchanged.

The active resolver is applied with hotspot-only DNAT rules for TCP/UDP port 53.
When Copy DNS from tunnel is selected, VirtuVPN first tries the active
Virtu/WireGuard tunnel config, then Android resolver properties, then falls back
to Quad9 secure DNS if no tunnel resolver can be read.

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
- phone kill switch status,
- router protection status,
- router DNS options.

When router protection is active, hotspot clients are protected by router rules
even if the phone kill switch is off. The phone kill switch still protects only
the phone's own traffic.

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
