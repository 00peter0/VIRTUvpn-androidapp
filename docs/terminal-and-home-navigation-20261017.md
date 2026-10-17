# Terminal access and Home navigation (versions 917–920)

Pending next APK after 920: the independent tunnel detail exposes Delete as the
existing trash icon directly in the toolbar instead of hiding it in the
three-dot overflow. The action still opens the existing confirmation dialog and
remains limited to independent profiles. Published APK 920 is unchanged.

The same pending UI update keeps the existing VPN Mesh and VPN Router icons on
Home. Single server, S2S Cluster and Other tunnels replace the repeated device
symbol with one shared terminal icon. VPN Cluster uses a terminal window with a
globe badge to represent infrastructure access together with internet VPN. These
are vector resources; card sizes, order, labels, routing and actions are unchanged.

## Direct terminal access

All tunnel list sections now use the same direct **Open terminal** action that
was previously available only in Single server. The action appears only for a
local profile authorized for the current managed device and having a terminal
in its current server-delivered assignments. S2S Cluster, VPN Cluster,
Other tunnels, VPN Mesh and generic lists reuse the existing terminal resolver and
launcher; a section name does not grant a terminal to a profile without one.

The launcher retains account authorization and activates the required assigned
tunnel through the shared manager. This change does not invent terminal URLs,
change WireGuard configuration, change address pools, install server components
or end persistent shell sessions.

## Internet diagnostics

The public IP/location card and the DNS/WebRTC check through Secured Browser
are shown for profiles in VPN Mesh or VPN Cluster and independent third-party
profiles. Third-party configurations may route only a private subnet, so these
checks describe the actual current connection rather than certify VPN coverage. Private Single server,
S2S and assigned private Other tunnels connections do not display these cards and do not initiate
the public identity HTTP probes. DNS and routing in the actual tunnel config
remain unchanged. The check continues to describe Secured Browser's evidence
observed through the current browser connection; it does not certify every
application on Android. The version 918 wording clarifies that dnscheck.tools
shows public IPv4/IPv6 and DNS resolvers, and that Secured Browser blocks WebRTC
address exposure. The previous blanket phrase about testing only Secured Browser
was unclear and is removed from English, German, French and Italian resources.

Private server/infrastructure profiles have a compact information icon explaining
split routing: public internet IPv4/IPv6 still uses the phone's Wi-Fi/mobile
connection. Secured Browser blocks WebRTC, but it does not create an internet VPN
route. VPN Mesh or VPN Cluster with an internet exit is required for that purpose.

The existing assignment-to-section mapping determines these capabilities.
Managed device ownership is checked first, including in generic lists. Pending
public identity jobs are cancelled when the selection changes or the detail
view is destroyed, and stale results cannot update the next profile's detail.

## Home overflow menu

The last three Home cards, **Enroll device**, **Sync now** and **Check
updates**, move into the three-dot menu. Their existing
enrollment, authentication, sync and update flows are reused. Sync now is
disabled while a managed sync is in progress. The signed-out actions continue
to use their existing sign-in prompts. Secured Browser and the independent VPN
Router remain available without a VCS account.

**Other tunnels** is the existing standalone agent-gateway/PC/GPU profile section,
not another unfiltered list of all local tunnels. Its profile filtering is
unchanged. From version 919, it is the last Home card, below S2S Cluster and
immediately above the version number. It replaces the previous Connections menu
entry, and its destination screen uses the same Other tunnels title. The card
uses the same styling as the other tunnel cards. Independent third-party VPNs
work without a VCS account; assigned VCS profiles retain their account boundary. Home
keeps VPN Mesh, Single server, VPN Cluster and S2S Cluster above it.

The Home overflow was also inheriting a light popup theme, producing a white
Connections box over the dark application. The shared action-bar popup theme now
uses the existing dark gradient, rounded border, readable light text and muted
disabled states. This styling also covers the newly moved overflow actions.

## Contextual information audit

`widget.VcsInfoButton` is the shared help control for XML and dynamically built
sections. It uses the existing VCS message dialog, a 48 dp touch target,
accessible information labels and tooltips compatible with older supported
Android versions. Help clicks only open explanatory text; they do not change a
setting, collect a measurement or expand/collapse the containing section.
Long messages scroll inside the shared dialog while its action buttons stay
available; the same layout remains suitable for larger Android font settings.
New help should reuse this control (`app:infoTitle` and `app:infoMessage`) instead
of introducing another popup or repeating a long paragraph on the main screen.

