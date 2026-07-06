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

## Home Screen Widgets

VirtuVPN exposes a VPN Router home-screen widget for dedicated router devices.
The widget is a control/status surface, not a separate security boundary. Router
security is still enforced by the root routing and firewall rules described in
this document.

The VPN Router widget uses:

- `@layout/widget_vpn_router_path` as the live `initialLayout`,
- `@layout/widget_vpn_router_path` as `android:previewLayout`,
- `@drawable/widget_vpn_router_preview` as the fallback `android:previewImage`,
- `@drawable/widget_vpn_router_mark` as the router artwork inside both the live
  widget and preview.

This is intentional. On Android 12+ launchers, including Samsung One UI,
`previewLayout` is the reliable way to make the widget picker render the same
shape and branding as the live widget. `previewImage` is only a fallback for
launchers that do not support layout previews, and it may be scaled or cached
differently by OEM launchers.

When changing widget branding:

1. Update the live layout and preview path together.
2. Keep `previewLayout` pointed at the live widget layout unless the preview
   must intentionally differ.
3. Keep `previewImage` as a fallback, but do not rely on it as the primary
   Samsung/Android 12+ picker rendering path.
4. Bump `wireguardVersionCode`; Samsung launcher can keep old widget resources
   cached after `adb install -r`.
5. After installing a new build on a router, force-stop/restart Samsung launcher
   or reboot the device if the widget picker still shows an old preview.

For the current router widget artwork, the source asset came from
`router-widget.png` on the `vcs-llm` workstation. The committed Android asset is
the cropped standalone router mark in
`ui/src/main/res/drawable-nodpi/widget_vpn_router_mark.png`.

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
- add a policy route for each hotspot interface to the router VPN table
  (`1047`) whose default route points at the active VPN interface,
- add an immediate fallback-block route after the VPN policy route so Android's
  lower-priority mobile tether route cannot carry hotspot traffic if the VPN
  table is temporarily unusable,
- redirect hotspot client TCP/UDP DNS on port 53 to the selected router DNS
  resolver,
- show a VirtuVPN app download/pairing QR code in the VPN Router page,
- allow the router phone's own internet traffic only through the VPN interface,
- allow the active VPN provider UID, installed Android `VpnService` provider
  UIDs, or WireGuard fwmark to use the physical uplink for tunnel transport,
