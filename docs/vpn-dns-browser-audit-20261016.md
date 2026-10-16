# Android DNS test and browser privacy audit — October 16, 2026

## Scope and decision

Improve the Android tunnel diagnostic entry point and the accuracy of its status
messages. Preserve VPN Maker, tunnel provisioning, WireGuard backends, routes,
firewall rules, DNS settings, managed synchronization and VPN Router behavior.
Secured Browser remains available without a VCS account. No third-party browser
settings are changed.

The user requested a prepared build first; installation and final acceptance of
the changed build on the phone wait for the device to be reconnected.

## Physical investigation before the change

The connected Samsung SM-S948B ran VirtuVPN version code 906 and an active
`vancouver` tunnel. Android reported IPv4 and IPv6 default routes through the VPN,
DNS `1.1.1.1`, all user UIDs covered and `bypassable=false`. Private DNS was off.

| Observation | Result and interpretation |
| --- | --- |
| IPv4 HTTP echo | The Canadian VPS public IPv4 was observed. |
| DNS resolvers | Cloudflare resolvers geolocated in Toronto, including IPv6 resolver addresses. Direct DNS queries from the VPS produced consistent resolver ownership/location. |
| Samsung Internet / dnscheck.tools | The phone's actual Swiss Wi-Fi IPv6 and private Wi-Fi, mobile and VPN interface addresses appeared in the client IP list. The exposed IPv6 matched Android's Wi-Fi link address. |
| Direct IPv6-only HTTPS echo | No response during the observation interval. No successful ordinary IPv6 HTTPS bypass was demonstrated. This does not certify every protocol or application. |
| VirtuVPN Secured Browser / same test | The client IP section showed only the Canadian VPS IPv4, without the Swiss IPv6 or private interface addresses. DNS resolvers remained in Toronto. |

The served [dnscheck.tools source](https://github.com/brianshea2/addr.tools/blob/main/website/dnscheck.tools/main.js)
combines HTTPS echo results and WebRTC ICE candidates in `Your IP addresses`.
The source, exposed local interface addresses and browser comparison support
WebRTC interface discovery as the cause of the observed Samsung Internet privacy
disclosure. This does not establish ordinary IPv6 traffic bypassing the VPN.
[RFC 8828](https://www.rfc-editor.org/rfc/rfc8828.html) describes WebRTC address
handling and the privacy implications of exposing network interfaces.

Private operational evidence, including screenshots, the Android VPN link state,
sanitized issued policy and a read-only VPS diagnostic, is under
`/srv/vcs/deploy/evidence/vcs-vpn-ipv6-discovery-20261016/`. Device IP addresses,
identifiers and raw device state are not added to Git.

## Findings and implementation

| Finding | Change |
| --- | --- |
| The DNS card delegated `ACTION_VIEW` to the default browser, which was Samsung Internet on the tested phone. | Open the fixed HTTPS test URL explicitly in Secured Browser through its existing `EXTRA_INITIAL_URL`. |
| Initial browser URL only populated the address field and did not start a test. A retained tab could still contain another page. | Consume the explicit initial URL and invoke the same protected navigation path as Go. Existing URL validation, document-start WebRTC protection and VPN/router checks stay in force. |
| Nonempty configured DNS was displayed as confirmed protected DNS. | Show `VPN DNS configured — use the test to verify` in a neutral informational color. Missing DNS requests verification instead of asserting a measured leak. |
| Any failed IPv6 echo lookup was described as `No IPv6`. | Show `IPv6 not confirmed`, without inventing a reason for the unsuccessful lookup. |
| A successful in-app test could be misunderstood as covering other browsers. | Add a visible scope explanation: the test covers Secured Browser; other browsers may expose IP addresses through WebRTC. |

English remains the primary language. German, French and Italian strings are
updated together. Removed status resource names are replaced rather than kept as
parallel legacy variants.

The app cannot change a foreign browser's JavaScript/WebRTC privacy policy as an
ordinary Android application. A network tunnel also cannot erase an address that
a browser has already read from local interfaces and supplied to page JavaScript.
Do not claim all third-party browsers leak or that redirecting this test fixes
those browsers. The tested Secured Browser protection remains the supported path
for stronger browser privacy.

## Verification and release boundary

The unchanged source baseline passed `:ui:lintVcsinstall` with 0 errors,
13 warnings and 25 warnings filtered by the existing baseline. The candidate
passed the canonical `build.sh` gate: **63 unit tests passed**, lint finished with
**0 errors and the same 13 warnings**, and there were **0 new lint findings**.
Production-package assembly, the non-debuggable manifest and the established
signing certificate check also passed. Resource checks covered all four supported
locales and confirmed removal of obsolete status keys. The WireGuard GoBackend
hash is unchanged. Results and before/after lint comparison are retained under
`/srv/vcs/deploy/evidence/virtuvpn-secured-dns-20261016/`.

Prepared candidate: version code **907**, version name
**1.0.20261016.368-vcsinstall**. The mobile update catalog and connected devices
are not updated as part of preparation. No VPN Maker or Rust release is needed.

When the phone is reconnected, verify:

1. The signed in-place update preserves enrollment, tunnel assignments and app
   data; reconnect the existing tunnel after Android replaces the application.
2. Tapping the tunnel DNS card stays in VirtuVPN and loads dnscheck.tools without
   a second Go tap, including with an existing browser session.
3. Configured DNS and inconclusive IPv6 lookup messages are accurate and the
   browser scope explanation is visible.
4. The test does not disclose the phone's original IPv6 or private interface
   addresses in Secured Browser on the same protected route.
5. Returning from the browser preserves tunnel connectivity and handshakes.

The physical comparison of browsers above was performed on version 906 before
the implementation. It must not be described as acceptance of the new 907 build.
