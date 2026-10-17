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

## Release 924 physical acceptance

APK 1.0.20261017.385-vcsinstall, source 71da18d64db869e6b5093edc9417d3209a39b445,
SHA-256 937ea18440c283c7d42224ef90163fc164f27cd0dc6fd6aa298136e3e2d771b9.
The existing signing certificate is retained. All three upgrades preserved package
UID, first-install identity, camera/notification grants and existing app data.

| Device | IP card | Secured Browser |
| --- | --- | --- |
| SM-S948B | 51.222.7.179, Canada | 51.222.7.179 |
| SM-A525F | 51.222.7.179, Canada | 51.222.7.179 |
| SM-T860 | 51.222.7.179, Canada | 51.222.7.179 |

All three client keys remain present on the same server interface. Final simultaneous
server-to-device tests received 3/3 replies per client with 0% loss. No account,
configuration, assignment, key or server firewall/routing change was made. The tablet
retains its existing managed Canada name suffix and original independent QR profile.
Owned browser test tabs were closed; existing tunnel connections were left active.

The SM-S948B reproduced the Swiss IP before upgrade and showed Canada after upgrade.
Selecting an inactive Vancouver profile cleared the old Canada identity without
switching either VPN. Returning to Canada and leaving/resuming the screen produced
a fresh correct result. IPv6 remained unconfirmed on all three devices; this does
not establish the absence of IPv6/DNS exposure in other applications.

### Recovery observation and evidence limits

After the in-place APK upgrade, the SM-A525F initially had a VPN network reported as
connected, but server ping had 100% loss and the isolated Secured Browser test failed
with ERR_NAME_NOT_RESOLVED. The new card displayed “VPN IP not confirmed” with no
physical-connection IP or old location. Reconnecting the same existing Canada tunnel
restored ping, browser IPv4 and the card. The exact cause of that interrupted tunnel
was not isolated; this release does not claim a general APK-update/reconnect repair.
The failed observations are retained separately from final successful evidence.

The first inactive-selection test attempted to tap a list item underneath the detail
overlay and did not change selection. This was a harness failure, preserved in the
evidence; the corrected test closes the overlay before selecting the inactive tunnel.
A pre-existing tablet instrumentation package was left untouched; this task installed
no instrumentation package and changed no system settings or biometric protection.

Final local gate: 125 tests passed, zero lint errors, 13 existing warnings and zero
new warnings. GitHub workflow 39073493293 passed for the exact source commit. Backend,
managed-client, browser and manifest bytes match the prior baseline; their hashes,
signed upgrade proofs and positive/negative device observations are in the evidence.

## Publication and cleanup

Release 924 was published by atomic directory exchange over vRack. Production
version/integrity readers and the exact served APK bytes matched the signed
artifact. Older 923 clients receive an update offer; 924 does not offer itself.
Unauthenticated APK/update routes still returned 401. App/Gateway/Rust, process
identities and private configuration were preserved without a production restart.
Owned APK transfers on both Macs and the local/production release staging and
temporary rollback were removed after verification. The accepted artifact and
bounded evidence remain available.
