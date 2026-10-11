# VirtuVPN Android App Security Hardening Backlog

## Unreleased compatibility cleanup — October 11, 2026

The owner requested batching repairs into larger releases rather than publishing
after every change. **Production remains version 903**; this source-only block
does not bump the Android version or replace the served APK. The local test APK
is an unpublished candidate, not another version-903 release. A future consolidated
publication must use a versionCode greater than 903 and the established signing,
package and integrity gates.

Replaced 25 call sites with equivalent AndroidX KTX spellings across 12 Kotlin
files: 19 URI parses, two transparent ColorDrawable constructions, three bitmap
allocations and one bitmap pixel write. Inspection of the actual installed
`core-ktx:1.17.0` bytecode confirms that each helper directly calls the original
framework operation. URI text, allowed schemes/hosts, enrollment/pairing checks,
nullable-input guards, exception scopes, bitmap dimensions, explicit ARGB_8888
configuration and QR pixel colors remain unchanged. Removed only imports made
unused by these replacements; URI type/encode/decode imports are retained.

| Gate | Released version 903 | Unreleased source |
| --- | --- | --- |
| Full lint warnings, empty diagnostic baseline | 119 | 92 |
| Visible lint warnings | 30 | 30 |
| Lint errors | 0 | 0 |
| Repository baseline entries | 89 | 62 |
| Newly introduced full-lint findings | — | 0 |

The 25 call sites resolve 27 findings because two URI parses were reported twice.
Pruned exactly those 27 baseline entries, adding no baseline entries or rule
suppression. Validation includes all 52 existing unit tests, normal/full lint,
assemble, signing/package/non-debuggable checks and a source-equivalence proof
that reverses only the listed call replacements and compares the remaining code.
There is no new physical-device VPN/router acceptance test in this block.

VPN/browser operation, auth requirements, sync/update recovery, SharedPreferences
commit/apply behavior, preference display, resources, build scripts, dependencies
and SDK levels are unchanged. The owner's choice to preserve current preference
title wrapping still applies. Remaining behavior/design review items in the dated
sections below stay open, including Canvas save/restore exception behavior,
preference persistence and launcher/navigation changes.

Evidence: `/srv/vcs/deploy/evidence/virtuvpn-ktx-equivalence-20261011/`.

## Android compatibility maintenance — October 11, 2026, version 903

The owner authorized continued fixes with consultation before changes that may
affect existing application behavior. This block preserves the current VPN,
browser protection, sync, updater, authentication and task-persistence contracts.

- Replaced 12 browser drawable-loading calls with `ContextCompat.getDrawable`
  using the same `SecureBrowserActivity` context and resource IDs. The actual
  installed AndroidX 1.17.0 bytecode delegates to `Context.getDrawable` on API21+;
  minSdk remains 24. Theme/resource selection, nullable results, exceptions and
  callbacks are preserved. This resolves 23 lint findings because some call sites
  were reported twice. AppCompat's separate tint/vector loading path is not used.
- Moved the existing outline-clipping flag on 27 views into one isolated shared
  style. The default style is empty with no parent; its API31 variant contains
  only `android:clipToOutline=true`. The XML attribute exists from API31, unlike
  the older Java setter. Clipping remains enabled where the XML attribute is
  supported, while older frameworks retain their existing defaults. No entire
  layout was copied, existing styles were not replaced, and other attributes,
  bindings, view order, sizes and actions are preserved.

| Gate | Version 902 | Version 903 |
| --- | --- | --- |
| Full lint warnings, empty diagnostic baseline | 169 | 119 |
| Visible lint warnings | 33 | 30 |
| Lint errors | 0 | 0 |
| Repository baseline entries | 136 | 89 |
| Newly introduced full-lint findings | — | 0 |

Removed only 47 resolved baseline entries: 24 outline-attribute findings and 23
browser drawable findings. No finding was added to the baseline and no rule was
suppressed. Translation and private-resource findings remain at zero.

Validation includes all 52 existing unit tests, normal/full lint, production
assemble, source-equivalence checks, compiled XML/style bags and a comparison of
effective compiled layout attributes under the API24–36 boundary model. The
model verifies empty/default and API31 style selection; it is not a claim of new
physical-device rendering or OEM firmware tests. Package, signing identity,
non-debuggable status and the served production APK/metadata/hash are checked.
Build scripts, dependencies, minSdk and targetSdk are unchanged.

### Behavior consultation still required

| Candidate | Possible effect and current decision |
| --- | --- |
| Five `singleLineTitle` preference attributes | AndroidX checks its own attribute before the framework fallback. Switching namespaces can make multiline titles explicit and affect wrapping/row height; preference keys, values and VPN logic need not change. The owner chose to preserve the current display; XML and title wrapping remain unchanged. |
| Shortcut XML variants | Empty API24 fallback could change what a third-party launcher reads even though native static shortcuts require API25. Retain the complete current definitions until launcher compatibility is agreed. |
| Widget XML variants | Keep current preview, dimensions and 4×3 target cells. Versioned complete XML variants would duplicate metadata and require checking launcher sizing/preview behavior; no cosmetic deletion of newer attributes. |
| Predictive Back manifest flag | Changing it can affect Back gestures, WebView history, dialogs and exit behavior on Android13+. Keep the current opt-in. |
| Quick Settings drawable | Preserve its application theme and non-null constructor contract; the remaining resource-loader warning is deferred with icon rendering/nullability review. |
| SharedPreferences KTX changes | Keep synchronous `commit()` and existing success checks, especially download/install recovery and router snapshots. Do not convert mechanically to `apply()` or Unit-returning helpers. |

Release: **903 / 1.0.20261011.364-vcsinstall**, established signing certificate.
Evidence: `/srv/vcs/deploy/evidence/virtuvpn-compatibility-20261011/`.

