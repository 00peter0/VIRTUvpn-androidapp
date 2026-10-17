# Android updater session lifecycle — 17 October 2026

The SM-T860 already ran signed version 910, recovered by an in-place update.
Android still retained VirtuVPN's earlier committed session 1811375501, created
at 2026-10-17 13:51:23 UTC for the 910 APK. Its progress remained 0.8 and no user
confirmation was recorded. The next update to 911 completed download and APK
verification but was blocked by that previous session. Approximately 8 GiB was
available in app storage. This incident is distinct from the original verifier
delay and from the phone's installation-identity discontinuity.

The updater kept one mutable workflow version but did not bind a target version
to each installer session. Successful ADB recovery proved the installed version,
yet retained the old session. Starting a later update overwrote the workflow
target before handling that session, so the previous unfinished Android record
looked like an unresolved part of the new update.

Each new session now records its own target version. Before a new workflow changes
its target, the updater retires only its recorded owned session whose target is
already installed. Callback identity is removed before abandonment so an obsolete
aborted result cannot fail the next update. Failed cleanup retains the verified
binding and offers an explicit retry. A failed status query never proves absence.

For pre-fix sessions only, the existing recorded workflow target is a conservative
upper bound: old versions wrote a verified newer manifest's target before creating
a session and could later increase that target. Cleanup therefore still requires
the recorded bound to be positive and already installed. A newer session target
always takes precedence; invalid explicit targets never fall back to an older
completed workflow. Unknown targets and unresolved future updates remain intact.

No foreign session is abandoned. No timeout, percentage or callback alone proves
installation. Android installation confirmation, checksum, size, package, version
and signer checks remain. App data, installation identity, VCS credentials,
assignments, WireGuard, DNS/browser policy and web terminal code are unchanged.

The change is exercised with JVM policy tests and the signed Android recovery
runner using only its own empty uncommitted test sessions. The runner preserves
the original preferences, download, original sessions and data canary. Actual
tablet acceptance must additionally complete a normal in-app update to a higher
version; an ADB bootstrap alone is not end-to-end acceptance.

Private measurements, build/lint reports, source hashes, device records and
physical results are under
`/srv/vcs/deploy/evidence/virtuvpn-tablet-updater-fix-20261017/`.

Android defines [session ownership and abandonment](https://developer.android.com/reference/android/content/pm/PackageInstaller#abandonSession(int))
and [session confirmation](https://developer.android.com/reference/android/content/pm/PackageInstaller.Session#commit(android.content.IntentSender)).
The app does not change device policy or bypass system verification.

The preliminary signed 912 bootstrap retained tablet UID 10597, the original
2026-07-12 installation date and the prior VPN-off state. All 89 JVM tests passed;
lint retained 0 errors and 13 existing warnings. The actual tablet runner passed
32 checks, including retiring a completed owned session, retaining future or
unknown targets, rejecting a retired callback and restoring original state.
Version 912 is a local repair/bootstrap artifact, not a public update. Public
release 913 is used for the subsequent normal in-app update acceptance; its final
physical result belongs to the release manifest and private acceptance evidence.