- allow narrow router-phone bootstrap traffic needed by Android VPN providers:
  selected Android connectivity/DNS system UIDs and plain DNS on TCP/UDP port
  53. This is phone OUTPUT only, not hotspot FORWARD, and exists so providers
  such as NordVPN can resolve and establish a new `tun` interface while router
  mode is fail-closed,
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
6. Check internet through the selected VPN tunnel.
7. Restore the last healthy VirtuVPN tunnel if the new tunnel fails.

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
20900: hotspot interface -> router VPN table 1047 -> active VPN interface
20901: hotspot interface -> unreachable fallback table 1048
21000: Android tether fallback -> physical uplink
```

`20901` must remain present during transition work. Do not clear both `20900`
and `20901` at the start of reconcile, because that opens a switch window where
Android's `21000` route can carry hotspot traffic over mobile data.

The modal is not only cosmetic. It is the operator-facing audit trail for the
active transition. If the flow fails, the router should remain blocked and show
the error instead of silently leaving clients on a direct uplink.

## Tunnel health and Virtu fallback

The router treats the active VPN interface as a candidate until it passes a
health gate. After installing fail-closed rules for the candidate, the app checks
internet through that exact interface with TCP/HTTPS probes using
`curl --interface <vpn-interface>`. This is the primary health signal because
some Samsung/Android builds reject `ping -I <tun>` with `SO_BINDTODEVICE:
Operation not permitted` even when ordinary HTTPS traffic through the tunnel is
working. ICMP probes against the selected router DNS resolver and public IPv4
targets remain only as a fallback signal for environments where interface-bound
ping works.

The health gate has hysteresis because some VPN providers and exits
intermittently drop ICMP even while ordinary traffic works:

- an already-enabled router tolerates transient missed probes and requires 3
  consecutive failed health cycles before moving to `DEGRADED`,
- a degraded router requires 2 consecutive successful health cycles before it
  returns to `ENABLED`,
- fail-closed route/firewall rules remain installed during both directions, so
  clients either keep the existing protected path or have no internet.

Router health diagnostics distinguish two user-facing failure classes without
changing the security decision:

- If Android reports a non-VPN uplink with
  `NET_CAPABILITY_INTERNET` and `NET_CAPABILITY_VALIDATED`, but the selected
  VPN interface fails the tunnel health gate, the router reports the selected
  VPN tunnel as unhealthy and suggests trying another tunnel.
- If Android does not report a validated non-VPN uplink, the router reports the
  upstream internet as unavailable (mobile data, ISP, or captive portal). This
  covers cases where the VPN provider cannot build a tunnel because the router
  phone itself has no usable upstream internet.

This upstream-vs-tunnel distinction is passive. It uses Android's already-known
network validation state and does not send active probes over the physical
uplink. It is used only for status text, notification text, and Secured Browser
blocked copy. Without a healthy VPN path, hotspot clients remain fail-closed in
both cases.

If the candidate health check fails, hotspot clients remain protected by
`20901 -> table 1048 -> unreachable default`. They do not fall back to Android's
mobile tether route. VirtuVPN then attempts a controlled fallback only when the
previous known-good tunnel is a VirtuVPN-managed tunnel that the app can start
itself through `TunnelManager`.

Fallback rules:

- If the new tunnel is healthy, router table `1047` remains pointed at it and
  the router becomes active.
- If the new tunnel is unhealthy and the previous VirtuVPN tunnel can be
  restarted, the router rebuilds rules against the fallback tunnel and runs the
  same health gate again.
- If no VirtuVPN fallback exists, or the fallback also fails health, the router
  stays fail-closed and surfaces an error in the operation modal.
- Third-party providers such as NordVPN can be detected and their transport UID
  can be allowed through the phone OUTPUT lockdown, but VirtuVPN cannot reliably
  restart those apps because Android does not expose a universal third-party VPN
  control API.
- If a third-party tunnel is down or still connecting, the router keeps hotspot
  clients blocked by `20901` while allowing only the provider/bootstrap path on
  the router phone. Without this phone-side bootstrap, Android may show the
  router phone as offline and the third-party provider may be unable to create
  the next `tun` interface.

This means a provider switch is never allowed to degrade into direct mobile
tethering. The best outcome is a healthy new tunnel, the second-best outcome is
a healthy VirtuVPN fallback, and the safe failure outcome is no client internet.

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
- physical uplink interfaces,
- active VPN owner UID,
- installed Android `VpnService` provider UIDs that are allowed to bootstrap a
  VPN tunnel through the phone OUTPUT lockdown.

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
`ENABLED` and `DEGRADED` states so a fail-closed router can self-heal without
requiring a manual toggle.

When the rules signature is unchanged and route/firewall verification passes,
reconcile performs only the tunnel-health sample. It does not flush chains or
rewrite policy routes. A single weak provider probe does not immediately drop
the router to degraded; the failure counter must cross the hysteresis threshold.
This avoids Secure Browser protection flapping on providers such as NordVPN
where ICMP behavior can be less stable than real HTTPS traffic.

This protects speed tests and large downloads from repeated route/firewall churn
while keeping the router fail-closed model intact.

Hotspot clients get internet only through the router VPN path after the route,
firewall, DNS, IPv6, and tunnel-health checks pass. Router tests showed that
device-local browser safety must not be assumed from WiFi association or normal
browser privacy modes. For safe web browsing on a hotspot client, install
VirtuVPN on that client and use VirtuVPN Secured Browser with router pairing.
The VPN Router page shows a QR code that opens the router download/pairing
landing page served locally by the router phone over the hotspot.

Secure Browser has its own detailed design document:
`docs/virtu-secure-browser.md`.

When VPN Router is active, the router phone exposes a local HTTP server on the
hotspot gateway at port `8788`. The same server provides:

- `/router/pair#id=...&secret=...`: local download/pairing HTML for hotspot
  clients,
