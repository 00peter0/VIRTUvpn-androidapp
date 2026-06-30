# VirtuVPN Secure Browser

VirtuVPN Secure Browser is the in-app browser for traffic that must stay on a
protected path. It is not a general WebView wrapper. The browser must either bind
to an Android VPN network, or run on the router phone while VPN Router is active
and router OUTPUT lockdown enforces VPN egress.

## Goals

- make browser egress provider-neutral: VirtuVPN, Android VPN providers such as
  NordVPN, and the local VPN Router mode are valid protection sources,
- avoid trusting ordinary private WiFi addresses as proof of protection,
- fail closed when no protected route is available,
- reduce browser-side privacy leaks such as persistent state, trackers, WebRTC,
  and LAN probing from public pages,
- keep the UX responsive with visible loading state and explicit route status.

## Protection Model

Secure Browser allows navigation only when one of these conditions is true:

- the process successfully binds to an Android `TRANSPORT_VPN` network with
  internet capability,
- VPN Router is enabled on the router phone itself, where router OUTPUT lockdown
  restricts phone traffic to the active VPN path,
- the client is on WiFi and verifies a nonce-bound VirtuVPN Router attestation
  from the current gateway.

The browser must not treat `192.168.x.x`, `10.x.x.x`, `172.16/12`, or any other
private client address as proof that a hotspot is safe. A normal home, office, or
cafe WiFi also gives private addresses, so that signal is not trusted.

A hotspot client connected to a VirtuVPN Router is allowed only after the browser
verifies the router attestation endpoint on the current WiFi gateway. Without
that attestation, accepting ordinary private WiFi addressing would recreate the
unsafe false-positive behavior.

Router attestation:

- endpoint: `http://<wifi-gateway>:8788/virtuvpn-router/attestation`,
- trust root: a random per-router pairing secret scanned from the VPN Router
  page QR code and stored on the client device,
- pairing is imported only through in-app QR scan flows and requires explicit
  user confirmation,
- the client stores a small list of paired routers, not a single global router,
  so a guest can use more than one trusted router without silent overwrite,
- guest pairings expire after 7 days and can be removed from the blocked browser
  screen with `Forget paired routers`,
- `virtuvpn://router-pair` is not a browsable web deep link; web pages must not
  be able to silently replace the trusted router,
- client sends a random nonce,
- router responds only while VPN Router status is `ENABLED`,
- response includes router id, nonce, timestamp, protected state, and HMAC
  signature,
- client accepts only matching nonce, fresh timestamp, `protected=true`, and a
  valid signature for the paired router id.

The attestation is intentionally lightweight and local to the hotspot. It does
not rely on private IP addressing as a trust signal, and the router landing page
is limited to manual install/update and pair-key copy actions. There is no
global HMAC secret embedded in the APK; a public guest APK must not contain the
material needed to forge router attestations.

Attestation proves that the current WiFi gateway knows the paired router secret
and currently reports VPN Router enabled. It does not cryptographically prove the
full internet egress path beyond that gateway; in the intended topology the WiFi
gateway is the router and router firewall/routing rules enforce the VPN path.
If router and client clocks differ too much, the timestamp freshness check fails
closed and the browser remains blocked.
When that specific failure is detected, the blocked screen tells the user to
check date and time on both devices.

`ConnectivityManager.NetworkCallback` monitors VPN availability. When the bound
VPN network is lost or loses internet capability, the browser is locked and the
WebView is sent to `about:blank`. This replaces periodic polling and reduces the
VPN-failure window.

## VPN Binding

Before loading a page, Secure Browser finds a usable VPN network and calls
`ConnectivityManager.bindProcessToNetwork(vpnNetwork)`. This forces WebView
traffic and DNS through the VPN network instead of assuming Android's default
network is already protected.

`bindProcessToNetwork` is process-wide, so the userspace WireGuard backend needs
an explicit regression check. The Go userspace backend protects its WireGuard
transport sockets with `VpnService.protect(wgGetSocketV4/6(...))` after
`wgTurnOn()`, which keeps the tunnel UDP sockets outside the VPN even when the
browser later binds the process to the VPN network. This is expected to be safe,
but releases must verify that opening Secure Browser while a Go userspace tunnel
is active does not interrupt handshakes, roaming, or data transfer.

