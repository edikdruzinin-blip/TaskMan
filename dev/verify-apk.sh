#!/bin/sh
# Checks that every built APK is signed (APK Signature Scheme v2+) and prints everything apksigner reports
# (including the signer certificate fingerprints).
# Needs apksigner from the Android SDK build-tools (present on GitHub runners and in Android Studio installs).
#   APKSIGNER=/path/to/apksigner ./dev/verify-apk.sh      (optional override)
cd "$(dirname "$0")/.."

SDK="${ANDROID_HOME:-$ANDROID_SDK_ROOT}"
APKSIGNER="${APKSIGNER:-$(ls -d "$SDK"/build-tools/*/apksigner 2>/dev/null | sort -V | tail -n 1)}"
if [ ! -x "$APKSIGNER" ]; then
  echo "apksigner not found (set APKSIGNER or ANDROID_HOME)"
  exit 2
fi

status=0
found=0
for apk in app/build/outputs/apk/debug/*.apk app/build/outputs/apk/release/*.apk; do
  [ -f "$apk" ] || continue
  found=1
  echo "== $apk"
  if out=$("$APKSIGNER" verify --verbose --print-certs "$apk" 2>&1); then ok=1; else ok=0; fi
  echo "$out"
  if [ "$ok" -ne 1 ]; then
    echo "NOT correctly signed: $apk"
    status=1
  fi
done
if [ "$found" -ne 1 ]; then
  echo "no APK found"
  status=1
fi
exit $status
