#!/bin/sh
# Creates YOUR private signing key for TaskMan and prints what GitHub Actions needs.
#
#   Termux:  pkg install openjdk-17      (one time)
#            sh dev/make-keystore.sh
#
# Keep the generated .jks file and its password safe (a backup!): without them the app can never be
# updated in place, and anyone who gets them can publish "updates" of your root-privileged app.
set -e
cd "$(dirname "$0")/.."

command -v keytool >/dev/null 2>&1 || { echo "keytool not found. In Termux: pkg install openjdk-17"; exit 1; }

OUT="keystore/taskman-release.jks"
ALIAS="taskman"
if [ -e "$OUT" ]; then
  echo "$OUT already exists, it will not be overwritten."
  exit 1
fi
mkdir -p keystore

# random password: 24 letters and digits
PASS=$(head -c 512 /dev/urandom | base64 | tr -dc 'A-Za-z0-9' | head -c 24)
if [ "${#PASS}" -ne 24 ]; then
  echo "could not generate a password"
  exit 1
fi

# -J-Dkeystore.pkcs12.legacy keeps the file readable by every JDK Gradle may run on
keytool -genkeypair -J-Dkeystore.pkcs12.legacy \
  -keystore "$OUT" -storetype PKCS12 -storepass "$PASS" -keypass "$PASS" \
  -alias "$ALIAS" -keyalg RSA -keysize 3072 -sigalg SHA256withRSA -validity 36500 \
  -dname "CN=TaskMan, O=Personal" >/dev/null 2>&1

# local builds (Android Studio, ./gradlew) pick the key up from this git-ignored file
if [ ! -e keystore.properties ]; then
  cat > keystore.properties <<PROPS
storeFile=$OUT
storePassword=$PASS
keyAlias=$ALIAS
keyPassword=$PASS
PROPS
  echo "Wrote keystore.properties (git-ignored): local builds will use the new key."
fi

echo
echo "Key created: $OUT   (BACK IT UP, it cannot be recreated)"
echo
echo "GitHub: repository > Settings > Secrets and variables > Actions > New repository secret."
echo "Create these four secrets:"
echo
echo "  KEYSTORE_PASSWORD = $PASS"
echo "  KEY_PASSWORD      = $PASS"
echo "  KEY_ALIAS         = $ALIAS"
echo "  KEYSTORE_BASE64   = the long text below (one line, copy all of it)"
echo
base64 "$OUT" | tr -d '\n'
echo
echo
echo "Certificate fingerprint (the workflow prints the same one for every APK it builds):"
keytool -list -keystore "$OUT" -storepass "$PASS" 2>/dev/null | grep -i "fingerprint" || true
