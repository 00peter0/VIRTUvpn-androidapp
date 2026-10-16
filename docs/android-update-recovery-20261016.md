# Android update recovery — October 16, 2026

## Problem and boundary

The SM-T860 tablet's 906 → 907 update remained at Android's reported 90%.
System integrity verification passed; a later verifier timed out and Android
announced continuation without a final result. After an authorized device reboot,
Android retained the recorded session as uncommitted while VirtuVPN continued
showing installation. A signed in-place ADB update recovered the device.
See [the original incident](vpn-dns-browser-audit-20261016.md#tablet-installation-recovery).
The exact system cause, including whether low storage contributed, is unproved.

This change improves recovery and reporting; it does not disable Android's
verifier, installation confirmation or app-integrity checks. VPN Maker, routing,
DNS/browser policy, enrollment, synchronization, accounting apps and user files
are outside the code change.

## Version 908 behavior

- After three minutes without increasing installation progress, replace the
  indefinite percentage/spinner with an explicitly unconfirmed state. Offer
  `Check installation`, available app-storage space and Android storage settings.
  A timeout never authorizes cancelling or replacing an installation.
- Persist the progress observation using monotonic time, session identity and
  boot count. Reopening the window does not reset the wait; a different session,
  reboot, invalid clock interval or actual forward progress starts a new window.
- Android 10+ can explicitly report an inactive, uncommitted installation after
  interruption. Offer retry, which reuses the retained download only after the
  complete existing checksum, package, version and signer verification.
- Replace only the application's recorded owned session, when Android facts prove
  it is inactive and uncommitted. Older Androids use conservative sealing/commit
  evidence; unknown state never grants permission to replace a session. Retire
  the old callback identity before abandoning a stale session.
- A failed status query does not mean the session is missing. A thrown commit
  does not authorize cancelling a possibly committed operation.
- A callback or percentage cannot alone establish successful installation. Check
  the installed package version; a late failure cannot undo an already confirmed
  installed target. Storage failures retain a storage-settings action after
  process recreation.
- Keep installation confirmation in Android's own foreground UI. No automatic
  reboot, uninstall, data reset, installation-policy change or foreign-session
  cleanup is introduced.

Android's documented
[session facts](https://developer.android.com/reference/android/content/pm/PackageInstaller.SessionInfo)
and [session ownership](https://developer.android.com/reference/android/content/pm/PackageInstaller#abandonSession(int))
define these boundaries. In particular, inactivity alone does not prove that a
committed installation may safely be replaced.

## Verification

The production build passed the established package, non-debuggable and signer
gates. All **75 JVM tests** passed, including 12 new installation-safety tests.
Lint retained **0 errors and 13 existing warnings**, with no new warnings.
`ManagedApkUpdates` and its integrity-verification implementation are unchanged.

A separate signed Android instrumentation APK targets the non-debuggable
`vcsinstall` variant through `UpdateRecoveryInstrumentation`. It creates only its
own empty, uncommitted session and never commits or installs an APK. Controlled
elapsed-time observations exercise the actual delayed/retry UI and callback
handling. The runner snapshots and restores only update-flow preferences, retains
the original download identity and installer sessions, and checks an app-data
canary. A private snapshot permits `restoreOnly=true` recovery after interruption.
Do not run it while a real update is pending; it refuses that state. Remove the
test APK after execution. It is not a production distribution artifact.

Physical execution and release publication are recorded below only after their
acceptance has completed. Unit tests do not prove the original Android verifier
stall can no longer occur.

## Tablet cleanup boundary

The user authorized safe cleanup. Use Android Settings `Clear cache` for named
applications, preserving application data. Do not use `pm clear`, uninstall apps,
remove game assets, change permissions or erase documents, photos, VPN configs,
Fahrkonto evidence or backups. Old downloaded VirtuVPN APKs are removed only after
checking their actual package/version and rechecking their SHA-256 before deletion.
The user clarified that Mafia City removal means old installers only, not the
installed game or its approximately 25 GiB of assets.

Operational measurements, cleanup records, build/lint/test reports and physical
results are retained privately under
`/srv/vcs/deploy/evidence/virtuvpn-update-recovery-20261016/`.

## Physical acceptance on the tablet

The SM-T860 runs signed production version **908**, name
**1.0.20261016.369-vcsinstall**, source commit
`d9cc5c35f539ef099c6a25ede5bf257d7a178aa4`, APK SHA-256
`e25310e578d43551f1f473400a1cd61957d48cf6899a3efd11ae1dc6691cb3cd`.
The in-place ADB installation preserved the UID, original installation date and
all three existing tunnels. The VPN remained off, matching the initial state.

The actual Android runner passed **22 checks**. These covered an owned active
session remaining intact, persisted waiting observations, the unconfirmed UI,
`Check installation`, available storage, the system storage action, interrupted
session retry UI, persistent storage-failure help, rejecting foreign callbacks,
requiring a measured installed version for success, ignoring obsolete failure
after proven installation, unchanged download identity and retained app data.
Update preferences and original owned installer sessions were restored exactly;
the test-only package and owned Mac transfer files were removed.

The first test assumed the storage action always opens `com.android.settings`.
Samsung delegates it to its system My Files / Analyze Storage activity. The
test was corrected to recognize the system handler; no production navigation
change was necessary. The corrected run passed. Controlled elapsed-time fixtures
exercise waiting behavior without deliberately hanging a real APK installation.
This does not prove the original system verifier fault can never recur.

Cleanup used only the `Clear cache` action for Chrome, Samsung Browser, Opera,
Facebook, YouTube and Perplexity. The corresponding cache counters reached zero;
application data remained. Seven inspected obsolete VirtuVPN installers totaling
156,610,832 bytes were removed. No Mafia City installer was found in the inspected
download directories, and the installed game was preserved. Available app-storage
space increased from approximately **2.4 GiB to 8.5 GiB** during the cleanup period;
this is a measured net change, not an exact attribution of every reclaimed block.

Android's storage monitor subsequently reported **NORMAL**, above its exhaustion
threshold. Personal files, accounting evidence and backups were not deleted.

## Production publication

Source-bound GitHub CI passed for the published APK commit. The complete 908
release replaced 907 atomically under `/opt/vcs-mobile/android/vcsinstall/` on
prod1, using EDGE → prod1 over vRack. The production metadata/integrity readers
and complete APK-serving response matched the accepted tablet artifact. Version
comparison offered an update to 907 and correctly offered none to 908. Actual
unauthenticated HTTP access remained rejected with 401.

The tablet's normal authenticated `Check updates` flow then showed
`No update available` for installed 908. No phone update was performed. Next,
Gateway, Rust and private environment hashes/process identities were preserved;
no runtime restart was required. Temporary transfer, test and rollback files were
verified and removed. The immutable release artifact and private evidence remain.

Release manifest:
`/srv/vcs/deploy/manifests/virtuvpn-908-update-recovery-d9cc5c35f539.json`.
