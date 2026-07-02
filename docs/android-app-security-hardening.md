# VirtuVPN Android App Security Hardening Backlog

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
- Router guest, enrollment install, and dashboard APK routes are unchanged:
  `/api/mobile/android/apk/guest`, `/api/mobile/android/apk/install?token=...`,
  and `/api/mobile/android/apk`.
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

Status: Step 1 implemented: centralized `VcsManagedClient` storage accessor
without behavior change. Token encryption remains pending.

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

Threat model notes:
- Keystore-backed value encryption raises the bar against other apps, backup or
  offline extraction, and non-root forensics.
- It does not protect against live root code running as, or instrumenting, the
  VirtuVPN app process. A live root attacker can generally ask Android Keystore
  to decrypt for the app UID.
- S3 protects API access tokens at rest. It does not encrypt WireGuard private
  keys in the existing config store. Encrypting tunnel configs is a separate,
  larger design change.
- Stronger defense-in-depth also needs server-side token TTL, refresh, and
  revocation work; that overlaps with R2.

Verification:
- Existing enrolled device keeps working after update.
- Fresh enrollment stores new session in encrypted storage.
- Corrupt or missing encrypted storage fails closed and asks user to sign in or
  enroll again.
- Sync and update flows still work.

Outside-Android impact:
No server change expected, unless token rotation policy is changed.

## S4 - Cleartext HTTP and transport policy

Status: Implemented for `VcsManagedClient` as part of S3 step 1.

Risk:
Enrollment/API parsing currently accepts `http://` in several places even though
target SDK and default platform policy may block cleartext in practice. The code
should explicitly express the product guarantee.

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
- Add `network_security_config` with cleartext disabled.
- Consider certificate pinning only with a rotation plan.

Verification:
- HTTPS enrollment and sync still work.
- HTTP enrollment is rejected with clear user-facing error.
- No router-local HTTP attestation behavior is broken; that endpoint is local
  router attestation, not managed API enrollment.

Outside-Android impact:
Possible if self-hosted deployments still use HTTP.

## S5 - PrivacyChecker trusted WiFi signal

Status: Needs discussion before implementation.

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

Outside-Android impact:
No expected server change.

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

Status: Needs discussion before implementation.

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

Outside-Android impact:
No expected server change unless API error format needs refinement.

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

Outside-Android impact:
No expected server change.

## D2 - Duplicate URL normalization

Status: Needs discussion before implementation.

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

Outside-Android impact:
No expected server change.

## D3 - Managed config import duplication

Status: Needs discussion before implementation.

Risk:
`importManagedBundle` and `importManagedConfig` have near-identical
create-or-update behavior, which increases maintenance risk.

Discovery before coding:
- Read both import functions and all call sites.
- Check whether bundle import has semantics that config import does not.

Preferred direction:
- Extract a shared `applyImportedConfig(preferredName, config)` helper only if
  behavior is exactly common.

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