Primary references:
[alternative Android resources](https://developer.android.com/guide/topics/resources/providing-resources),
[XML outline attribute](https://developer.android.google.cn/reference/android/R.attr#clipToOutline),
[widget sizing by platform version](https://developer.android.com/develop/ui/views/appwidgets/layouts).

## Localization maintenance — October 11, 2026, version 902

The owner approved the remaining translation block, with application behavior
changes still requiring discussion. Added German, Italian and French translations
for **251 resource IDs per locale** (250 strings and one plural resource), covering
account messages, VPN status/router operation, browser protection and pairing,
terminal access, enrollment, synchronization and update prompts. Existing English
text, calls and all previous translations are preserved. The sole resource-name
change isolates the Copy label under the app-owned `vcs_copy_label`; its one XML
reference changes with it, while its label and action remain the same.

Formatting argument positions/types, line breaks, product names, protocol terms
and DNS addresses were checked against the English resources. Safety requirements,
consent prompts, protection limits and failure/recovery instructions retain their
meaning. The French pairing-removal singular uses the count already passed by
`getQuantityString(..., count, count)`; the existing zero-count guard is unchanged.
French and Italian include explicit `many` forms matching their `other` wording.

Eleven address/glyph/numeric-format resources are explicitly language-independent:
the example HTTPS address, browser action symbols, match/zoom numeric formats and
the no-data dash. Their original contents are unchanged. This is a resource
classification, not a suppression of untranslated human-readable messages.

| Gate | Version 900 | Version 902 |
| --- | --- | --- |
| MissingTranslation findings in full lint | 262 | 0 |
| Full lint warnings, empty diagnostic baseline | 433 | 169 |
| Visible lint warnings | 33 | 33 |
| Lint errors | 0 | 0 |
| Repository baseline entries | 400 | 136 |
| Newly introduced full-lint findings | — | 0 |

Only 262 resolved translation entries and two resolved private-resource entries
were removed from the baseline. No baseline entry or suppression was added.
The `copy` override was initially deferred in version 901. Inspection of the
installed AndroidX dependency and the compiled APK then established that its
default and three requested translations are identical to the app label. Version
902 removes the override and gives the app its own label. Every compiled AndroidX
Copy-label locale configuration is compared before/after and must remain exactly
the same. This resolves the final missing translation and both private-resource
warnings without changing the dependency UI. The remaining behavior/design review
items from version 900 remain deferred.

Validation: all 52 existing unit tests, full and normal lint, production assemble,
compiled resource presence for all three locales, source preservation checks,
production package/non-debuggable/signing checks, and the served APK/metadata/hash
binding. This block does not constitute a new physical-device VPN/router or
screen-by-screen language acceptance test. Build/deployment scripts, VPN/router,
sync, updater persistence, authentication and registration policies are unchanged.

Release: **902 / 1.0.20261011.363-vcsinstall**, established signing certificate.
Evidence: `/srv/vcs/deploy/evidence/virtuvpn-localization-20261011/`.
Earlier dated release sections below retain their historical measurements.

## Safe warning cleanup — October 11, 2026, version 900

The owner authorized simple cleanup and explicitly required consultation before
changes to application logic. This block preserves VPN/router operations, enrollment,
sync, authentication, command receipts, updater persistence and signing identity.
Build and deployment scripts are unchanged.

### Completed source and resource cleanup

- Removed 78 unused resource IDs and their localized/density variants after both
  a full lint review and reference checks across tracked code/XML. The unused
  `tunnel_detail_peer` layout has no binding consumers. Resources referenced only
  by that deleted layout were checked again before removal. No device profiles,
  downloads, installation records or user files were removed.
- Removed 11 byte-identical bitmap copies. Resource aliases preserve the launcher,
  round launcher and router-shortcut names and original pixels/density selection.
  Pre-26 launcher aliases point to the retained foreground PNGs; adaptive v26 XML
  continues to use those PNGs, avoiding an alias cycle.
- Completed 82 existing string translations plus the new Share logs accessibility
  label in German, Italian and French. Format arguments retain their existing
  positions/types. The fixed `virtuvpn://router-pair` example is protocol syntax
  and is explicitly non-translatable. Existing English UI remains the default;
  historical untranslated strings are still reported, not replaced with English
  copies to silence lint.
- Added 20 explicit French/Italian `many` plural forms with exactly the previous
  `other` fallback text. This changes no selected message. The partial-sync count
  label uses a neutral count format with the existing string ID and arguments.
- Decorative images defer to adjacent text labels; the log Share action now has
  its own accessible label. Six 10sp labels now use 11sp. Three widget separators
  share one non-translatable symbol resource. Fixed display ellipses and one
  redundant XML namespace. Existing input-field accessibility labels are retained
  pending a separate review.
- Replaced 46 color parsing spellings with the equivalent AndroidX `toColorInt`
  call. The installed AndroidX implementation delegates directly to the same
  `Color.parseColor`; conditional branches, values and callbacks are unchanged.

### Validation and honest warning inventory

| Gate | Before | After |
| --- | --- | --- |
| Visible lint warnings | 135 | 33 |
| Full lint warnings with an empty diagnostic baseline | 761 | 433 |
| Lint errors | 0 | 0 |
| Repository baseline entries | 643 | 400 |

The baseline was pruned, never expanded; no lint rule was disabled. Full reports
are collected using an external diagnostic init script and empty baseline, without
changing project build scripts. The 400 remaining baseline findings remain open.
**Ten historical launcher-shape findings stop being recognized by lint after
bitmap aliasing. Their pixels were preserved, so shape/themed-icon design remains
an open review item; these ten findings are not claimed as repaired designs.**

Validation includes all 52 existing unit tests, full and normal lint, production
assemble, compiled icon aliases, package/non-debuggable/signing checks and the
APK/metadata/hash binding served by VCS App. Physical ARM VPN/router testing remains
the separate acceptance gate described below. Version **900 /
1.0.20261011.361-vcsinstall** uses the established updater and signing certificate.
Evidence: `/srv/vcs/deploy/evidence/virtuvpn-safe-warnings-20261011/`.

### Stop point: consult before behavioral changes

| Area | Why it is deferred |
| --- | --- |
| Updater/managed state and router preference edits | Preserve synchronous `commit()` and its Boolean success checks. KTX `edit` returns Unit, so a mechanical replacement can lose the durability/failure contract. Router recovery snapshots, health counters and operation ordering require individual review. |
| RecyclerView notifications | Replacing whole-list notifications requires validating item identity, moves, selection and observer ordering. |
| Enrollment App Links and autofill | Domain verification/browser routing and credential handling are product/security policies, not cosmetic warnings. |
| Android compatibility attributes | Newer-platform attributes are intentionally ignored on older supported devices. Do not delete them or raise minSdk just to clear warnings; decide version-qualified compatibility behavior first. |
| URI/bitmap/Canvas and compatibility drawables | Review nullability, exception cleanup, bitmap parameters and platform/theme behavior before converting helpers. |
| Layout consolidation and input accessibility | Compound drawables, action placement, background removal and field labels can alter layout, touch areas or screen-reader behavior. |
| Shared `copy` resource and plural wording | Renaming an override can change dependency UI labels; quantity wording must preserve its caller's formatting semantics. |
| Remaining localization and launcher design | 262 historical translation findings remain a separate language review. Adaptive monochrome layers and legacy icon geometry need correct branding assets and rendering checks. |

Android resource reference:
[resource aliases](https://developer.android.com/guide/topics/resources/providing-resources#AliasResources).

## GitHub Actions native checkout — October 11, 2026

The **Android Secured Browser** workflow must check out recursive Git submodules.
The WireGuard native build requires both `tunnel/tools/wireguard-tools` and
`tunnel/tools/elf-cleaner` at the commits pinned in this repository. A fresh
checkout without these dependencies fails CMake configuration because
`wireguard-tools/src/wg-quick/android.c` is missing; a developer checkout with
initialized submodules can conceal this CI failure.

The workflow now initializes the pinned submodules and verifies the two required
native source files before setting up Java/Gradle. Its existing unit-test, lint
and build gate remains mandatory. Do not substitute unpinned dependency downloads
or disable the native build to make CI pass. Local fresh checkouts require
`git submodule update --init --recursive` before invoking the same Gradle gate.
The repaired fresh checkout passes all 52 unit tests, lint (0 errors, 135 visible
existing warnings) and `assembleVcsinstall` with Gradle build-cache reuse disabled.

This changes CI checkout only. Production version 899, signing identity, updater,
VPN behavior and access requirements are unchanged. Failure reproduction and
verification evidence: `/srv/vcs/deploy/evidence/virtuvpn-ci-discovery-20261011/`.


## Device diagnostics — October 11, 2026, version 899

**Device diagnostics** now opens a full VirtuVPN screen with the existing dark
background, teal actions, resource bars and readable cards. It is available without
a VCS account. Technical JSON is retained only as an explicit redacted Share report
export. CPU/RAM, internal storage, battery/thermal state, device security, VPN/sync,
optional app storage and local TCP checks have separate evidence and timestamps.
Unavailable values remain unknown; there is no invented security score or malware
verdict. Findings never authorize deleting files, changing VPNs or stopping apps.

### Measurement and privacy contract

- Resources use Android MemoryInfo, Debug process PSS, StatFs, sticky battery state
  and thermal status when supported. CPU is a measured 750ms sample of the
  **VirtuVPN process**; 100% means one logical CPU, so multicore work can exceed
  100%. The sample includes diagnostic work. Logical CPU count is the count
  available to this process, not a promise about all physical cores. Android's
  low-memory signal is separate from occupied RAM because cached RAM is normal.
- Security shows the reported Android patch/date, screen lock, USB debugging and
  developer settings. A patch older than 180 days suggests reviewing system
  updates; this is not CVE detection. Patch age uses the phone clock, malformed,
  future or unavailable dates stay unknown. Root, bootloader integrity and malware
  are not assessed. Battery temperature is not presented as CPU temperature.
- **Measure app storage** is optional and requires user-enabled Usage access on
  Android8+. The app measures up to 64 launcher-visible packages for the current
  user with a 15s traversal budget and shows the eight largest measured apps.
  Missing/failed/capped results are partial evidence, not a complete phone ranking.
  It uses each package's storage volume, never reads private app files, and never
  uses QUERY_ALL_PACKAGES, Accessibility or root. Cache is included in data bytes
  and must not be added a second time. Android7 keeps basic diagnostics and offers
  Android Storage instead. Actual CPU/RAM rankings for other apps are unavailable
  under Android's sandbox; usage access does not remove this limitation.
- **Check local ports** is an explicit connect-only sample of 32 fixed TCP ports
  on numeric IPv4 and IPv6 loopback (64 checks), six concurrent slots and 180ms
  connection timeout. No DNS, external targets, banners, payloads, UDP or subnet
  scan. Refusal is distinguished from inconclusive/unsupported address results.
  An accepting local port proves only that the sampled address accepted a local
  connection: it does not identify its application or prove LAN/public exposure.
  Ports outside the list and interface-only listeners remain unverified.
- VPN state, DNS configuration and handshake age retain the existing evidence
  limits: UP/default routes do not prove reachability or absence of leaks.
  Collection is bounded to 32 tunnels and a 4s coroutine deadline. No external
  public-IP/DNS tests are performed. No-account status is informational, not a
  security fault. Diagnostic operations do not enroll, sync, log out or update.
- A screen ViewModel prevents duplicate clicks and preserves observations across
  rotation. Explicit navigation away cancels observation jobs; process restart
  starts fresh, with no persisted app inventory or diagnostic history. Revoking
  Usage access clears app insights and invalidates late results. Export uses an
  explicit allowlist: no app/package names, VPN profile names/keys/endpoints,
  installation IDs, credentials, tokens or browsing data. Nothing is uploaded
  automatically. Sharing requires the user's Android share action.

### Validation and release

Validation: 52 unit tests, lint/signing/production build gates and isolated Android36
instrumentation. Coverage includes anonymous basic diagnostics, real resource
measurements, an actual accepting localhost listener with no transmitted payload,
64-probe bounds, refresh, rotation retention, export redaction and actual Android
Usage access grant/revocation. Lint has 0 errors and 135 visible pre-existing
warnings (one obsolete export label removed); the 627 matching legacy baseline
warnings remain a separate audit scope and have not been presented as fixed.
UI is visually checked in portrait, landscape and
larger system text. The x86 UI fixture adds locally built x86 WireGuard libraries
for emulator compatibility only; production remains the unchanged ARM build.

Canonical version: **899 / 1.0.20261011.360-vcsinstall**. The established signing
certificate, unified updater, managed enrollment and independent anonymous Browser/
third-party Router access are preserved. APK/metadata/integrity are published
atomically through vRack; App, Gateway and Rust need no changes or restart.
Evidence: `/srv/vcs/deploy/evidence/virtuvpn-device-diagnostics-20261011/`.

Platform references:
[StorageStatsManager](https://developer.android.com/reference/android/app/usage/StorageStatsManager),
[cache included in data](https://developer.android.com/reference/android/app/usage/StorageStats),
[per-app RAM limits](https://developer.android.com/reference/android/app/ActivityManager#getProcessMemoryInfo(int%5B%5D)),
[CPU access limits](https://developer.android.com/reference/android/os/HardwarePropertiesManager),
[network state restrictions](https://developer.android.com/about/versions/10/privacy/changes),
[package visibility](https://developer.android.com/training/package-visibility/declaring).

## Unified updater — October 11, 2026, version 898

An explicit **Check updates** opens one lifecycle-aware progress window and starts
Check → Download → Verify → Install. New updates no longer require a second
check/download click. DownloadManager remains the durable download owner; its
actual bytes/total determine progress. Hashing/copy progress is measured separately.
PackageInstaller owns the installation session and reports its actual progress and
final result. A 100% download or staging percentage is never reported as installed.

The application coordinator outlives screen observers. Hiding the window continues
download/verification, while permission and installer confirmation are launched only
by a resumed foreground window. Returning reuses the recorded download, re-verifies
it and continues. Android's unknown-source permission and installation confirmation
are preserved; denial/cancellation gives an actionable state with the retained
owned download. No silent install, browser/URL fallback or background activity
launch was introduced. Installation callbacks are scoped to an explicit non-exported
receiver and the recorded session ID. Only the recorded stale session is eligible
for cleanup; an active install cannot be replaced by a second session.

Installation uses the verified APK directly in a PackageInstaller session, removing
the obsolete FileProvider installation path. Security checks still bind size, SHA-256,
package/version and both installed/candidate signer identities; staging checks the
hash again. Success comes from Android's result or the actual installed version.
Failed/missing native downloads can be retried; contradictory/unrelated session
callbacks cannot advance this workflow. Secure Browser and third-party VPN Router
continue to require no VCS account. Managed update authorization stays unchanged.

Validation passed: 45 existing unit tests, production assemble/signing gates and
lint (0 errors, 136 unchanged warnings). Isolated Android36 testing rejected a
checksum mismatch without creating an installer session, completed download and
verification while hidden, preserved required Android permission/confirmation,
and retried a cancelled install without another download. PackageInstaller
completed a real self-update to a deliberately higher test-only code899; only
production code898 is published. No customer accounts, production DB fixtures
or VPN/router changes were involved.

Release/test evidence: `/srv/vcs/deploy/evidence/virtuvpn-update-flow-20261011/`.
Emulator coverage uses private x86-native fixtures as previously documented; the
current test fixture allows only localhost HTTP to exercise DownloadManager without
customer tokens. Production APK and network policy remain unchanged by that fixture.
APK publication is an atomic APK/metadata/integrity swap through vRack, with the
matching signer preserved and no App, Gateway or Rust restart required.

## October 11 maintenance implementation — A1–A4

The owner authorized implementation after the discovery below. The four bounded
blocks are implemented together as version code **897**,
**1.0.20261011.358-vcsinstall**. The discovery records describe the earlier 896
artifact; they are retained as evidence, not current deployment instructions.

### Access and compatibility contract

- **Secure Browser requires neither VCS registration nor a VCS account session.**
  Its protected-network/router-pairing check remains independent of account auth.
- **VPN Router with a third-party VPN requires no VCS registration/login.** Root,
  routing and protected-uplink requirements still apply. VCS-managed VPN profiles,
  VPN Mesh, Managed Access, device enrollment/sync and device update grants retain
  their existing VCS authorization requirements.
- Account logout never revokes a global VPN, deletes local VPN configurations,
  disables router protection or ends detached server shells. Managed ownership
  and command receipts are scoped to the authorized device; legacy ownership is
  adopted only for the device already recorded at startup.
- Enrollment QR/deep-link formats and refresh grants remain compatible. On the
  first upgraded managed request, a valid existing refresh grant adopts a durable
  installation ID plus Keystore-encrypted installation secret. Server binding
  verifies this proof and retains the device ID and its assignments. An arbitrary
  name/model or client device ID cannot claim an existing installation. Legacy
  clients without installation proof remain accepted but cannot claim a device
  by matching its model name; upgrading is recommended.
- The installed signing certificate is unchanged. The legacy Android Debug
  subject does **not** imply that this APK is debuggable: `vcsinstall` now has
  `isDebuggable=false`. A separate signing-key migration is outside this change.
  Never replace this key or require uninstalling existing production devices.

### Completed blocks

**A1 — reliable synchronization and configuration storage (VPN01/05/06).**
Manual, foreground and periodic synchronization share a single operation.
Stopping a UI observer cannot cancel shared work; stopped/resumed screens cannot
leave duplicate polling loops. A failed config or import ACK is isolated to its
assignment, other imports and commands continue, and incomplete/pending bundle
imports do not authorize deleting healthy previous profiles. Encryption finishes
before touching the original configuration; a complete synced temporary file in
the same directory replaces it atomically. No permanent configuration backup is
created. Corrupt assignment/pending metadata does not crash normal navigation.

Command intent/outcome receipts are committed before acknowledgment. A lost ACK
is retried with the recorded outcome and does not turn successful execution into
failure. An interrupted command is reported as requiring state inspection rather
than silently executed again. Pending receipts are retained; acknowledged receipts
are bounded by seven-day retention and a safety cap. The API atomically records a
terminal outcome, accepts duplicates and rejects contradictory/previously
rejected outcomes. It never downgrades a completed command after a lost reply.

**A2 — installation identity and atomic enrollment (VPN03/04).**
Tenant-scoped PostgreSQL transaction locks serialize binding/quota decisions in
both login/refresh and enrollment. The enrollment token is claimed with an unused,
unexpired compare-and-set inside the same transaction. Quota/account failures
roll back consumption. Identical phone models do not rotate each other's tokens;
proof-backed re-enrollment retains the original device and assignments. No schema
migration or second auth database was introduced.

**A3 — production APK and verified updates (VPN02).**
The release contains APK, output metadata and `release-integrity.json` as one atomic
unit. Artifact metadata is authoritative over stale version hints. The API verifies
the published SHA-256/size/version/signer binding and caches unchanged verification;
it checks actual download bytes before serving. Android persists the DownloadManager
ID and expected manifest, displays actual state/progress after restart and checks
size, SHA-256, package, version and both installed/candidate certificate identities
before passing an app-owned content URI to Package Installer. Failed verification
has no unverified URL/install fallback. The installer still owns its OS permission
and signature checks. Cleanup is restricted to tracked obsolete downloads and
this app's own verified cache candidates. `build.sh` is now the portable Java17/SDK
production entry point with test/lint/signature/package/debuggable publication gates;
it does not publish to the old private IP or create duplicate debug/release copies.

**A4 — truthful status, low-impact sync and browser separation (VPN07/08/09/10).**
Home shows persisted sync phase, elapsed time, partial failures/pending work and
DownloadManager progress. Interrupted sync is recoverable after app restart.
Unique WorkManager sync runs at a 15-minute minimum with network/battery constraints
and exponential bounded retries; Android controls timing, so this is not an emergency
or instant-command promise. Explicit sync keeps its existing immediate path.
Confirmed unchanged standalone configs skip provisioning; an unchanged pending
config is not re-downloaded while its tunnel stays UP. Confirmed bundle versions
skip repeated imports/ACKs. New/changed/missing configs are still fetched normally,
and changed running profiles remain deferred until DOWN.

Device diagnostics explicitly distinguish local UP, configured DNS/default routes,
observed handshakes and unknown external reachability/leak behavior. The dead,
unreferenced 100%-privacy score helper was removed. The user can explicitly share
a bounded report containing no keys, tokens, endpoint URLs or browsing history.
Modern WebView uses a named Secure Browser profile, including its own cookies and
WebStorage. Older providers keep their shared storage intact, explain the limitation
and disable global cookie changes/clearing that could sign Web Terminal out. VPN/
router protection gates and detached terminal-job behavior are preserved.

### Validation and release evidence

- UI unit tests: 45 passed, including failure-safe atomic writes and concurrent
  single-flight observers/cancellation; existing router, URL, blocker and secret
  migration suites remain passing. Android assemble and lint pass with zero lint
  errors. Existing translation/style/icon warnings remain explicitly reported;
  the baseline was not expanded to hide findings.
- Real isolated PostgreSQL tests cover installation proof, legacy migration,
  concurrent device quota, one-use enrollment, rollback, idempotent/immutable ACK
  and tenant isolation. Production data and customer agents are not test fixtures.
- Production APK static verification confirms code897, correct package, minimum24,
  target36, non-debuggable manifest and unchanged certificate. In-place install over
  the actual 896 APK preserved app UID and existing profile bytes on an isolated
  Android36 emulator, without uninstalling.
- This x86 emulator's ARM translation crashes in the old published WireGuard Go
  library too. UI coverage therefore uses an explicitly test-only re-signed fixture
  with the unchanged locally built x86 tunnel library. Home, redacted diagnostics,
  plaintext-to-Keystore profile migration and anonymous Secure Browser entry were
  observed. This does not constitute an ARM VPN/hotspot/router or customer-login
  acceptance test. Such device checks remain a documented physical-device gate.
- Private reproducible logs/manifests/preservation evidence:
  `/srv/vcs/deploy/evidence/virtuvpn-maintenance-20261011/`. App release builds happen
  only on edge; prod1 receives verified artifacts through vRack with no Git pull,
  dependency installation or build. Guardian remains paused and Rust is reused.
- The clean June clone was an ancestor of canonical Git with no unique tracked,
  untracked or private inputs; only generated CMake caches were ignored. It was
  removed, reclaiming 72,261,632 allocated bytes. Current source, signing inputs,
  immutable releases and production device data remain retained.


## Current discovery audit October 11 2026

Current source is `9a7ceccf0672e37c9c5bec0f9fe60c103a7a291d` on `main`,
clean and aligned with its tracked upstream before this documentation update.
The production APK and immutable EDGE artifact have identical SHA-256
`3e24baced44b315e4157291797442444920a19f64896f88cb4c26dbcc9fed18e`:
package `com.virtuvpn.android`, version code 896,
`1.0.20261004.357-vcsinstall`. Static manifest/signature inspection confirms
`debuggable` and signer subject `C=US, O=Android, CN=Android Debug`.

This is a source/integration and static artifact review. No Android build,
device/emulator test, enrollment, VPN/router action or application-code change
was performed. Source inspection supports the failure paths below; it does not
claim a reproduced production exploit or a full dependency vulnerability scan.
Detailed evidence and source hashes are retained in
`/srv/vcs/deploy/evidence/virtuvpn-discovery-20261011/audit.json`.

### Existing functionality to preserve

WireGuard kernel/userspace backends, VPN mesh, Managed Access VPN/S2S clusters,
Agent Gateway profiles, QR/deep-link enrollment, trusted-host confirmation,
central VCS account authentication, independent device grants, single-flight
device refresh on 401, encrypted tokens and WireGuard configs, Secure Browser,
router pairing/attestation, router protection/restore, Web Terminal, connection
monitoring, version-aware imports, APK version detection/download, Android TV,
Quick Tile, router widget and biometric config export already exist.

The July S1/S2/S3/R2 descriptions below are historical checkpoints. Do not
reimplement encryption, enrollment trust or device refresh. Device access-token
TTL is enabled by default in current VCS App for refresh-capable versions 773+;
the distributed app is 896. Existing imports skip identical configs and defer
replacing a running tunnel. Account logout intentionally preserves VPN profiles,
router protection and server-side shell jobs.

### Confirmed gaps and improvements

| ID | Priority | Current behavior and proposed improvement |
| --- | --- | --- |
| VPN-01 | P1 | `VcsManagedClient.syncManagedTunnels` aborts the entire cycle on one bundle/provision/import/ACK exception. Home/manual/list sync lacks a shared operation lock, and repeated fragment resumes can leave overlapping loops. Use one coordinator, lifecycle-owned jobs and per-assignment results; preserve ownership until an authoritative complete snapshot and propagate cancellation. |
| VPN-02 | P1 | Production distributes a debuggable APK; update JSON has no expected digest/size/signer, and Android opens the downloaded file without those checks. Make a non-debug variant while initially preserving the established signing identity, then verify package/version/size/hash and approved signer before install. Reuse the current endpoint and versionCode contract. A signing-key change requires a separate migration; never solve it by forcing reinstall/data loss. [Android signing requirements](https://developer.android.com/studio/publish/app-signing). |
| VPN-03 | P1 | Registration uses `Build.MODEL` as deviceName and null devicePublicKey, without an installation identity. Enrollment/account restore matches the latest active tenant/user/deviceName. Two identical phone models can rotate each other's device token and assignments. Add a persistent proved installation/device identity with migration of existing grants; a display label or arbitrary submitted ID is not proof of ownership. |
| VPN-04 | P1 | Enrollment usedAt/expiry is checked before the transaction; consumption updates by ID without an unused/valid predicate. Concurrent completion can both pass. Tenant device-count admission is also not serialized. Atomically consume the existing token in the binding transaction and serialize quota admission. Keep current QR/deep-link formats. |
| VPN-05 | P1 | `FileConfigStore.writeEncryptedText` truncates the old profile before encryption/write can succeed. Keystore/full-disk/interruption can destroy good config. Encrypt first and atomically replace using a temporary same-directory file; no permanent backups. `loadAssignments` also needs corruption handling that does not turn broken local JSON into an authoritative empty set and delete good profiles. |
| VPN-06 | P2 | Command execution and ACK have no durable local result journal. A lost success ACK enters the operation-failure handler; server ACK can overwrite an already terminal result. Persist and replay the same observed receipt; make terminal results immutable. Current UP/DOWN actions are desired-state operations, not evidence of arbitrary non-idempotent command execution. |
| VPN-07 | P2 | `PrivacyChecker` treats tunnel UP as hidden IP and non-empty DNS settings as protected DNS; a split tunnel can score 100 without proof of default routes, IPv6 or DNS path. Separate configured, observed and unknown status. Add one read-only diagnostic view and a bounded sanitized support export, reusing existing ping/handshake/statistics. |
| VPN-08 | P2 | Managed polling belongs to the visible tunnel-list fragment, with a 15-second delay after each cycle; Home has manual sync/resume heartbeat. No durable global sync outcome/scheduler or download-observer restoration is present. Show last sync, pending commands and per-assignment errors. Background sync is a product/battery decision; [WorkManager](https://developer.android.com/develop/background-work/background-tasks/persistent) can persist scheduled work but does not guarantee immediate emergency execution. Preserve independent existing router/VPN services. |
| VPN-09 | P2 | Every foreground cycle provisions each active standalone assignment even when unchanged, though local apply already avoids duplicate writes. Reuse configVersion/conditional validation so only changed configs are provisioned. One June22 clone is a cleanup candidate of about 69 MiB; no references were found in inspected units/wrappers/current source, but compare unique Git/private state before deletion. It was not deleted. |
| VPN-10 | P2 | Secure Browser uses process-global `CookieManager.removeAllCookies`; its existing documented limit is that this can sign out Web Terminal. Verify supported WebView profile/process isolation before changing data scopes. Preserve terminal detach-only job lifetime and its private transport. |

### Finite proposed maintenance blocks

1. A1: sync coordination, partial results, safe local writes and durable command
   receipts — VPN-01/05/06. Verify concurrent sync, malformed assignment, lost
   ACK, cancellation and full-disk/Keystore failure in isolated fixtures.
2. A2: stable device identity and atomic enrollment — VPN-03/04. Verify two
   identical models remain distinct, existing grants survive, concurrent token
   consumption succeeds once and quotas hold across concurrent requests.
3. A3: production APK lifecycle — VPN-02. First prove same-key upgrade over the
   current APK retains accounts, Keystore, profiles and enrollment. Verify
   tampered/stale/wrong-package/wrong-signer packages are rejected.
4. A4: accurate diagnostics, progress, conditional sync and WebView separation
   — VPN-07/08/09/10. Decide background-sync expectations first; verify supported
   real Android devices with screen off, process death, reboot, Doze and network
   changes. Static source review cannot substitute for this acceptance.

The repo currently has five UI unit-test files and two tunnel unit-test files;
no instrumentation tests were found. Direct managed-sync/enrollment/updater
coverage was not found. Those suites were not executed during discovery.
Android and VCS App must share the existing Auth/device/assignment/update
contracts; no second auth, mailer, poller or VPN implementation is proposed.
Managed Access removes a device's assignment rather than globally revoking VPN.
Guardian, Rust and Fahrkonto remain outside these proposed blocks.

## Historical hardening backlog

This document is the application-level security and reliability backlog for
`/srv/vcs/src/mobile/VIRTUvpn-androidapp`.

No item in this document should be implemented directly from the finding text.
Each item requires a short discovery pass first, then a scoped implementation
plan, then review of any behavior change before code is changed.

## Working rules

- Keep changes surgical and compatible with the current app flow.
- Do not duplicate existing router, browser, enrollment, or managed-access
  logic.
- Prefer existing app patterns and shared helpers before adding new abstractions.
- If a change touches paths outside this Android repo, discuss it separately
  before implementation. Examples: VCS App API routes, prod1 env, distribution
  paths, server enrollment behavior, or download endpoints.
- Every implementation must define verification before coding.
- Security fixes must fail closed unless explicitly documented otherwise.

## Current priority order

1. S1: enrollment confirmation and enrollment domain policy.
2. S2: APK update URL validation and APK integrity checks.
3. S3: protected storage for managed/account session material.
4. S4: cleartext/API transport policy and optional certificate pinning.
5. R1/R2/R3: managed sync resilience.
6. D1: shared private-address classifier.
7. D2/D3/D4: smaller deduplication cleanups.

## S1 - Enrollment hijack through deep links

Status: Implemented app-side for the current product decision.

Risk:
`MainActivity.handleVcsEnrollmentIntent` accepts enrollment payloads immediately,
while router pairing already requires explicit confirmation. A crafted
`virtuvpn://enroll?...` or QR enrollment payload can point `api` /
`apiBaseUrl` at an attacker-controlled server. If accepted, that server can
become the managed-access authority for the device and later push tunnel or
update actions.

Discovery before coding:
- Read `MainActivity.handleVcsEnrollmentIntent`.
- Read `VcsManagedClient.isEnrollmentUri`, `parseEnrollmentUri`,
  `handleEnrollmentPayload`, `parseEnrollmentPayload`, `completeEnrollment`,
  and session storage.
- Read `AndroidManifest.xml` intent filters for custom scheme and HTTPS
  enrollment.
- Read VCS App enrollment routes only if the proposed fix needs server-side
  domain policy or token format changes.

Required decisions:
- Whether custom-scheme enrollment may ever use a non-VirtuComputing API host.
  Current decision for this hardening pass: only `vcs.virtucomputing.com` is
  approved.
- Whether self-hosted enterprise domains are supported now or later.
- Where the allowlist lives: app-local constant, server-delivered policy, or
  account/tenant policy.
- What exact confirmation copy should show to the user.

Preferred app-side direction:
- Always show a confirmation dialog before completing enrollment, for QR and
  deep-link flows.
- Show the target enrollment host clearly.
- Reject unapproved hosts before `completeEnrollment`.
- Treat `https://vcs.virtucomputing.com/api/mobile/android/enroll/open?...` as
  the default trusted path.
- Keep router pairing confirmation behavior unchanged.

Implemented behavior:
- Enrollment QR, pasted enrollment payloads, and `virtuvpn://enroll` /
  `https://vcs.virtucomputing.com/api/mobile/android/enroll/open` deep links are
  parsed into a validated `EnrollmentRequest` before any network call.
- The only accepted enrollment server for this pass is
  `https://vcs.virtucomputing.com`.
- `http://` enrollment links and any foreign enrollment host are rejected before
  `POST /api/mobile/android/enroll/complete`.
- Every enrollment entrypoint shows a confirmation dialog with the target host.
- Router pairing confirmation remains separate and unchanged.
- Secured Browser can be opened from Home without account sign-in so hotspot
  guests can use the browser/pairing flow. Browsing itself still requires a
  local VPN, verified VPN Router hotspot, or local router protection before any
  page is allowed.

Verification:
- Deep link with trusted host shows confirmation and enrolls only after accept.
- Deep link with untrusted host is rejected before any network request.
- QR payload with trusted host shows confirmation and enrolls only after accept.
- QR payload with untrusted host is rejected.
- Existing managed-access install/open flow still works.
- Router pairing flow still works and is not mixed with enrollment.

Outside-Android impact:
Potentially yes, if approved enrollment domains must come from VCS App or tenant
configuration. Discuss before touching server code.

### Enrollment and sign-in flow discovery

Current real flow:

- VCS App web UI creates Android enrollment material through
  `POST /api/mobile/android/enroll/start`.
- The server returns install/open/deep-link material and a QR payload backed by a
  short-lived, one-time `MobileEnrollmentToken`.
- Android completes enrollment by calling
  `POST /api/mobile/android/enroll/complete` with the enrollment token and device
  metadata.
- Successful enrollment creates or updates the `MobileDevice`, marks the token
  used, and returns a managed-device access token.
- VirtuVPN Android also has `Sign in VCS`. This calls
  `POST /api/mobile/android/auth/login`, stores an account session, and attempts
  to bind or restore the managed-device session.
- Account sign-in is required for complete app functionality in the current UI,
  but it is not the same operation as QR enrollment. The app can be signed in
  and still need managed-device session restoration before sync/update works.
- Existing docs say users should generally start from VCS App web UI enrollment
  for managed access. The app-local sign-in path is still needed for account
  features and device-session restore.

Security consequence:

- S1 should not remove enrollment.
- S1 should make enrollment explicit and trusted: only approved host,
  confirmation before network call, and clear target server display.
- For the current product state, approved enrollment host is
  `vcs.virtucomputing.com`.
- If self-hosted VCS App domains are needed later, that is a separate product
  decision and likely needs a server-delivered or signed trust policy.

## S2 - APK update URL and integrity checks

Status: App-side origin/path hardening implemented. APK digest validation remains
server-dependent.

Risk:
The managed sync response can provide `apkUrl`, which is stored and later opened
through Android's installer flow. Android package signing prevents replacing the
installed app with a differently signed package, but the app should still avoid
prompting installation from arbitrary origins.

Discovery before coding:
- Read `VcsManagedClient.rememberUpdateAvailable`, `downloadStoredUpdate`,
  `updateDownloadUri`, `openDownloadedUpdate`, and `openUpdateUrl`.
- Read VCS App routes for `/api/mobile/android/update` and
  `/api/mobile/android/update/apk`.
- Inspect current update response fields and whether checksum/version metadata
  are already available.

Required decisions:
- Enforce same-origin as `session.apiBase` for `apkUrl`.
- Whether to allow only `/api/mobile/android/update/apk` and
  `/api/mobile/android/apk/install`.
- Whether APK hash is server-provided and signed, or app only compares a
  response header/hash from trusted origin.
- Whether certificate pinning is required before this item is considered done.

Preferred app-side direction:
- Require `https`.
- Require same origin as the current API base.
- Reject file/content/custom schemes.
- Validate version code/name against expected update metadata when available.
- Add APK digest validation if the trusted server provides a digest.

Implemented behavior:
- Managed update still uses the existing VCS App routes only:
  `/api/mobile/android/update` and `/api/mobile/android/update/apk`.
- Enrollment install and dashboard APK routes are unchanged:
  `/api/mobile/android/apk/install?token=...` and `/api/mobile/android/apk`.
  Router guest install is served locally by the VPN Router phone at
  `http://<router-gateway>:8788/virtuvpn.apk`, not by a VCS guest route.
- Android validates the server-provided `apkUrl` before storing it and validates
  it again before opening a previously stored update URL.
- Accepted update URL requirements:
  - `https`,
  - same host and effective HTTPS port as the active managed `apiBase`,
  - exact path `/api/mobile/android/update/apk`,
  - no userinfo and no fragment.
- Invalid or stale stored update URLs are removed from app preferences before
  any `DownloadManager` or external `ACTION_VIEW` open is attempted.
- `download=1` remains appended locally when needed, preserving the current
  native download behavior.

Integrity note:
- The current VCS App update JSON does not provide an APK digest. The server APK
  response exposes version headers and the Android package installer enforces
  package signing for updates, but app-side digest verification should wait until
  the trusted server publishes a digest/version metadata field through the
  existing `/api/mobile/android/update` response. Do not add a duplicate update
  endpoint for this.

Verification:
- Legitimate update still downloads and opens installer.
- `http://`, foreign host, custom scheme, and malformed `apkUrl` are rejected.
- Guest APK distribution remains unaffected.
- Enrollment install fallback remains unaffected.

Outside-Android impact:
Likely yes if digest/version fields must be added to VCS App update responses.
Discuss server changes before implementation.

## S3 - Plaintext managed/account session storage

Status: Step 1 and step 2 implemented for API bearer tokens. Server-side token
TTL/refresh remains pending under R2.

Risk:
`VcsManagedClient` stores account access token, managed device token, API base,
device id, assignments, and config material in normal `SharedPreferences`.
Biometric authentication is currently a UI gate, not a cryptographic key gate.

Discovery before coding:
- Read every `getSharedPreferences(PREFS, ...)` use in `VcsManagedClient`.
- Read session restore, account restore, sync, pending activation, and assignment
  persistence paths.
- Read `BiometricAuthenticator` and current sign-in/auth gates.
- Check dependencies for AndroidX Security Crypto or existing Keystore helpers.

Required decisions:
- Whether to migrate all `vcs_managed_client` preferences at once or only token
  fields first.
- Whether assignments/config material should also be encrypted.
- Whether biometric confirmation should protect token decryption or remain only
  a UI gate.
- Migration behavior for existing installed devices.

Preferred app-side direction:
- Introduce one private storage accessor before migration.
- Do not use `EncryptedSharedPreferences`; `androidx.security:security-crypto`
  is deprecated and should not become a new foundation.
- Use Android Keystore-backed AES/GCM value encryption, or an equivalent
  maintained AEAD implementation, for bearer token values.
- The token encryption key must not require per-use biometric/user
  authentication. Managed sync, heartbeat, state reporting, commands, quick tile,
  and boot/autoconnect paths need background access after device unlock.
- Migrate existing plaintext values once, then remove plaintext copies.
- Keep `allowBackup=false` and current deny-all backup rules.

Implemented step 1:
- `VcsManagedClient` now routes all `vcs_managed_client` access through a small
  private `ManagedPrefs` accessor.
- `ManagedPrefs` exposes separate secret read/write methods for token fields,
  but the implementation still stores values in the same existing
  `SharedPreferences` file. This is deliberately behavior-preserving and only
  prepares the next migration step.
- Direct `getSharedPreferences(PREFS, ...)` calls are removed from
  `VcsManagedClient`; D4 is closed for this file.

Implemented step 2:
- Account and managed-device bearer tokens are encrypted at rest with an
  Android Keystore-backed AES/GCM key.
- Stored secret values use an `enc:v1:` prefix so the app can distinguish new
  encrypted values from legacy plaintext values.
- Existing plaintext token values are migrated on first read: the plaintext
  token is returned for the current operation, an encrypted replacement is
  written back to the same key, and future reads use the encrypted value.
- Keystore decrypt/encrypt failure returns `null` for the token and clears both
  token keys. This fails closed into the existing sign-in/enroll-required flow
  instead of looping on a permanently broken secret.
- The Keystore key is not user-auth/biometric gated, preserving background
  heartbeat, sync, state reporting, quick tile, commands, and post-unlock boot
  behavior.
- `apiBase`, `deviceId`, expiry, display/account metadata, assignments, pending
  activations, and update prompt fields remain in the existing preferences file
  for this step.

Threat model notes:
- Keystore-backed value encryption raises the bar against other apps, backup or
  offline extraction, and non-root forensics.
- It does not protect against live root code running as, or instrumenting, the
  VirtuVPN app process. A live root attacker can generally ask Android Keystore
  to decrypt for the app UID.
- S3 protects API access tokens at rest. WireGuard tunnel config encryption is
  handled separately by WG1 below.
- Stronger defense-in-depth also needs server-side token TTL, refresh, and
  revocation work; that overlaps with R2.

Verification:
- Existing enrolled device keeps working after update.
- Fresh enrollment stores new session in encrypted storage.
- Corrupt or missing encrypted storage fails closed and asks user to sign in or
  enroll again.
- Sync and update flows still work.

## WG1 - WireGuard private keys at rest

Status: Implemented in Android app.

Risk:
WireGuard `.conf` files contain `PrivateKey` and can also contain peer
`PreSharedKey` values. The upstream file-backed config store writes
`wg-quick` text directly to app-internal storage. On a dedicated rooted router
device, a plaintext config file is one of the most valuable local secrets.

Design:
- Keep the existing `ConfigStore` and WireGuard `Config` APIs unchanged.
- Encrypt the persisted config text with Android Keystore-backed AES/GCM before
  writing it to disk.
- Use a separate Keystore alias for WireGuard configs:
  `virtuvpn_wireguard_config_aes_gcm`.
- Do not require biometric or per-use user authentication for the Keystore key.
  The router must be able to bring up tunnels headlessly after normal device
  unlock/boot flows.
- Decrypt only when loading a config into memory for normal tunnel operation.
- Migrate legacy plaintext `.conf` files on first successful load by rewriting
  the same file in encrypted form.

Implemented:
- Added a shared `SecretCrypto` utility for versioned `enc:v1:` AES/GCM values.
- `VcsManagedClient` token encryption now uses the shared utility instead of a
  token-only duplicate implementation.
- `FileConfigStore.create` and `FileConfigStore.save` write encrypted config
  payloads.
- `FileConfigStore.load` accepts both encrypted and legacy plaintext configs.
  Legacy plaintext is parsed first, then rewritten encrypted. If encrypted
  config decryption fails, loading fails closed and the tunnel cannot start from
  that corrupted/unreadable secret.
- `WgQuickBackend` still has to materialize a transient plaintext config file
  because `wg-quick` consumes a file path. That temporary file is now restricted
  to owner read/write and deleted in a `finally` block so it is not left behind
  after command failures.

Threat model notes:
- This protects WireGuard private keys against casual file disclosure, backup or
  offline extraction, and other apps without live root access.
- It does not protect against a live root attacker that can instrument the
  VirtuVPN process, read process memory while the tunnel is being started, or
  cause the app UID to ask Keystore for decryption.
- Explicit config export can still produce a plaintext WireGuard config because
  export is an intentional user/admin action. That flow should remain treated as
  sensitive.

Verification:
- Existing plaintext tunnels still load.
- After first load, the `.conf` file no longer contains plaintext
  `PrivateKey =` or `PreSharedKey =` lines.
- Newly created or saved tunnels are encrypted on disk immediately.
- Temporary `wg-quick` config files are owner-only and cleaned up after tunnel
  state changes.

Outside-Android impact:
No server change expected, unless token rotation policy is changed.

## S4 - Cleartext HTTP and transport policy

Status: Implemented for managed Android API/enrollment/update transport.

Risk:
Enrollment/API transport must not silently fall back to HTTP or an attacker
controlled TLS endpoint. The code should explicitly express the product
guarantee and pin the trusted production host with a rotation plan.

Discovery before coding:
- Read `VcsManagedClient.isEnrollmentUri`, `parseEnrollmentUri`,
  `parseEnrollmentPayload`, and `normalizeApiBase`.
- Inspect manifest/network-security-config state.
- Check whether local development or self-hosted deployments currently rely on
  HTTP.

Required decisions:
- Whether HTTP is completely forbidden for production and debug builds.
- Whether debug-only cleartext should exist for local development.
- Whether certificate pinning is required and how rotation will be handled.

Preferred app-side direction:
- Reject HTTP enrollment/API bases in production.
- Add `network_security_config` for the trusted production domain.
- Pin only with a rotation plan and backup/intermediate/root pins.

Implemented:
- `VcsManagedClient` rejects HTTP enrollment and managed API/update bases before
  network calls.
- `network_security_config` pins `vcs.virtucomputing.com` and subdomains to the
  observed Let's Encrypt chain and forbids cleartext for that domain.
- The config intentionally does not set a global cleartext deny because
  VirtuVPN Router attestation uses a local, non-public
  `http://<wifi-gateway>:8788` endpoint. Blocking all cleartext at the platform
  layer would break verified router pairing/browser compatibility.
- The router-local cleartext endpoint is not a managed API path. It is exposed
  only while VPN Router is enabled, restricted by router firewall rules to the
  hotspot downstream interface, and served through a root-owned persistent
  `toybox nc -L` proxy to the app-owned loopback attestation server. The
  attestation payload is nonce-bound and HMAC-signed with a per-router pairing
  secret; cleartext transport here is accepted only because the endpoint is
  local to the hotspot and the signed payload provides integrity/authenticity.
- Pin-set expiration is `2028-08-15`; cert-chain rotation must be checked and a
  new app released before that date.

Residual:
- APK downloads launched through Android `DownloadManager` are still protected
  by HTTPS plus same-origin/path validation, but app-level certificate pinning is
  not a complete substitute for verifying the downloaded APK artifact itself.

Verification:
- HTTPS enrollment and sync still work.
- HTTP enrollment is rejected with clear user-facing error.
- No router-local HTTP attestation behavior is broken; that endpoint is local
  router attestation, not managed API enrollment.

Outside-Android impact:
Certificate-chain rotation for `vcs.virtucomputing.com` now needs app-release
coordination before the configured pin expiration.

## S5 - PrivacyChecker trusted WiFi signal

Status: Implemented in Android app.

Risk:
SSID substring heuristics can create a false security signal. The product rule
from router/browser testing is that safety should come from VPN binding,
verified router attestation, or router firewall state, not WiFi naming.

Discovery before coding:
- Read `PrivacyChecker`.
- Find every UI location that displays or consumes the trusted WiFi score.
- Confirm whether this score affects decisions or only display.

Preferred direction:
- Do not label WiFi as trusted based on SSID.
- Tie secure/protected messaging to real VPN/router protection state only.
- If retained, present SSID heuristic as informational network type, not trust.

Verification:
- No screen says a connection is protected because of SSID or mobile data alone.
- Existing privacy/status UI still renders.
- `PrivacyChecker` no longer marks mobile data or SSID-derived WiFi names as
  trusted and no longer adjusts score based on SSID heuristics.

Outside-Android impact:
No expected server change.

## Web Terminal cleartext scope

- Android cleartext remains denied by default and for the VCS API.
- The network security config permits HTTP only to the fixed private Web
  Terminal host `10.77.1.1`. The activity additionally accepts HTTP only for
  addresses classified as private/local, and reachability requires the
  assignment's active WireGuard tunnel.
- Public HTTP browsing and arbitrary LAN cleartext remain blocked.

## R1 - One failed provision stops whole sync

Status: Needs discussion before implementation.

Risk:
One bad assignment or bad WireGuard config can abort the whole
`syncManagedTunnels` loop and prevent other valid assignments from importing.

Discovery before coding:
- Read `syncManagedTunnels`, `importManagedConfig`, `importManagedBundle`, and
  `SyncResult`.
- Read UI copy for sync result reporting.

Preferred direction:
- Catch per-assignment failures.
- Record assignment-level error.
- Continue importing remaining assignments.
- Return a partial result that UI can explain.

Outside-Android impact:
Possibly if assignment error should be reported back to VCS App.

## R2 - Device token 401 does not restore from account session

Status: Client refresh-on-401, single-flight, and device refresh-token fallback
implemented. Short access-token TTL enforcement remains pending.

Risk:
If the managed device token expires, calls fail even when account session can
restore a managed device session.

Discovery before coding:
- Read `requestJson`, `loadSession`, `restoreManagedSessionFromAccount`, sync,
  heartbeat, update, and command ack flows.
- Identify which requests use device token and which use account token.

Preferred direction:
- On a device-token 401, try account-based device session restore once.
- Retry the original request once.
- Avoid infinite retry loops.

Implemented:
- Device-token API calls now use a refresh-aware request path.
- On HTTP 401 from a managed-device request, Android calls the existing
  `POST /api/mobile/android/auth/device` endpoint with the account token,
  stores the returned managed-device token, and retries the original request
  once.
- Covered flows include sync, update check, heartbeat, tunnel provision,
  imported/config ack, command ack, and state reporting.
- Account login, enrollment complete, and account-session restore remain
  outside this retry path to avoid recursive refresh loops.
- Refresh is process-local single-flight: concurrent 401 handlers serialize on a
  refresh lock and compare the failed token with the currently stored token.
  If another request already refreshed the session, the waiting request reuses
  that stored token instead of issuing another refresh.
- Newer clients store `deviceRefreshToken` from login/account-restore and
  enrollment responses through the same encrypted secret storage used for access
  tokens.
- On HTTP 401 the refresh order is account-first, then device-refresh-token
  fallback. Account-bound devices keep using account restore as the preferred
  path; enrollment-only devices can recover through
  `grantType=device_refresh_token` without a signed-in account.
- Device refresh tokens are treated as managed-device secrets: clear-session and
  keystore failure cleanup remove them together with the managed access token.

Remaining:
- VCS App R2-server A provides refresh-token infrastructure, but access-token
  expiry is still nullable and not enforced for legacy compatibility. Short
  access-token TTL must be gated by an Android version known to store and use
  `deviceRefreshToken`.
- Current server refresh tokens are stable/non-sliding for 90 days. For
  enrollment-only fleet devices, expiry means a new enrollment is required
  unless a future server policy adds sliding refresh-token renewal.

Outside-Android impact:
Short access-token TTL and any sliding refresh-token policy are VCS App server
changes. Keep the client invariant account-first: account restore may replace
the stored refresh token, so device-refresh-token fallback is not the preferred
path for signed-in devices.

## R3 - Corrupt JSON preferences can crash sync/startup paths

Status: Needs discussion before implementation.

Risk:
`JSONArray(raw)` / `JSONObject(raw)` on persisted preferences can throw if the
preference value is corrupted.

Discovery before coding:
- Read `loadAssignments`, pending activation load/store, and any JSON prefs.

Preferred direction:
- Wrap parsing in `runCatching`.
- Clear corrupt preference and return empty JSON.
- Log enough detail for diagnostics without dumping secrets/config material.

Outside-Android impact:
No expected server change.

## D1 - Duplicate private-address classification

Status: Needs discussion before implementation.

Risk:
Private IP/host classification exists in several places. Divergence can reopen
known bypasses such as IPv4-mapped IPv6 or unusual numeric IP forms.

Discovery before coding:
- Read `SecureBrowserUrlPolicy`.
- Read `VpnRouterAttestation` host/gateway checks.
- Read `WebTerminalBrowserActivity` URL checks.
- Search for all `isPrivateIpv4`, `isPrivateIpv6`, `private host`, and
  `InetAddress` helpers.

Preferred direction:
- Create one `InetClassifier`/`PrivateAddress` utility.
- Cover IPv4, CGNAT `100.64/10`, loopback, link-local, IPv6 ULA/link-local,
  IPv4-mapped IPv6, and numeric host variants.
- Add unit tests at the utility level.

Implemented:
- Added `PrivateAddressClassifier` as the shared classifier.
- `SecureBrowserUrlPolicy`, `WebTerminalBrowserActivity`, and
  `VpnRouterAttestation.isAllowedClientAddress` now use the shared classifier
  instead of maintaining separate private-IP logic.
- Unit tests cover RFC1918, loopback, link-local, CGNAT `100.64/10`, IPv6 ULA,
  IPv4-mapped IPv6, local hostnames, and decimal/hex/octal IPv4 forms.

Outside-Android impact:
No expected server change.

## D2 - Duplicate URL normalization

Status: Implemented in Android app.

Risk:
Secure Browser and Web Terminal maintain similar URL normalization code.

Discovery before coding:
- Compare `SecureBrowserActivity.normalizeUrl` and
  `WebTerminalBrowserActivity.normalizeUrl`.
- Confirm Web Terminal has different allowed schemes/hosts than Secure Browser.

Preferred direction:
- Share only the truly common normalization.
- Keep Secure Browser security policy separate from Web Terminal access policy
  if their threat models differ.

Implemented:
- `SecureBrowserUrlPolicy` owns the shared trim/default-scheme/parse/allow
  normalization path.
- Secure Browser still allows only HTTPS top-level navigation.
- Web Terminal still defaults to HTTP and applies its separate terminal access
  policy, including private/local host restrictions for cleartext.

Outside-Android impact:
No expected server change.

## D3 - Managed config import duplication

Status: Implemented in Android app.

Risk:
`importManagedBundle` and `importManagedConfig` have near-identical
create-or-update behavior, which increases maintenance risk.

Discovery before coding:
- Read both import functions and all call sites.
- Check whether bundle import has semantics that config import does not.

Preferred direction:
- Extract a shared `applyImportedConfig(preferredName, config)` helper only if
  behavior is exactly common.

Implemented:
- `importManagedBundle` and `importManagedConfig` now share
  `applyImportedConfig(preferredName, config)` for create/update/current
  handling.
- Assignment ack behavior remains outside the shared helper, so direct managed
  config imports still ack imported assignments and bundle imports keep their
  existing bundle ack flow.
- Tunnel ownership is reconciled by assignment ID before import. A tunnel is
  removed only when the server explicitly returns that same assignment as
  inactive; missing assignments and external tunnels are left untouched.
- Imports use exact, case-sensitive names. If an exact config already exists
  on disk but is not loaded by `TunnelManager`, it is adopted before update.
  Distinct assignments such as revoked `EDGE1` and active `edge1` are never
  aliased by a case-insensitive lookup.
- Migration also handles clients whose earlier build recorded the active
  assignment under a stale case-variant name: when the assignment ID matches
  and the exact server-preferred config already exists, only that stale
  case-variant is deleted before adopting the exact config.
- Revoked legacy assignments that no longer carry `localTunnelName` are
  cleaned by their exact sanitized display name, but only after all exact
  names claimed by active assignments are protected. This removes stale
  `EDGE1` without touching the distinct active `edge1` tunnel.

Outside-Android impact:
No expected server change.

## D4 - Repeated preferences access in VcsManagedClient

Status: Needs discussion before implementation.

Risk:
Many direct `getSharedPreferences(PREFS, ...)` calls make S3 harder and increase
the chance of inconsistent storage behavior.

Discovery before coding:
- Count and categorize all preference accesses.
- Decide whether this is a standalone cleanup or part of S3 migration.

Preferred direction:
- Add one private `prefs(context)` accessor.
- Use it as a stepping stone toward encrypted storage.

Implemented:
- Added `ManagedPrefs` with plain and secret read/write methods.
- Replaced direct `getSharedPreferences(PREFS, ...)` calls in
  `VcsManagedClient`.
- No behavior change yet; encrypted token migration remains S3 step 2.

Outside-Android impact:
No expected server change.