- `/virtuvpn.apk`: local download of the same VirtuVPN APK installed on the
  router phone,
- `/virtuvpn-router/attestation`: nonce-bound signed router attestation for
  VirtuVPN Secured Browser.

VirtuVPN Secure Browser on a hotspot client can use the attestation response to
verify that the current WiFi gateway is the paired VirtuVPN Router before
allowing browser traffic without a local VPN transport. Pairing uses a random
per-router secret exposed through the local router pairing landing page while
router protection is active. The landing page offers manual actions only:
install/update VirtuVPN from the router, copy the Secure Browser pair key, and
show the pair key for manual paste. It must not perform hidden redirects,
background tests, or browsing-content serving.

The endpoint is inactive unless router protection is active. The app-owned HTTP
server listens only on internal localhost port `8789`. Router rules start a
root-owned persistent hotspot proxy on the current gateway address at public
port `8788` and forward requests to `127.0.0.1:8789`. Current builds use
`toybox nc -L` for the hotspot proxy, not a single-shot `nc -l` loop. The proxy
must keep one stable listener with backlog and parallel accepted connections;
there must be no sleep/restart gap between client requests. This keeps the
external URL stable (`http://<router-gateway>:8788/router/pair`) while avoiding
provider/OEM cases where hotspot clients can reach root-owned local listeners
but not app-UID listeners directly. When router status is `ENABLED`,
attestation returns a signed `protected=true`. When router rules are still
fail-closed but the tunnel is `DEGRADED`, attestation returns a signed
`protected=false` with the router availability/detail. Secure Browser treats
that as a verified router that is currently unsafe for browsing and keeps the
WebView blocked with a router-degraded message. This distinction prevents false
green status while still proving that the client is talking to the paired
router.

The router process must stay network-unbound while router mode is active.
Secured Browser may call Android's process-wide `bindProcessToNetwork()` on
ordinary client devices, but the router phone itself must use local-router
protection without a VPN bind. The router foreground service clears any stale
process network binding before starting or refreshing the pairing/attestation
server so provider-specific VPN routing cannot make the local HTTP server stop
answering hotspot clients.

The app-side attestation server lifecycle is intentionally separate from the
root hotspot proxy lifecycle. The app server binds only `127.0.0.1:8789`; the
public gateway address belongs to the root proxy. Therefore changes in the
detected tether gateway host must not force an app-server rebind while the
loopback listener is already alive. `start()`/`stop()` are serialized by a
lifecycle lock, and a redundant failed `start()` must not call global `stop()`
or close a listener created by another successful start attempt. This prevents
enable-time `EADDRINUSE` bursts from turning into a temporary attestation
outage.

Router pairing is
intentionally QR/in-app/manual-paste only. `virtuvpn://router-pair`,
`http://<router-gateway>:8788/router/pair#id=...&secret=...`, and the legacy
`https://vcs.virtucomputing.com/router/pair#id=...&secret=...` format must all
parse through the same client-side pairing parser, but the client app must
always require explicit confirmation before storing the router secret. This
prevents a web page from silently replacing the trusted router. Clients can
store multiple paired routers, pairings expire after 7 days, and the Secure
Browser blocker screen provides an explicit unpair action.

The attestation server may still bind on all local addresses for Android
compatibility, but router firewall rules restrict TCP port `8788` to detected
hotspot downstream interfaces and reject the same port from other interfaces.
The HTTP handler also keeps the source-address allowlist as a second layer.
It must read and discard the full HTTP request headers before writing the
response. If the server writes a JSON response and closes the socket while the
client's request headers remain unread, Android/Linux can emit a TCP reset and
clients may receive `HTTP 200` headers without the JSON body. Secure Browser
treats that as an invalid/unreachable attestation and remains blocked.

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
When the user explicitly enables `Sessions On`, Secured Browser keeps in-memory
tab WebViews while the app is left and reopened, but it re-verifies the
VPN/router protection path before browsing continues. Turning `Sessions Off`
destroys all tab sessions and returns the browser to the default ephemeral
behavior.
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

