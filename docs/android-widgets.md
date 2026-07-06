# Android Widgets

This document is the single source of truth for VirtuVPN Android home-screen
widgets. Keep widget implementation, launcher caching notes, and release checks
here instead of duplicating them in the router or browser documents.

Widgets are control and status surfaces only. They are not security boundaries:

- VPN Router security is enforced by the root routing and firewall rules in
  `VpnRouterManager`.
- Secured Browser protection is enforced inside `SecureBrowserActivity`.

## Current Widgets

VirtuVPN currently exposes two Android home-screen widgets.

### VPN Router Widget

Purpose: show the protected router path and provide quick router actions on a
dedicated Android router device.

Files:

- provider: `ui/src/main/java/com/wireguard/android/widget/VpnRouterWidgetProvider.kt`
- provider XML: `ui/src/main/res/xml/widget_vpn_router_path.xml`
- live layout: `ui/src/main/res/layout/widget_vpn_router_path.xml`
- fallback preview: `ui/src/main/res/drawable/widget_vpn_router_preview.xml`
- router mark: `ui/src/main/res/drawable-nodpi/widget_vpn_router_mark.png`

Provider configuration:

- `@layout/widget_vpn_router_path` is the live `initialLayout`.
- `@layout/widget_vpn_router_path` is `android:previewLayout`.
- `@drawable/widget_vpn_router_preview` is the fallback `android:previewImage`.
- target size is `4x3`.

Runtime behavior:

- Root card and primary button send the static
  `com.virtuvpn.android.widget.VPN_ROUTER_TOGGLE` broadcast.
- Router logo and status pill send the status refresh broadcast.
- `Pair` opens the VPN Router client page in `VpnRouterActivity`.
- `Browser` opens `SecureBrowserActivity`.
- The main status is the security invariant (`Protected`, `Ready`,
  `Start VPN`, `Hotspot off`, or `Disabled`), not a raw internet-speed signal.
- The tunnel badge is a quality signal. `Tunnel offline` can be shown while the
  router is still `Protected`; that means clients remain fail-closed and cannot
  leak to the phone uplink.
- The widget shows the active tunnel/provider, DNS mode, hotspot interfaces,
  last checked time, and a best-effort hotspot client count. Client counting
  first tries direct `/proc/net/arp` access and then falls back to the app root
  shell (`cat /proc/net/arp` plus `ip neigh show`) because some Samsung/Android
  builds expose neighbor state inconsistently to the app process. The count is
  based on unique client MAC addresses on the current tether interfaces, so IPv4
  ARP and IPv6 neighbor entries for the same device are not double-counted.
- Status rendering uses `VpnRouterManager.getStatus()` with a short timeout; if
  status cannot be read, the widget shows `Open to check` instead of blocking
  launcher rendering.
- Enabling from the widget records router intent through
  `VpnRouterManager.requestRouterActive()` and starts `VpnRouterService`. The
  foreground service performs heavy rule install/reconcile outside the Android
  broadcast timeout.
- After a toggle request, the widget refreshes immediately and then schedules
  delayed refreshes so the UI catches up when the service finishes.

Implementation rules:

1. The primary router card action must remain a stable toggle broadcast. Do not
   dynamically switch the main button between activity, status, and toggle
   pending intents. Samsung Launcher can keep stale child click handlers across
   widget refreshes and package updates.
2. Do not run full router enable synchronously inside `AppWidgetProvider`.
   Broadcast receivers have tight execution limits; router enable belongs in
   `VpnRouterService`.
3. Keep the `Pair` and `Browser` actions dedicated. They are allowed to open
   activities because they are explicit user navigation actions.
4. If a stale Samsung click handler appears after changing action wiring, change
   the affected view id and bump `wireguardVersionCode`.
5. Client count is informational only. Do not use it as a router security gate;
   fail-closed protection is enforced by routing/firewall state, not by the
   number of visible neighbor entries.

### Secured Browser Widget

Purpose: provide a 4x1 search-style launcher entry into VirtuVPN Secured
Browser.

Files:

- provider: `ui/src/main/java/com/wireguard/android/widget/SecuredBrowserWidgetProvider.kt`
- search activity: `ui/src/main/java/com/wireguard/android/activity/SecureBrowserWidgetSearchActivity.kt`
- provider XML: `ui/src/main/res/xml/widget_secured_browser_quick.xml`
- live layout: `ui/src/main/res/layout/widget_secured_browser_quick.xml`
- preview image / logo: `@drawable/shortcut_secured_browser`

