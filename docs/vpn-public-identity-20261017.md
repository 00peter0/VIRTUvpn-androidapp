# VPN public identity card — 17 October 2026

## Problem and scope

The three-device Canada test passed for separate client keys, tunnel traffic and
Secured Browser IPv4 egress (51.222.7.179). The native IP card nevertheless showed
the Swiss physical connection on two devices. It used URL.openConnection without
selecting the VPN network; the browser explicitly selected its protected network.
The missing network selection is a confirmed code defect. The precise contribution
of Android default-network selection or existing HTTP connections was not isolated.

Release 924 changes only the card measurement and its lifecycle. VPN configuration,
WireGuard/backend, server routes, account/device identity, managed sync, Android
permissions, terminal access and Secured Browser protection are unchanged.

## Measurement contract

- Require the selected tunnel to be UP and eligible for internet diagnostics.
- Match its configured interface addresses to exactly one Android VPN network with
  internet capability and a default route. Never select a physical uplink or an
  unrelated VPN merely because one exists.
- Bind each HTTPS request to that captured Network using Network.openConnection.
  Do not bind the entire app process and do not fall back to the default connection.
- Probe IPv4 and IPv6 separately, only if that family has a VPN default route.
  Validate returned IP literals before displaying or requesting geolocation.
- Disable HTTP caching, redirect following and connection reuse for these bounded
  checks; disconnect every connection. Maximum response: 8 KiB; connect/read timeout:
  6 seconds each. Failed geolocation does not hide an observed IP.
- Clear the card on pause, selection/state/config changes and network loss. Refresh
  on resume, matching network changes or explicit tap; do not continuously poll.
- A generation, exact tunnel/config identity and complete network snapshot guard
  every request boundary and final publication. A delayed result from a previous
  check must never restore an obsolete IP or location, including a same-name tunnel.
- Missing network/failed measurement displays “VPN IP not confirmed”; IPv6 failure
  displays “IPv6 not confirmed”. Neither means proof of absence of a leak elsewhere.
- The shared i control explains that this is a VPN-scoped observation, approximate
  IP geolocation and no device-wide DNS/IPv6 privacy guarantee.

## Verification

Ten new JVM regressions cover invalid echo bodies, no active VPN, no IPv6 route,
network/tunnel change during each stage, stale geolocation, cancellation and fresh
retry after success or failure. The initial run passed 125/125 total tests.
Final build/lint, signed APK integrity, release publication and physical results
are recorded separately after verification; source changes alone are not a claim
of an installed or accepted release.

Evidence: /srv/vcs/deploy/evidence/virtuvpn-ip-identity-924-20261017.
Baseline three-device evidence: /srv/vcs/deploy/evidence/vcs-vpn-mesh-canada-three-devices-20261017.

References: [Android Network.openConnection](https://developer.android.com/reference/android/net/Network#openConnection(java.net.URL)),
[network callback lifecycle and ordering](https://developer.android.com/reference/android/net/ConnectivityManager.NetworkCallback).