Real-state interpretation: `Copy DNS from tunnel` does not mean the router is
using a fallback just because the installed resolver is Quad9. If the rule
signature contains resolver IPs read from the active tunnel/provider, those are
provider DNS values. For example:

```text
dns_mode=copy_tunnel
last_rule_signature=tun0|swlan0|9.9.9.9,149.112.112.112|...
VIRTUVPN_ROUTER_DNS -i swlan0 --dport 53 -j DNAT --to-destination 9.9.9.9
```

This means the active tunnel/provider supplied Quad9 (`9.9.9.9`,
`149.112.112.112`) and the router applied the first provider resolver to hotspot
clients. The Quad9 fallback is used only when `copy_tunnel` cannot read any
usable IPv4 DNS resolver from the tunnel config or Android DNS state.

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

## Client app download and pairing

The VPN Router page shows a QR code for connected client devices. When router
protection is active, the QR points to the local hotspot landing page:

```text
http://<router-gateway>:8788/router/pair#id=<router-id>&secret=<router-secret>
```

The local active-router landing page must provide:

- Install/update VirtuVPN app from the router phone:
  `http://<router-gateway>:8788/virtuvpn.apk`.
- Copy Secure Browser pair key.
- Visible pair key text for manual copy if clipboard integration fails.
- Clear instruction that safe web browsing on hotspot clients should be done in
  VirtuVPN Secured Browser after pairing.

It must not provide hidden redirects, background tests, browsing surfaces, or
network diagnostics. The page is only for download and explicit manual pairing.
It is intentionally not bound to VCS sign-in, enrollment, or internet access.
An unenrolled app installation behaves as the guest browser client; after
enrollment/sign-in the same APK unlocks full VCS functionality.

The router-local `/virtuvpn.apk` endpoint is the only no-enroll guest install
path for this router flow. Do not keep a second VCS guest download path in the
web app; it duplicates the router guest model. Dashboard download and
enrollment-token install endpoints remain separate flows.

Supported pair-key formats:

```text
virtuvpn://router-pair?id=<router-id>&secret=<router-secret>
http://<router-gateway>:8788/router/pair#id=<router-id>&secret=<router-secret>
https://vcs.virtucomputing.com/router/pair#id=<router-id>&secret=<router-secret>
```

Both formats must parse through the same client-side pairing parser. The app may
be opened from either form, but storing the router secret always requires a
VirtuVPN confirmation dialog.

Pairing incident resolved in builds 744-747:

- Router rules could be healthy while the local attestation server was not
  running after app update/process restart. `VpnRouterManager` now synchronizes
  the attestation server lifecycle directly on enable, reconcile, and disable.
- Attestation status cache was cold, so the first request could block on root
  status probes and exceed the client timeout. The reconcile monitor now
  pre-warms the status cache and the client timeout has more headroom.
- The HTTP server could return headers and then reset before the JSON body
  because it closed the socket before draining request headers. The handler now
  drains request headers before writing the response.
- The router QR used the trusted landing URL format, but the client QR/deep-link
  parser originally accepted only `virtuvpn://router-pair`. The client now
  accepts both the app URI and the landing URL fragment format.

Attestation stability incident resolved in builds 818-819:

- The previous hotspot proxy used a single-shot `toybox nc -l` inside a
  `while true` loop. Each accepted connection briefly removed the listener, so
  concurrent hotspot clients could receive `Connection refused` /
  `UNREACHABLE`. The proxy now uses persistent `toybox nc -L`.
- Proxy liveness must be verified against the persistent listener state. A
  transient proxy blip must not cause unnecessary full firewall rebuilds while
  router rules are otherwise healthy.
- The app-side loopback server no longer restarts just because the detected
  tether host changes; it updates the remembered host while keeping
  `127.0.0.1:8789` alive.
- App-side `start()`/`stop()` are serialized so redundant enable/reconcile
  starts cannot race on `127.0.0.1:8789`. A failed redundant start closes only
  its own partially opened socket and must not stop an already running server.

