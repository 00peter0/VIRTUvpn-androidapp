# Android device reconnection — 17 October 2026

A normal APK update and an email change must retain the installation identity and
canonical MobileDevice ID. Device credentials remain independent of the account
email. Never match or adopt another device solely by its model name.

A fresh installation can legitimately have a new local identity. If an account
already has an active Android device of the same model, login must not silently
create another record. The server returns ANDROID_DEVICE_CHOICE_REQUIRED with
only that authenticated owner’s candidate IDs, names and assigned-profile counts.
An account/device refresh cannot authorize a reconnection or new-device decision.

VirtuVPN displays existing devices and Add another device. Reconnect device needs
an explicit second confirmation and a fresh account login. It retains the original
record and assignments, renews device credentials in the existing transaction and
writes an audit record. Add another device needs explicit confirmation and the
ordinary quota check; it supports genuinely different devices of the same model.
Cancellation, process recreation, failed authentication or ambiguous input grants
no consent. No passwords or pending decisions are persisted for replay.

All candidate lookups remain within the authenticated tenant, user, Android
platform and active/non-revoked status. Wrong installation proofs still fail.
Enrollment proof handling, WireGuard configuration, VPN Maker, routes and jobs
are unchanged. Older clients receive an actionable conflict instead of silently
adding a duplicate; upgrade VirtuVPN for the confirmation UI.

The incident distinguished the same physical phone’s old Android UID 10347 and
first-install date 2026-07-07 from UID 10461 and first-install date 2026-10-17.
This establishes installation discontinuity, not who or what caused it. The
server retained the original VPN assignments. Owner-requested repair preserved
the canonical record and assignment contents and retired only the empty duplicate.
The tablet had a separate interrupted Android installer session; signed in-place
ADB recovery to 910 retained UID, first-install date, data and installation identity.
Play Protect was disabled on the tablet only at the owner’s explicit request and
system confirmation. VirtuVPN checksum, certificate and package checks remain.

Evidence: /srv/vcs/deploy/evidence/vcs-tablet-vpn-incident-20261017.
Automated coverage: scripts/android-device-reconnect.test.ts in VCS App, existing
installation-handoff and mobile-lifecycle suites, and DeviceRegistrationChoiceTest
in VirtuVPN. Physical sync checks must retain the original IDs and profiles; do not
reset production app data to simulate another fresh installation.
