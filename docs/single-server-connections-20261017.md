# Single server access — 17 October 2026

Home includes **Single server**, protected by the existing VCS sign-in gate.
It opens the existing `TUNNEL_SECTION_AGENT_GATEWAY` (Connections) filter. This
exposes assigned standalone server connections without requiring an S2S cluster.
The generic filter also retains existing non-S2S connection profiles.

The backend's `single_server` capability reuses `local_pc_cli` delivery and the
existing `standalone_pc` compatibility mode. Sync, device identity, WireGuard
transport, tunnel activation, WebTerminalBrowserActivity, tabs, scroll, detach,
End session and Secured Browser privacy controls are unchanged. No local tunnel
is deleted or reconfigured merely by opening the new Home entry.

An optional VPN cluster combines one VPN Mesh route with the standalone server
connection in one WireGuard configuration. Internet defaults remain on the mesh
peer; the private terminal route remains on the server peer. Existing S2S
clusters retain their backend policy and UI. The app already reads terminal
metadata generically for both direct and bundled assignments.

Build 909 (`1.0.20261017.370-vcsinstall`) changes navigation/resources and version
only. Use the normal production `build.sh` signing/package/unit/lint gates and
publish APK, output metadata and integrity manifest together. This release does
not install anything on a physical phone/tablet automatically. Hardware VPN and
terminal acceptance must be reported separately from compilation and unit tests.