Provider behavior:

- VirtuVPN/WireGuard: when the app can identify running WgQuick tunnels, the UI
  shows the tunnel name.
- Other Android VPN providers: the UI shows a generic protected Android VPN
  provider state.
- VPN Router phone: when router mode is active, the UI shows the active router
  tunnel name.
- Verified VPN Router client: the UI shows a verified VPN Router state.

On pause or destroy, the browser unbinds from the VPN network.

## Egress Indicator

The browser header shows the active protection path:

- `Protected via VirtuVPN: <tunnel>`
- `Protected via Android VPN provider`
- `Protected via VPN Router: <tunnel>`
- `Protected via verified VPN Router`
- `Protected route required`

After protection is active, the browser does not automatically contact a public
IP or geo service. The header shows `Tap to check exit`; only an explicit tap
performs the short egress identity check and adds the apparent public exit
country and public IP to the header. This avoids sending VPN exit IP plus timing
metadata to third-party lookup services during normal browsing. The returned IP
literal is validated before it is used in the geo lookup URL.

The exit identity is only a trust signal for the user; it is not used as the
security decision. The security decision remains local VPN binding or router
lockdown state.

The header also shows an HTTPS-only lock badge for the current top-level page.
Because non-HTTPS top-level navigation is blocked by policy, a loaded page should
show the locked state. Before navigation or while blocked, the badge stays in the
ready state.

## WebView Hardening

Secure Browser WebView settings:

- JavaScript is enabled because modern websites require it,
- third-party cookies are disabled,
- file and content access are disabled,
- file-to-file and file-to-network access are disabled,
- mixed content is blocked,
- multiple windows are disabled,
- media autoplay requires user gesture,
- geolocation is disabled,
- Android Safe Browsing is enabled where available,
- downloads are blocked.

Safe Browsing is a malware/phishing protection tradeoff. Any Safe Browsing
lookups go through the bound VPN network.

Secure Browser sends privacy preference signals on top-level navigations:

- `DNT: 1`,
- `Sec-GPC: 1`.

At document start it also exposes `navigator.globalPrivacyControl = true`,
`navigator.doNotTrack = "1"`, and installs a `no-referrer` meta policy as early
as the WebView allows. Blocked/intercepted responses generated by the browser
include `Referrer-Policy: no-referrer` and the same GPC signal.

The browser intentionally does not proxy pass-through subresource requests just
to rewrite headers. Android WebView does not provide a safe in-place header
mutation API for those requests, and proxying them in app code would add latency
and create a larger privacy/security surface.

## URL Policy

Allowed:

- top-level `https:`,
- `wss:`,
- internal `about:` and `blob:` resources needed by WebView.

Blocked:

- public `http:`,
- `ws:`,
- top-level `data:`,
- private-address subresources when the top-level document is public.

This avoids cleartext browsing and reduces DNS rebinding or LAN probing from a
public website. Private LAN administration over cleartext is intentionally not a
Secure Browser feature; use a separate managed/admin flow for that.

## WebRTC Protection

Secure Browser disables WebRTC APIs to prevent local/private IP exposure through
STUN or peer connection candidates.

Preferred path:

- `WebViewCompat.addDocumentStartJavaScript(...)` installs the protection before
  page scripts run.

Fallback:

- runtime JavaScript injection in `onPageStarted` and `onPageFinished` for older
  WebView providers that do not support document-start scripts.

VPN binding is still the primary network protection. WebRTC blocking is
defense-in-depth for local IP privacy.

## Ephemeral Session

Secure Browser is ephemeral by default. On pause and destroy it clears:

- cookies,
- WebStorage,
- WebView cache,
- form data,
- browser history.

Android `CookieManager` is process-global. Clearing Secure Browser cookies also
clears cookies used by other in-app WebView surfaces such as Web Terminal. This
is an intentional privacy-favoring tradeoff for the ephemeral browser mode.

Bookmarks are intentionally persisted because they are explicit user state.

## Tracker And Ad Blocking

`shouldInterceptRequest` blocks common advertising and tracking hosts with a
local in-app host/suffix matcher. The list is bundled with the app, so no remote
filter dependency is fetched at runtime.

