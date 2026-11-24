#!/usr/bin/env bash
# Writes a throwaway RSA key for signing access tokens on a laptop, into
# secrets/ (git ignores it). Never use a key from here in production.
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p secrets
if [ -f secrets/auth-signing-key.pem ]; then
  echo "secrets/auth-signing-key.pem already exists"
  exit 0
fi
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out secrets/auth-signing-key.pem 2>/dev/null
chmod 600 secrets/auth-signing-key.pem
echo "wrote secrets/auth-signing-key.pem"