Provider configuration:

- `@layout/widget_secured_browser_quick` is the live `initialLayout`.
- `@layout/widget_secured_browser_quick` is `android:previewLayout`.
- `@drawable/shortcut_secured_browser` is the fallback `android:previewImage`.
- target size is `4x1`.

Runtime behavior:

- Root card, inline search pill, and search action button open the internal
  widget search activity.
- The search activity accepts a query and launches `SecureBrowserActivity` with
  `EXTRA_INITIAL_URL=https://www.google.com/search?q=...`. Web content is still
  loaded only by Secured Browser, never by widget code.
- The widget does not decide whether browsing is protected. The browser remains
  fail-closed and performs its own VPN/router attestation checks after launch.

Implementation rules:

1. Keep the widget as a launcher entry, not as a bypass around browser
   protection.
2. Keep the widget visually close to a full-width search engine field: no
   separate logo block, one inline search field, and one inline action button on
   the right.
3. Android `RemoteViews` widgets do not provide a reliable editable text field
   inside the launcher widget itself. Use the internal search activity for text
   input, then route the confirmed query through `SecureBrowserActivity` so the
   existing browser gate still owns navigation.

## Launcher Preview And Branding Rules

On Android 12+ launchers, including Samsung One UI, `previewLayout` is the most
reliable way to make the widget picker render the same shape and branding as the
live widget. `previewImage` is only a fallback for launchers that do not support
layout previews, and it may be scaled or cached differently by OEM launchers.

When changing widget branding:

1. Update the live layout and preview path together.
2. Keep `previewLayout` pointed at the live widget layout unless the preview
   must intentionally differ.
3. Keep `previewImage` as a fallback, but do not rely on it as the primary
   Samsung/Android 12+ picker rendering path.
4. Bump `wireguardVersionCode`; Samsung Launcher can keep old widget resources
   cached after `adb install -r`.
5. After installing a new build on a router, force-stop/restart Samsung Launcher
   or reboot the device if the widget picker still shows an old preview.

For the current router widget artwork, the source asset came from
`router-widget.png` on the `vcs-llm` workstation. The committed Android asset is
the cropped standalone router mark in
`ui/src/main/res/drawable-nodpi/widget_vpn_router_mark.png`.

The live VPN Router widget must render the router mark directly on the widget
card, without an extra circular badge behind it and without a visible square
bitmap background. The PNG should keep only the illuminated router mark on
transparent alpha. If the source logo contains a generated dark card or square
backdrop, remove/key out that backdrop in the committed widget asset instead of
masking it with another badge layer. This keeps the widget visually consistent
with the launcher icon while avoiding the raw-square look that Samsung Launcher
makes very visible in compact widget previews.

Widget action controls must use state-list drawables with a visible pressed
state. The primary action uses `@drawable/secured_browser_widget_button`, which
delegates to the raised 3D primary button layers. Secondary actions use
`@drawable/widget_button_secondary`. Do not reuse these drawables for passive
status pills, tunnel badges, counters, warnings, or search fields; those should
keep `@drawable/secure_browser_url_bar_background` so users can tell which
parts of the widget are interactive. This is especially important on Samsung
Launcher, where home-screen widgets otherwise provide weak touch feedback.

## Release Checklist

Use this checklist whenever widget code, widget XML, layout, preview, strings,
or artwork changes:

1. Bump `wireguardVersionCode` so launcher resource caches see a new package
   version.
2. Run the normal Android gate:

   ```bash
   ./gradlew :ui:testVcsinstallUnitTest :ui:lintVcsinstall :ui:assembleVcsinstall
   ```

3. Install the build on a real Samsung device before calling the widget done.
4. Re-add or refresh the widget from the launcher picker after install.
5. For the VPN Router widget, verify that tapping the card/primary button logs
   `VPN_ROUTER_TOGGLE` and does not open `VpnRouterActivity` directly.
6. Verify that the router widget can request enable without launcher ANR and
   that the visible status catches up after the service finishes.
7. Verify that the Secured Browser widget opens `SecureBrowserActivity` and that
   browser protection still gates the first navigation.
