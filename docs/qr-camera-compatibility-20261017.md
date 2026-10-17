# Native QR camera compatibility — 17 October 2026

The Galaxy SM-S948B (API 37) opened the permitted camera but could not read a QR
that VirtuVPN on the SM-T860 (API 32) and Samsung Camera could recognize. This is
separate from release 922's permission cancellation/retry repair.

## Evidence and decision

The test-only comparison retained the installed production APKs: phone 922,
tablet 921. No camera frames or decoded contents were persisted, no profile was
imported, and the owned instrumentation packages and transfers were removed.

- The tablet's original scanner recorded a successful decode.
- On the phone, autofocus, continuous focus, normal/inverted scanning, harder
  detection, wider framing, a standard preview size and unlocked orientation
  did not decode the code during the controlled observations.
- Original ZXing core 3.4.1 and isolated core 3.5.4 both failed on the same legacy
  camera frames. Updating the decoder alone was therefore not the demonstrated
  solution.
- A CameraX/Camera2 comparison **with the original 3.4.1 decoder** succeeded
  after 49 analyzed frames. Its 1440 × 1080 luma plane had a row stride of 1472.

This demonstrates a working replacement for the legacy camera integration on
the affected phone. It does not isolate one specific camera-driver defect or
prove that row padding caused the old integration's failure. An initial probe
harness lifecycle error and a user-reboot interruption are retained as failed
evidence, not successful camera tests.

Discovery and reproducible, test-only comparison sources:
`/srv/vcs/deploy/evidence/virtuvpn-qr-camera-20261017`.

## Implementation

Both Other tunnels QR import and native Router pairing use `NativeQrScanContract`
and one `QrScannerActivity`. CameraX 1.6.2 replaces JourneyApps' legacy camera
wrapper; the original ZXing core decoder remains explicit at 3.4.1. No second
camera engine or unused JourneyApps activity is shipped.

- Prefer the back camera, support center and tap autofocus and optional light.
- Read the full analysis image, respecting buffer position, row and pixel stride;
  support normal/inverted QR, Unicode and rotation without writing camera files.
- Analyze the latest frame on one worker, at most eight times per second. Close
  every delivered image, including skipped, invalid, failed and successful frames.
- Bind the camera to the visible activity lifecycle. Invalidate pending work on
  stop/destruction, release only this scanner's use cases and stop its worker.
- Pause recognition when help/system dialogs take window focus. A pending result
  cannot be consumed after cancellation or while another screen is in front.
- Preserve the focused, user-approved camera permission flow. Missing access and
  camera errors expose retry; there are no implicit grants, APK settings changes
  or background camera services.
- Return QR text only through the internal activity result; retain existing
  import validation, enrollment confirmation, account ownership and Router pairing.
- Block screen capture of the scanner and keep explanation in the shared `i`
  control. Scanner help does not change Secured Browser website camera/microphone
  denial, network policy or WebRTC protection.

CameraX is initialized when opening the scanner. No network QR service or model
download is used. Existing file/image QR import remains separate and unchanged.

## Verification boundary

Seven JVM regressions cover exact text, inverted/rotated/dense QR, padded and
interleaved planes, read-only/nonzero-position buffers, malformed bounds and
unrelated frames. Native tests cover the internal result contract and cancellation
during camera initialization. Camera permission tests remain separate.

Release 923 acceptance and physical device results are recorded after final
verification in `docs/terminal-and-home-navigation-20261017.md`. Do not treat the
diagnostic prototype's successful read as proof of a deployed release. Android
API 24 is supported by the dependency/build configuration; it was not physically
tested in this session.

References: [CameraX compatibility and releases](https://developer.android.com/jetpack/androidx/releases/camera),
[closing and managing analysis frames](https://developer.android.com/media/camera/camerax/analyze).
