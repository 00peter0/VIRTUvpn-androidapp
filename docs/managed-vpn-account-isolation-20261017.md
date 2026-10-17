# Managed VPN account isolation (2026-10-17, version 916)

## Reproduction and cause

The phone had retained managed profiles from its previous superadmin login.
The normal Single server section showed the current account's assignments, but
launching the legacy general tunnel activity showed every local config. The
user confirmed that a profile from the other account could actually connect.
This was an activation authorization defect, beyond a presentation problem.
The existing per-device ownership ledger was used for sync cleanup, but was
not enforced by the shared tunnel backend boundary. Logout retained profiles
and running VPN access.

## Shared boundary

Managed profile access is now tied to the stable managed device ID, independent
of email and Android model. A known managed profile is usable only when exactly
one recorded owner matches the current managed device. Ambiguous ownership and
corrupt ownership records fail closed. Profiles with no managed owner remain
independent third-party imports; VPN Router and Secured Browser do not require
VCS registration for their existing independent functions.

The authorization check and backend activation execute under the same context
lock. This covers direct UP, TOGGLE, observable no-op UP, boot/Always-on restore,
connectivity reconnect and existing shortcut/remote-control entry points. Private
config reads, cached config getters, config edits and export inputs also check
ownership. Generic lists, saved selections, Home, TV fallback and Quick Settings
hide out-of-account managed profiles. Explicit DOWN remains possible for cleanup.
The terminal launcher validates the currently assigned URL. A terminal window
closes its WebView when the managed device/account or its assigned access changes,
including while the activity is in the background; server shell sessions persist.

Logout, login and startup stop out-of-account managed VPN access without revoking
server assignments or deleting retained WireGuard files. Signing in to the
original account restores access to its own profiles after sync. Shell processes
on the server are not ended by the Android UI or account cleanup.

## Sync and migration

Assignment caches carry their device identity. A response or command started
in an old context cannot adopt the next account's refreshed token or commit old
assignments into the next context. A context generation fences session-creating
responses across logout/context changes. Existing verified identity continuity
and duplicate-device handling remain intact.

Successful or partial imports claim ownership before touching a local profile.
A conflicting/ambiguous local filename gets a stable device-specific suffix;
another account's config is not read or overwritten to reuse that name. An old
unscoped ledger with no proven owner is quarantined until a fresh authorized sync.
Already-proven legacy ownership is retained. No identity is inferred from email,
model, tunnel display name or address similarity.

## Verification

Run JVM policy tests, production lint/build and the test APK build. On the
signed-in acceptance phone with retained profiles from both accounts, run:

```
adb shell am instrument -w -e networkPolicyOnly true -e accountScopeOnly true com.virtuvpn.android.test/com.wireguard.android.vcs.UpdateRecoveryInstrumentation
```

The negative checks request foreign UP and TOGGLE through the shared manager
and observable model, require rejection at the ownership boundary, reject direct
and cached config reads, verify retained files still exist and prove that the
active backend VPN set is unchanged by the negative attempts. They never enable
a foreign profile. The network checks retain the full Single server/S2S address
contract and Secured Browser HTTP rejection, including Android 17 loopback.

Acceptance also requires a signed in-place update with retained identity,
reopening the current Single server terminal, and checking that the generic list
no longer displays profiles owned by the previous account. Test evidence and
release metadata are stored in the deployment evidence directory. Do not mark
physical acceptance complete from unit tests alone.
