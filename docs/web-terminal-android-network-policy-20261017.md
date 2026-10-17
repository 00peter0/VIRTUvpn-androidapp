# Android Web Terminal: allocated gateway compatibility (2026-10-17)

## Observed failure

On the physical phone, version 914 and the active `single-server-S` VPN opened
`http://10.75.2.1:7681/?vcsNative=android`, but Android WebView rejected it with
`net::ERR_CLEARTEXT_NOT_PERMITTED`. The failure occurred before reaching the
server. The old network security resource allowed only `10.77.1.1`, explaining
why the original account's terminal worked while a new Single server did not.

This is separate from the clean-server installer and its private Node runtime.
Do not install global Node, duplicate the terminal implementation, change Studio,
relax the server firewall or recreate devices to fix this Android policy error.

## Correction in version 915

The existing network security resource now lists all 430 exact terminal gateway
addresses allocated by VCS: Single server `10.75.N.1` (2..253) and S2S source
`10.N.1.1` (77..254). Single server's reserved 254 address, unrelated Local PC
addresses, client addresses, arbitrary LAN and public hosts stay denied.
An explicit base policy denies cleartext also on older supported Androids.

The resource is checked exhaustively in `NetworkSecurityConfigTest`; VCS API
certificate pins are unchanged. Secured Browser's independent HTTPS-only policy
is unchanged. Managed Access identity, WireGuard config, update flow, terminal
server runtime and shell lifetime are unchanged.

The assigned VPN still provides network encryption. The exact-host exception
permits HTTP inside that tunnel; it does not make the terminal publicly reachable
or replace server-side source, host, origin and assignment checks.

## Repeatable verification

```
./gradlew :ui:testVcsinstallUnitTest :ui:lintVcsinstall :ui:assembleVcsinstall :ui:assembleVcsinstallAndroidTest
adb install -r ui/build/outputs/apk/androidTest/vcsinstall/ui-vcsinstall-androidTest.apk
adb shell am instrument -w -e networkPolicyOnly true com.virtuvpn.android.test/com.wireguard.android.vcs.UpdateRecoveryInstrumentation
```

The read-only instrumentation branch checks Android's actual installed network
policy for every allocated gateway and rejects public/VCS API HTTP, unrelated
LAN hosts, reserved boundaries and IPv6. It also checks Secured Browser HTTP
rejection. It does not run updater recovery fixtures or change account data.
Instrumentation may restart the app; reopen it and verify the assigned VPN before
performing the terminal UI check. Remove only the test APK afterwards.

Physical acceptance must include opening the existing Single server terminal
through the assigned VPN, a harmless shell round trip, closing/reopening the
terminal without ending the server session, and an in-place signed update with
unchanged application UID and retained VPN. A successful URL policy unit test
alone is not proof of terminal connectivity.

Future VCS address pool changes must update the exact-host resource and tests
in this same release path. Never enable global cleartext as a workaround.

## Phone acceptance and account-isolation follow-up

Version 915 was installed through the real signed in-app update on the phone,
retaining its UID, first-install date and the Single server tunnel. The user
confirmed that the previously blocked terminal connects. The actual Android
policy accepted all 430 allocated gateways. An additional assertion about
loopback was invalid on this phone's Android 17: the OS adds an implicit local
loopback exception. The corrected test records that behavior and verifies that
Secured Browser still rejects loopback HTTP. No HTTP browser restriction was
removed to accommodate the test.

A technical launch of the legacy unfiltered tunnel list exposed a separate
account-isolation defect: retained profiles from a previous account could be
activated. This is addressed in version 916 with a shared ownership boundary;
see [the account isolation audit](managed-vpn-account-isolation-20261017.md).
