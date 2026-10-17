# Single server access — 17 October 2026

Home includes **Single server** directly below **VPN Mesh**, protected by the
existing VCS sign-in gate. Build 910 opens its dedicated
`TUNNEL_SECTION_SINGLE_SERVER` filter. Connections remains available in the Home
overflow menu for existing Local PC/GPU profiles. S2S Cluster, VPN Cluster and
VPN Mesh retain their existing sections.

The backend's `single_server` capability reuses `local_pc_cli` delivery and the
dedicated `single_server` presentation mode. Previously stored `standalone_pc`
grants are recognized from the explicit connection capability on sync, without
changing their key, address, local name or configuration version. A bundled
Single server remains in VPN Cluster instead of appearing twice. Device
identity, WireGuard transport, tabs, scroll, detach, End session and Secured
Browser privacy controls are unchanged. No local tunnel is deleted or
reconfigured merely by opening a section or reclassifying a grant.

The Single server list offers **Open terminal** when the authorized assignment
contains a terminal link. It uses the same launcher as tunnel details: activate
the required assigned tunnel, then open the existing private terminal activity.
A standalone Single server must use its own local tunnel, never a global fallback
to an unrelated VPN cluster. Explicit bundled terminal paths and legacy S2S
fallback behavior are preserved. The terminal activity's URL/privacy policy is
unchanged. Terminal installation remains in VCS Managed Access.

An optional VPN cluster combines one VPN Mesh route with the standalone server
connection in one WireGuard configuration. Internet defaults remain on the mesh
peer; the private terminal route remains on the server peer. Existing S2S
clusters retain their backend policy and UI. The app already reads terminal
metadata generically for both direct and bundled assignments.

Build 909 introduced the compatibility navigation entry; build 910
(`1.0.20261017.371-vcsinstall`) separates the profile and adds the list shortcut.
Use the normal production `build.sh` signing/package/unit/lint gates and
publish APK, output metadata and integrity manifest together. This release does
not install anything on a physical phone/tablet automatically. Hardware VPN and
terminal acceptance must be reported separately from compilation and unit tests.
