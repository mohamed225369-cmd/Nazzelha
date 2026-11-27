#!/usr/bin/env bash
# Run once on a trusted local computer with Java, OpenSSL and authenticated GitHub CLI.
set -euo pipefail
umask 077
repo="mohamed225369-cmd/Nazzelha"
gh auth status
existing="$(gh secret list --repo "$repo")"
if grep -Eq '^(NAZZELHA_SIGNING_KEY_B64|NAZZELHA_SIGNING_PASSWORD)[[:space:]]' <<< "$existing"; then
  echo "Signing secrets already exist. Do not replace them: updates need the original key."
  exit 1
fi
dir="$HOME/.nazzelha-signing"
if [ -e "$dir" ]; then
  echo "Signing backup folder already exists. Reuse or recover your existing key, never overwrite it."
  exit 1
fi
mkdir -m 700 "$dir"
openssl rand -hex 32 > "$dir/password.txt"
keytool -genkeypair -storetype PKCS12 -keystore "$dir/nazzelha.p12" \
  -alias nazzelha -keyalg RSA -keysize 3072 -validity 10000 \
  -dname "CN=Nazzelha Android Release" \
  -storepass:file "$dir/password.txt" -keypass:file "$dir/password.txt"
base64 < "$dir/nazzelha.p12" | tr -d '\n' | gh secret set NAZZELHA_SIGNING_KEY_B64 --repo "$repo"
gh secret set NAZZELHA_SIGNING_PASSWORD --repo "$repo" < "$dir/password.txt"
echo "Configured. Keep the signing folder securely backed up offline and never commit it."
