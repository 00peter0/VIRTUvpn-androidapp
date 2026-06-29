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
  restricts phone traffic to the active VPN path.

The browser must not treat `192.168.x.x`, `10.x.x.x`, `172.16/12`, or any other
private client address as proof that a hotspot is safe. A normal home, office, or
cafe WiFi also gives private addresses, so that signal is not trusted.

`ConnectivityManager.NetworkCallback` monitors VPN availability. When the bound
VPN network is lost or loses internet capability, the browser is locked and the
WebView is sent to `about:blank`. This replaces periodic polling and reduces the
VPN-failure window.

## VPN Binding

Before loading a page, Secure Browser finds a usable VPN network and calls
`ConnectivityManager.bindProcessToNetwork(vpnNetwork)`. This forces WebView
traffic and DNS through the VPN network instead of assuming Android's default
network is already protected.

Provider behavior:

- VirtuVPN/WireGuard: when the app can identify running WgQuick tunnels, the UI
  shows the tunnel name.
- Other Android VPN providers: the UI shows a generic protected Android VPN
  provider state.
- VPN Router phone: when router mode is active, the UI shows the active router
  tunnel name.

On pause or destroy, the browser unbinds from the VPN network.

## Egress Indicator

The browser header shows the active protection path:

- `Protected via VirtuVPN: <tunnel>`
- `Protected via Android VPN provider`
- `Protected via VPN Router: <tunnel>`
- `Protected route required`

After protection is active, the browser performs a short egress identity check
without delaying navigation. It adds the apparent public exit country and public
IP to the header. This is a trust signal for the user; it is not used as the
security decision. The security decision remains local VPN binding or router
lockdown state.

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

## Loading UX

The browser includes:

- a thin page progress bar driven by `WebChromeClient.onProgressChanged`,
- pull-to-refresh around WebView,
- reload/back/forward controls,
- a movable navigation pad whose position is persisted,
- visible route status in the header.

Pull-to-refresh and reload are enabled only when the browser is unblocked and a
real page is loaded. When the browser locks or stops, loading progress and
refresh spinner are cleared.

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

On router clients, the safest path is to install VirtuVPN on the client device
and use Secure Browser locally. The VPN Router page provides the download link
and QR code.

On the router phone itself, Secure Browser may run while VPN Router is enabled
because router OUTPUT lockdown prevents normal phone traffic from bypassing the
VPN. The browser still shows the router tunnel in the egress header.

## Known Limits

- The egress country/IP lookup is informational. It is not a security control.
- Android Safe Browsing may contact Google Safe Browsing infrastructure through
  the VPN.
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
- verify navigation works with VirtuVPN active,
- verify navigation works with a third-party Android VPN provider active,
- verify VPN loss locks the browser and clears the loaded page,
- verify the header shows protection path and then exit identity,
- verify WebRTC leak tests do not expose local IP candidates,
- verify cookies/cache/history are cleared after leaving the browser,
- verify tracker/ad blocking does not break basic navigation,
- verify progress bar and pull-to-refresh do not remain stuck after page load or
  browser lock.