Expected steady-state process shape on the router while VPN Router is enabled:

```text
sh -c toybox nc -4 -s <router-gateway> -p 8788 -L toybox nc -4 127.0.0.1 8789
toybox nc -4 -s <router-gateway> -p 8788 -L toybox nc -4 127.0.0.1 8789
LISTEN <router-gateway>:8788
LISTEN 127.0.0.1:8789
```

There should be no `while true; ... nc -l ... sleep` proxy loop in current
builds.

Package-update attestation recovery added in builds 820-821:

- Android package replace kills the app process. Root firewall rules and the
  root `8788` proxy can survive that, but the app-owned `127.0.0.1:8789`
  attestation server dies with the process. Without recovery this creates a
  zombie proxy that accepts hotspot connections but cannot return signed
  attestation responses until the user manually opens VirtuVPN.
- `ACTION_MY_PACKAGE_REPLACED` is handled by a non-exported receiver. It starts
  `VpnRouterService` immediately to minimize the zombie-proxy window after
  update. The service then reads the real router status from kernel/firewall
  state, not from a "desired on" preference. If router rules are not already
  active, the service does not install rules and naturally stops after inactive
  monitor ticks, so an app update cannot turn on VPN Router if it was off.
- The service startup path then clears stale process network binding, restarts
  the app-side attestation server, warms status, and lets reconcile restore the
  proxy/rules if needed.
- Manual "open the app once after update" is now a fallback/debug step, not the
  normal production update flow.


Attestation protected-bit model from build 824 onward:

- `protected` means only the security invariant: router fail-closed firewall,
  reject rules, and unreachable hotspot fallback are installed well enough to
  prevent client traffic from leaking to the physical uplink.
- `protected` does not mean the selected VPN tunnel currently has working
  internet. Tunnel quality is reported separately as signed `tunnelOnline`
  metadata.
- A degraded tunnel or upstream outage therefore signs `protected=true` when
  fail-closed rules still hold. Clients may have no internet, but they are still
  protected from uplink leakage.
- `protected=false` is reserved for actual security-invariant failure, such as
  missing reject/unreachable rules or missing router chains. Secured Browser
  must block on that state.
- This is stricter and less noisy than treating tunnel-health failures as
  security failures: browser availability no longer depends on curl/ISP jitter,
  while real firewall/routing failures still fail closed.

Router lifecycle hardening added in build 825:

- A single root-shell/detect hiccup must not tear down the attestation plane.
  If `detect()` cannot read router chains but the previous signed state was
  active, the router keeps the last active fail-closed status for a short
  3-strike confirmation window and logs the miss as a transient root check.
- Non-active status reported by reconcile no longer immediately calls
  `stopService()`. `VpnRouterService` already has an inactive-tick grace window
  and is responsible for stopping itself if the router really stays inactive.
- Explicit router off remains immediate: `disable()` still removes rules,
  clears latched router state, stops the app-side attestation server, and stops
  the foreground service. The grace applies only to transient detection misses,
  not to user-requested shutdown.

Tunnel-interface transient handling added in build 826:

- If router rules are installed but the VPN interface temporarily disappears
  during provider renegotiation, `detect()` no longer signs `protected=false`
  by default. It reuses the last active tether snapshot and verifies the
  fail-closed invariant that still matters without an active tunnel: hotspot
  policy rule to the unreachable table plus IPv4/IPv6 reject chains.
- The tunnel-specific `FORWARD -o <tun>` allow rule is checked only when a
  tunnel interface exists. When the tunnel is absent, clients have no internet,
  but traffic still cannot fall back to the physical uplink while the
  unreachable/reject rules hold.

Tunnel-interface rebuild guard added in build 827:

- When the VPN interface is temporarily missing while router rules are still
  installed, reconcile/enable does not attempt a full rule rebuild. Rebuilding
  against a missing tunnel would tear down live fail-closed rules and then fail
  at the routing step.
- The router instead keeps the installed fail-closed rules untouched, keeps
  attestation synchronized, and waits for the provider to recreate the tunnel
  interface.