Benefits:

- fewer third-party requests,
- lower data usage,
- faster page rendering in many cases,
- less cross-site tracking surface.

This is not a full content blocker or cosmetic filter engine. It blocks network
requests by host/suffix only.

The browser tracks the number of blocked tracker/ad requests for the current
top-level page and shows the count in the header. The count resets on every new
page navigation.

## Loading UX

The browser includes:

- a thin page progress bar driven by `WebChromeClient.onProgressChanged`,
- pull-to-refresh around WebView,
- reload/back/forward controls,
- find-in-page with next/previous match navigation,
- persisted text zoom controls,
- persisted desktop-mode toggle using a desktop user-agent and wide viewport,
- long-press link actions for open, copy, and share,
- a movable navigation pad whose position is persisted,
- visible route status in the header.

Pull-to-refresh and reload are enabled only when the browser is unblocked and a
real page is loaded. When the browser locks or stops, loading progress and
refresh spinner are cleared.

Long-press link actions still respect the browser URL policy. Unsafe schemes are
not offered as trusted Secure Browser navigation targets.

## Bookmarks

Secure Browser supports quick links and user-saved bookmarks. Default bookmarks
can be hidden. Bookmark state is separate from ephemeral browser storage and is
kept across sessions.

## Activity Exposure

Secure Browser must not be a generic exported browser surface for other apps.
External apps should not be able to silently open trusted-looking Secure Browser
content with arbitrary URLs. Initial URL handling is allowed only for trusted
internal app flows.

## Router Interaction

On router clients, the VPN Router protects traffic at the router layer, so any
ordinary browser can use the router-protected hotspot path. Secure Browser on
the client device can also run when it verifies the local router attestation from
the current WiFi gateway. The VPN Router page provides a download/pairing QR for
clients that want local Secure Browser protection.

When Secure Browser is blocked because no local VPN or verified router is
available, the blocker screen shows `Pair with VPN Router`. That action opens
the QR scanner so a hotspot client can scan the pairing QR shown on the router
phone. The import still requires explicit confirmation before the per-router
secret is stored. The same blocked screen also exposes `Forget paired routers`
so guest devices can remove old trust without navigating through hidden settings.

On the router phone itself, Secure Browser may run while VPN Router is enabled
because router OUTPUT lockdown prevents normal phone traffic from bypassing the
VPN. The browser still shows the router tunnel in the egress header.

## Known Limits

- The egress country/IP lookup is on-demand and informational. It is not a
  security control.
- Android Safe Browsing may contact Google Safe Browsing infrastructure through
  the VPN.
- Secure Browser cookie cleanup is process-global and may sign out other in-app
  WebView features such as Web Terminal.
- Host-based tracker blocking is best-effort and does not replace a full
  extension-grade content blocker.
- WebRTC protection depends on WebView behavior; document-start injection is the
  preferred path, with runtime injection as compatibility fallback.
- A malicious website may still fingerprint browser/device characteristics not
  controlled by this feature.

## Release Checklist

For every Secure Browser release:

- run `./gradlew :ui:testVcsinstallUnitTest :ui:assembleVcsinstall`,
- verify navigation is blocked with no VPN and no active VPN Router,
- verify a hotspot client without local VPN is blocked when attestation is not
  reachable or invalid,
- verify a hotspot client without local VPN is allowed when attestation is valid,
- verify navigation works with VirtuVPN active,
- verify Go userspace VirtuVPN remains connected while Secure Browser is open
  and the process is bound to the VPN network,
- verify navigation works with a third-party Android VPN provider active,
- verify VPN loss locks the browser and clears the loaded page,
- verify the header shows protection path and only fetches exit identity after
  an explicit tap,
- verify WebRTC leak tests do not expose local IP candidates,
- verify cookies/cache/history are cleared after leaving the browser,
- verify tracker/ad blocking does not break basic navigation,
- verify the HTTPS-only badge and blocked tracker count update per page,
- verify top-level requests carry DNT/GPC privacy headers and intercepted
  responses include the no-referrer policy,
- verify progress bar and pull-to-refresh do not remain stuck after page load or
  browser lock,
- verify find-in-page, text zoom, desktop mode, and long-press link actions work
  without bypassing URL policy.
