# VirtuVPN for Android

Canonical source: `github.com:00peter0/VIRTUvpn-androidapp.git`, branch `main`.
This is the VCS-managed VPN/router/browser/terminal application, derived from
WireGuard Android under the upstream licenses. Production package is
`com.virtuvpn.android`; do not distribute the upstream or `.debug` package.

## VCS production build and publication

Use Java 17 and the Android SDK (compile SDK 36; minimum Android API 24).
Set `ANDROID_HOME` and run `./build.sh`. The `vcsinstall` variant is non-debuggable.
The established production signing certificate must remain
`f3802b267a2f4bc16f8c00c74de3665b63d4e7c3cd1fd8012e757137f40c63a3`.
Its legacy certificate subject is Android Debug; changing the key without a
separately planned migration would break upgrades and must not be done here.
Signing keys stay outside Git. A fresh checkout must fail the publication gate
if it builds with an unrelated newly generated debug key.

Publish the tested APK together with `output-metadata.json` and a verified
`release-integrity.json` containing package, version code/name, SHA-256, size,
signer SHA-256 and source commit. VCS App consumes the atomic release on prod1
at `/opt/vcs-mobile/android/vcsinstall/`; edge artifacts are immutable and bound
to the source commit. Never overwrite only one file in a live release.

See [maintenance and security evidence](docs/android-app-security-hardening.md)
for enrollment compatibility, sync, command receipts and acceptance limits.
For physical Android testing from EDGE, use the
[verified MacBook SSH/ADB connection](docs/android-app-security-hardening.md#physical-android-device-access-from-edge--october-11-2026).

## Upstream library information

## Embedding

The tunnel library is [on Maven Central](https://search.maven.org/artifact/com.wireguard.android/tunnel), alongside [extensive class library documentation](https://javadoc.io/doc/com.wireguard.android/tunnel).

```
implementation 'com.wireguard.android:tunnel:$wireguardTunnelVersion'
```

The library makes use of Java 8 features, so be sure to support those in your gradle configuration with [desugaring](https://developer.android.com/studio/write/java8-support#library-desugaring):

```
compileOptions {
    sourceCompatibility JavaVersion.VERSION_17
    targetCompatibility JavaVersion.VERSION_17
    coreLibraryDesugaringEnabled = true
}
dependencies {
    coreLibraryDesugaring "com.android.tools:desugar_jdk_libs:2.0.3"
}
```

## Translating

Please help us translate the app into several languages on [our translation platform](https://crowdin.com/project/WireGuard).
