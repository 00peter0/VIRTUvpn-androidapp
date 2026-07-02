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