Phone acceptance also found that the Router root did not consume system/action-bar
insets. Its overview help could sit under the action bar on Android API 37.
Version 918 makes the Router root fit system windows, matching the existing Home
and main tunnel screens. This changes layout only. Android documents inset
handling for [edge-to-edge views](https://developer.android.com/develop/ui/views/layout/edge-to-edge)
and [action-bar overlays](https://developer.android.com/reference/androidx/core/view/WindowCompat#FEATURE_ACTION_BAR_OVERLAY).

| Area reviewed | Information moved to contextual help | Kept directly visible |
| --- | --- | --- |
| Home | Secured Browser / protection explanation | VPN state, Android protection state, hotspot warning, main actions |
| Tunnel details | Private connection IPv4/IPv6 explanation; DNS/WebRTC test scope | Connection state, endpoint, terminal action, DNS measurement and test action on internet profiles |
| VPN Router | Overview, uplink preference, protection mechanism, client pairing explanation, DNS scope | Current statuses, QR/action, selectors, Strict/Nested DNS filtering warning |
| Device Diagnostics | Privacy/report sharing; CPU/RAM interpretation; storage scope; app measurement denominators and limitations; startup detection scope | Measurements, unavailable/restricted states, observation times, partial/failed results, Usage access explanation before permission, actionable security warnings |
| Secured Browser | Existing compact controls and explanatory dialogs already cover their context | Blocked-page reason, protection state and controls remain visible |
| Web Terminal | Existing compact toolbar and actions require no extra paragraph | Required-tunnel blocking reason and connection state remain visible |
| Account, enrollment and updater | Existing confirmation dialogs and short field hints retained | Consent, sign-in errors, installation progress, system approval and recovery errors remain visible |

Disabled TV entry points remain unchanged. The native editor is reused for
independent Other tunnels profiles from version 919. No VPN routing,
security setting, Android permission, measurement collector, browser policy or
server component is changed by moving explanatory text.

## Validation

Build, lint, existing unit tests and physical phone checks are recorded in
`/srv/vcs/deploy/evidence/virtuvpn-navigation-917-20261017`. Phone acceptance
checks the overflow actions, the private Single server detail, the internet
VPN Mesh detail, and opening the current account's terminal. The existing
negative account-isolation instrumentation also checks that a foreign retained
profile cannot offer account-scoped internet diagnostics.

### Final phone acceptance: version 918

The published APK is built from `5782c6f46b48aef1b091064a4718cf2c2bb363cd`,
SHA-256 `e50aef7b874f8c0d1320bc15f795007badfc6a2542e50eebee4f14c1ec020284`.
The signed in-place phone update preserved the application UID, original install
time and account data. The tablet was not updated in this task.

- 98 JVM tests passed; lint reported 0 errors, 13 existing warnings and no new
  warnings. CI run `39051696238` succeeded.
- Version 917 completed 20 physical navigation checks and exposed the Router
  overview overlap described above. Version 918 then passed five targeted checks:
  the overview information control is visible and opens, the bottom Router DNS
  help is reachable, and the DNS dialog explains public IP/DNS measurements and
  WebRTC protection using the new wording.
- The version 917 physical security test allowed all 430 allocated terminal
  gateway exceptions while rejecting arbitrary cleartext destinations. Secured
  Browser independently rejected cleartext, including platform loopback
  exceptions. Four retained foreign profiles were rejected for activation,
  direct/cached configuration reads, export inputs and account-scoped internet
  diagnostics; the two current-account profiles remained available. Version 918
  changes only resources, layout, release metadata and this document, so the
  tested Kotlin, Java, native runtime and network policies are unchanged.
- The current account's existing Terminal 1 connected successfully. The harmless
  commands `echo VCS_WEB_TERMINAL_918_OK` and `uptime` returned visible output;
  earlier version 916 history remained present. The server shell was left open.
- Read-only identity checks found 20 device records, four active records, no new
  devices and unchanged canonical/installation IDs and assignments.
- Publication and HTTP serving checks verified the exact APK bytes. VCS App,
  terminal gateway and Rust processes/configuration were preserved. Owned phone
  transfer files and the temporary UI dump were removed after acceptance.

Final evidence: `/srv/vcs/deploy/evidence/virtuvpn-dns-wording-918-20261017`.
Earlier navigation and negative security evidence remains in the version 917
directory above. The physical checks cover the profiles available to this
account; other assigned-profile shortcut sections use the shared implementation
and were not separately connected on the phone.

## Independent WireGuard VPNs in Other tunnels (919)

The existing round logo button is visually unchanged. In Other tunnels it opens
a shared dark VCS choice dialog with all three actions: create manually, import a configuration file (.conf,
.zip or QR image), or scan a QR code. The rest of the managed sections retain
their existing synchronization button. No second WireGuard engine is added.
The provider must supply WireGuard configuration; provider apps, password-only
sign-ins and OpenVPN files are not supported by this flow.

Independently created profiles use the existing encrypted private configuration
store on the device. They do not require VCS registration, are not VCS server
assignments and are not synchronized or backed up by VCS. Import never connects
a profile automatically. Their routes, DNS and supported application exclusions
are retained by the native parser/editor. Users can edit and rename them and
explicitly delete them from their detail menu, with confirmation. Deleting an
active local profile disconnects it; it does not revoke a provider subscription.

The Other tunnels header has shared contextual information describing these
limits. Assigned VCS connections can still appear here for the signed-in owner;
these retain their terminal metadata and cannot be manually deleted. A signed-out
Other tunnels screen shows only independent local profiles, including after
activity restoration. Managed sections continue to require account sign-in.

Independent status derives from the existing fail-closed ownership ledger: a
profile owned by any managed device, including the current one, is never a local
manual-delete target. Native creation and deletion recheck that boundary under
the ownership lock at the actual file mutation. Managed sync reserves its name
under the same lock and cannot claim an existing independent configuration; it
uses the existing device suffix fallback or reports a conflict without replacing
that profile. Independent renaming cannot take a managed reserved name.

The choice dialog reuses `VcsDialogs.showChoice`, including its dark rounded
controls, rather than the retired light native bottom sheet. The former sheet
class and layout were removed once unreferenced. The explanatory text names the
round logo instead of a plus symbol. VPN Router's existing Add tunnel action
also opens these choices in Other tunnels; its pairing action is unchanged.

## Permissions for the requested action (920 — current)

Permission requests reuse `PermissionsActivity` in a focused mode: only the
requested capability, a short reason, its observed status, one action and the
shared information control. A QR scan asks for camera only. Usage diagnostics,
APK installation, initial VPN consent, legacy download storage and Quick Settings
overlay access each keep their own request. No unrelated rows or bulk runtime
request are shown. Unsupported or unknown capability requests close without a
grant; they never fall back to the complete overview.

Camera, notifications and legacy storage use a single Android runtime request
for that selected capability. If Android no longer offers a prompt, or camera
AppOps still blocks an otherwise granted permission, the action opens Android
settings. Actual access is rechecked on return; a successful settings activity
result alone does not count as permission. A granted request returns to its
original caller without a separate Continue step; continuation remains handled
by that feature’s existing result callback. Denial and cancellation do not
start the protected action or request another permission.

The full overview is available only when explicitly opened from Home →
Permissions. It retains individual management actions without an “allow all”
button. Kill switch and background restrictions remain optional settings, with
no automatic enabling or routing changes. Managing an existing diagnostic usage
grant uses focused management mode, so it stays open instead of immediately
returning as an already completed request. Import on current Android versions
retains the system file picker and scoped storage without broad storage grants.

Secured Browser website camera/microphone denial, WebRTC protection, managed
profile ownership, VPN routing, terminal sessions and updater verification are
unchanged. Contextual permission guidance follows Android's
[runtime permission workflow](https://developer.android.com/training/permissions/requesting).

Verification and release evidence:
`/srv/vcs/deploy/evidence/virtuvpn-context-permissions-920-20261017`.
The dated 919 results below describe that release's previous behavior and are
superseded by the focused flow above.


### Final phone acceptance: version 920

Published source `ebaf3ce2ac04eefb11c6b9b870f5e6d4445f2946`, APK SHA-256
`80c0fa7639421aaf07125d1e0c3c948e01e98352fcd3462bf73a17cd537e849d`.

- 108 JVM tests passed. Lint: 0 errors, the same 13 existing warnings, no new
  warnings. CI run `39056962524` succeeded.
- Native checks exercised all nine focused permission screens, the explicit
  overview, cancellation and blocked camera behavior. Opening/cancelling screens
  changed neither runtime grants nor request history. Existing granted usage
  access remained manageable.
- Thirteen physical UI checks passed. QR scanning showed only camera access and
  its short purpose, with no bulk request, unrelated rows or Continue. Its action
  opened the actual Android camera permission prompt. Cancelling without a grant
  returned to Other tunnels without opening the scanner. The explicit Home
  overview retained individual actions, Kill switch guidance and real Android VPN
  settings; modern scoped files needed no broad storage grant.
- Final-APK import, managed ownership and network-policy checks passed. Original
  profile files, account identity and active VPN were preserved. Read-only DB
  evidence found the same 20 device records, four active records, no new devices
  and unchanged installation/device IDs and assignments.
- Production signing, APK bytes, UID and first install time were verified. The
  phone was updated without clearing data; the tablet was not touched. Owned
  test/transfer files were removed. App, Gateway, Rust and production environment
  were preserved; publishing the APK required no app restart.
- Automation did not accept a new permission grant or change optional protection
  settings. Live camera decoding and a newly accepted grant/resume were not
  physically exercised. UI evidence is from the API 37 phone; API 24–37 capability
  and runtime-request selection are unit tested. Quick Settings overlay access
  retains the existing subsequent-tap behavior after returning from settings.

Evidence: `/srv/vcs/deploy/evidence/virtuvpn-context-permissions-920-20261017`.

## Permissions and protection overview (919 — historical)

Home overflow now exposes **Permissions** without requiring a VCS account.
Camera QR scans, the Secured Browser native router-pairing scanner, initial VPN
consent from the list/Home/quick tile, app-usage access, APK-source installation
access, Quick Settings overlay access and legacy download storage requests open
the same overview. Existing action-specific system confirmations and grant
checks remain mandatory. Secured Browser website camera/microphone denial and
WebRTC protection are unchanged.

The overview lists VPN consent, Kill switch (Always-on and Block connections
without VPN), native camera QR access, notifications, APK installation, diagnostic
usage access, Quick Settings overlay access where applicable, files and background
activity. Each row explains its purpose through the shared information control
and opens the relevant Android screen. Camera help explicitly points to App
settings → Permissions → Camera and the Android Camera access privacy switch.
After enabling the requested access, Continue returns to the requesting feature;
only that actual grant is required. Back/cancellation does not claim a grant.

**Allow app permissions** explicitly requests the supported runtime group:
camera when hardware is present, notifications on API 33+, and legacy write
storage only on API 24–28. Special access and Kill switch are never part of this
group and still require their separate Android settings. Opening the overview
grants nothing and changes no VPN, route, installation, battery exemption or
managed identity. Current access is re-read on return from settings; unavailable
Kill switch observations are labelled as requiring a check in Android settings.
Modern file import/download uses the existing scoped mechanisms and does not
request broad storage access.

Android reference: [permission and app-op checking](https://developer.android.com/reference/androidx/core/content/PermissionChecker)
and [application settings intents](https://developer.android.com/reference/android/provider/Settings#ACTION_APPLICATION_DETAILS_SETTINGS).

The first permission overview opens at the top so the grouped runtime action and
requested-feature label remain visible. Continue stays visibly disabled until
the requested actual access is observed; unrelated optional access is not a
condition for continuing.

### Final phone acceptance: version 919

Published source `e3e1becb0a197f1896957e8503e0497302dc434c`, APK SHA-256
`ecce3bd69f5618034100b3a63aca32bd184c917af1ecb93c1f4337ef4b118831`.

- 107 JVM tests passed. Lint: 0 errors, 13 existing warnings, no new warnings.
  CI run `39055460481` succeeded.
- Seventeen final phone UI checks passed: Home placement, centered message,
  styled choices, native editor navigation, blocked-camera permission guidance,
  real Android app/VPN settings navigation, return/cancel behavior, grouped
  action visibility, Kill switch guidance and scoped file access.
- Final-APK instrumentation verified encrypted inactive local creation, exact
  canonical configuration preservation through actual content:// file import
  and QR-image decoding, duplicate-name rejection and local deletion. All
  original profile files and the active VPN were preserved. Managed profiles
  rejected manual deletion; four foreign retained profiles rejected activation,
  configuration reads and internet diagnostics. All 430 terminal gateway
  network exceptions and the negative browser policy checks passed.
- Camera access remained blocked by Android. Live camera decoding and accepting
  the bulk runtime prompts were not performed; guidance, settings navigation and
  blocked/cancelled continuation were verified without changing those settings.
  No real third-party provider VPN was connected. Earlier manual editor creation
  and confirmed/cancelled deletion evidence remains available from an unpublished
  919 candidate; its partial navigation report is retained as partial.
- UID and original install time were preserved. Read-only identity checks found
  20 device records, four active records, unchanged IDs/assignments and no new
  devices. The tablet was not updated. Owned test/transfer files were removed.
- Publication and authenticated-route guards passed with App, Gateway, Rust and
  production configuration preserved. No production app restart was required.

Evidence: `/srv/vcs/deploy/evidence/virtuvpn-other-tunnels-919-20261017`.
Physical testing used the API 37 phone. Older runtime-permission group selection
is unit tested; this does not claim physical acceptance on every older Android.
