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