Router rebuild hardening added in build 828:

- All router `iptables`/`ip6tables` operations run with `-w 5` through a shell
  wrapper. This prevents netd/tether rule rewrites from causing transient
  xtables lock failures during link changes.
- A healthy hotspot attestation proxy is no longer stopped during a router rule
  rebuild. It is restarted only when verification says the proxy is not healthy
  for the current downstream interface.
- Fail-closed ordering is make-before-break where possible: hotspot forwarding
  deny rules are inserted immediately after forward-chain flush, the previous
  OUTPUT fail-closed chain remains active until just before the new egress set
  is written, and the `INPUT` allow for attestation is refreshed only at the end.
  This keeps browser pairing/attestation reachable while preserving uplink
  leak protection.

Hardware validation after build 828:

- Simulated `tun0` blip test: 60/60 hotspot-client attestation samples returned
  `protected=true`. There were no empty responses, no `protected=false`, and no
  `503` responses during the down/recovery cycle.
- During the blip and rebuild, attestation remained reachable and reported
  `protected=true` with `tunnelOnline=false`; after recovery it returned to
  `availability=ENABLED`.
- Secured Browser stayed verified throughout the test. No router-degraded,
  unreachable, or invalid-response browser blocks were logged during the
  simulated provider renegotiation.
- Router logs showed no reconcile failure and no xtables exit-code 4. The
  rebuild completed on the first attempt with the attestation proxy still
  serving.
- The routing backstop stayed present throughout: hotspot policy rule to the
  unreachable table plus unreachable default route remained intact, so there
  was no uplink leak window.

Stability status after builds 824-828:

- Tunnel without internet: no browser block; router signs `protected=true` and
  reports tunnel quality separately.
- ISP/uplink outage: no browser block caused by tunnel-quality noise; UI should
  diagnose upstream vs tunnel failure separately.
- Root-shell/detect hiccup: no permanent attestation plane shutdown.
- App package update: router service restores automatically after package
  replace.
- VPN interface renegotiation/blip: no browser block and no rule rebuild against
  a missing tunnel interface.
- Leak posture is unchanged: security still relies on fail-closed firewall,
  policy routing, unreachable fallback, and IPv6 reject rules.

Router-state anti-flap hardening added in build 822:

- Attestation `protected` reflects the router's signed availability state, so a
  transient false-negative in rule verification must not immediately become a
  browser block. Router rule verification now uses the same 3-strike principle
  as tunnel health checks: a single missed `iptables`/route/proxy check keeps
  the last protected state, while sustained misses still transition to
  `ERROR` and trigger fail-closed recovery.
- This does not make the router fail-open. The firewall/routing rules remain in
  the kernel while the transient verify miss is being confirmed. If the rules
  are genuinely gone, verification fails repeatedly and the router stops
  signing `protected=true`.
- The attestation status cache TTL is longer than the worst observed
  reconcile/health-check burst, so clients should not receive `503 Unavailable`
  merely because a slow health probe is running.

Router VPN protects the hotspot network path. For safe browsing on the client
device, download VirtuVPN to that device, pair Secured Browser with the router,
and browse through VirtuVPN Secured Browser. The client app is the supported
path for device-local browser protection because it can verify router
attestation and fail closed when the router tunnel is degraded or unreachable.
Ordinary browsers must not be presented as equivalent protection.

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
- allows installed VPN provider UIDs to bootstrap tunnel transport while keeping
  ordinary phone output locked down,
- allows minimal Android VPN bootstrap DNS/connectivity from the router phone so
  a provider can recover from a dead tunnel without opening hotspot-client
  mobile fallback,
- health-checks the candidate VPN interface before treating router protection as
  complete,
- attempts VirtuVPN-managed fallback when the candidate tunnel fails health,
- removes duplicate router chain jumps before re-attaching chains.

## UI

Home keeps the compact router enable/disable control and links to the dedicated
VPN Router page.

The VPN Router page shows:

