# Terminal access and Home navigation (version 917)

## Direct terminal access

All tunnel list sections now use the same direct **Open terminal** action that
was previously available only in Single server. The action appears only for a
local profile authorized for the current managed device and having a terminal
in its current server-delivered assignments. S2S Cluster, VPN Cluster,
Connections, VPN Mesh and generic lists reuse the existing terminal resolver and
launcher; a section name does not grant a terminal to a profile without one.

The launcher retains account authorization and activates the required assigned
tunnel through the shared manager. This change does not invent terminal URLs,
change WireGuard configuration, change address pools, install server components
or end persistent shell sessions.

## Internet diagnostics

The public IP/location card and the DNS/WebRTC check through Secured Browser
are shown only for profiles in VPN Mesh or VPN Cluster. Private Single server,
S2S and standalone Connections do not display these cards and do not initiate
the public identity HTTP probes. DNS and routing in the actual tunnel config
remain unchanged. The check continues to describe Secured Browser's evidence
only; it does not certify every browser or application on Android.

Private server/infrastructure profiles have a compact information icon explaining
split routing: public internet IPv4/IPv6 still uses the phone's Wi-Fi/mobile
connection. Secured Browser blocks WebRTC, but it does not create an internet VPN
route. VPN Mesh or VPN Cluster with an internet exit is required for that purpose.

The existing assignment-to-section mapping determines these capabilities.
Managed device ownership is checked first, including in generic lists. Pending
public identity jobs are cancelled when the selection changes or the detail
view is destroyed, and stale results cannot update the next profile's detail.

## Home overflow menu

The last three Home cards, **Enroll device**, **Synchronize** and **Check
updates**, move into the three-dot menu beside **Connections**. Their existing
enrollment, authentication, sync and update flows are reused. Synchronize is
disabled while a managed sync is in progress. The signed-out actions continue
to use their existing sign-in prompts. Secured Browser and the independent VPN
Router remain available without a VCS account.

Connections is the existing standalone agent-gateway/PC/GPU profile section,
not another unfiltered list of all local tunnels. Its profile filtering is
unchanged. Home keeps VPN Mesh, Single server, VPN Cluster and S2S Cluster as
the main navigation, with the version number at the bottom.

## Validation

Build, lint, existing unit tests and physical phone checks are recorded in
`/srv/vcs/deploy/evidence/virtuvpn-navigation-917-20261017`. Phone acceptance
checks the overflow actions, the private Single server detail, the internet
VPN Mesh detail, and opening the current account's terminal. The existing
negative account-isolation instrumentation also checks that a foreign retained
profile cannot offer account-scoped internet diagnostics.
