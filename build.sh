#!/usr/bin/env bash
set -euo pipefail
# Canonical VCS-installed artifact. Signing continuity is verified before publication.
cd -- "$(dirname -- "${BASH_SOURCE[0]}")"
TASK_ANDROID_SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [[ -z "$TASK_ANDROID_SDK" || ! -d "$TASK_ANDROID_SDK/build-tools/36.0.0" ]]; then
  echo 'Set ANDROID_HOME to an Android SDK with build-tools 36.0.0.' >&2
  exit 1
fi
export ANDROID_HOME="$TASK_ANDROID_SDK"
./gradlew :ui:testVcsinstallUnitTest :ui:lintVcsinstall :ui:assembleVcsinstall --console=plain "$@"
TASK_APK='ui/build/outputs/apk/vcsinstall/ui-vcsinstall.apk'
TASK_CERT="$($TASK_ANDROID_SDK/build-tools/36.0.0/apksigner verify --print-certs "$TASK_APK")"
if [[ "$TASK_CERT" != *'Signer #1 certificate SHA-256 digest: f3802b267a2f4bc16f8c00c74de3665b63d4e7c3cd1fd8012e757137f40c63a3'* ]]; then
  echo 'Signing identity mismatch: publication is blocked. Restore the established signing key; do not reinstall devices.' >&2
  exit 1
fi
TASK_BADGING="$($TASK_ANDROID_SDK/build-tools/36.0.0/aapt dump badging "$TASK_APK")"
if [[ "$TASK_BADGING" == *'application-debuggable'* || "$TASK_BADGING" != *"name='com.virtuvpn.android'"* ]]; then
  echo 'Production APK package/debuggable gate failed.' >&2
  exit 1
fi
sha256sum "$TASK_APK"
echo 'Validated production candidate. Publish APK, output-metadata.json and release-integrity.json as one release.'