- router status,
- detected uplink,
- router protection status,
- VirtuVPN app download/pairing QR code for connected devices,
- router DNS options.

When router protection is active, hotspot clients stay associated with WiFi but
only receive internet through the VPN tunnel. If the tunnel path is unavailable,
client internet stops instead of falling back to the phone uplink. The router
phone itself is also locked down so ordinary phone traffic cannot use mobile data
outside the VPN while router mode is enabled.

## Implementation Phases

1. Detect root, VPN tunnel, hotspot interfaces, and physical uplink.
2. Install fail-closed router NAT, forwarding, and policy-routing rules.
3. Health-check the selected VPN tunnel before declaring router protection
   complete.
4. Reconcile rules when VPN, provider UID, hotspot, DNS, or uplink changes.
5. Attempt VirtuVPN-managed fallback when a new candidate tunnel fails health.
6. Apply router-only DNS behavior for hotspot clients.
7. Show VirtuVPN app download/pairing QR in the VPN Router page.
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
   - provider switch with healthy new tunnel,
   - provider switch with failed new tunnel and VirtuVPN fallback,
   - provider switch with failed new tunnel and no fallback, confirming clients
     remain fail-closed,
   - client reconnect with reused DHCP address,
   - DNS leak scans,
   - IPv6 leak scans,
   - Router page QR opens only the download/pair-key landing page,
   - regular browsing works without opening any router page.

## New device checklist

Before using a new rooted Android device as a production router:

Android/OEM hardening that is outside the app build must also be completed per
device. For the current Android 14 Samsung router profile, see
`docs/virtu-vpn-router-android14-customization.md`.

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
   - the Router page shows the VirtuVPN download/pairing QR,
   - the active router landing page provides install/update and copy pair key,
   - the landing page tells clients to use VirtuVPN Secured Browser for safe
     browsing through the router hotspot,
   - the page only provides install/update and pair-key copy actions,
   - the install/update link serves the APK from the router-local endpoint.
5. Verify router attestation proxy behavior:
   - while router mode is enabled, the root proxy uses `toybox nc -L`, not
     single-shot `nc -l`,
   - repeated samples show a stable listener on `<router-gateway>:8788`,
   - sequential and concurrent client requests to
     `/virtuvpn-router/attestation` return complete `HTTP 200` JSON bodies,
   - router-local requests to `<router-gateway>:8788` may be rejected by
     firewall rules; the supported test path is from a hotspot client through
     the downstream interface,
   - logcat does not show repeated proxy start/stop churn or repeated
     `router rules incomplete` rebuilds during steady-state browsing.
6. Verify package-update recovery:
   - install a newer APK while VPN Router is enabled,
   - confirm `ACTION_MY_PACKAGE_REPLACED` immediately requests router service
     restore,
   - confirm the app-owned listener on `127.0.0.1:8789` returns without manually
     opening VirtuVPN,
   - confirm hotspot client attestation recovers and returns signed JSON,
   - repeat with VPN Router off and confirm the receiver does not enable router
     mode.
7. Verify attestation anti-flap behavior:
   - run repeated client attestation probes while the router is under normal
     health-check/reconcile load,
   - expected result is sustained `protected:true` with no recurring
     `protected:false` or `503` bursts,
   - a real sustained rule failure must still reach `ERROR` after the verify
     failure threshold and keep clients fail-closed.
8. Verify health diagnostics:
   - with uplink internet working but the selected VPN tunnel broken, status
     should identify the selected tunnel as unhealthy,
   - with router upstream internet unavailable, status should identify upstream
     internet/mobile/ISP/captive portal as the likely problem,
   - both states must keep hotspot clients fail-closed and must not change
     router availability decisions except through the existing tunnel health
     gate.
9. Verify DNS behavior:
   - selected router resolver is used,
   - competing DoH/DoT providers are blocked,
   - UDP/443 is blocked so HTTP/3 and unknown DoH-over-QUIC fall back to TCP,
   - selected resolver family is not blocked by the DoH blocklist,
   - no mobile-provider DNS appears in repeated client scans.
10. Verify IPv6 behavior:
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
