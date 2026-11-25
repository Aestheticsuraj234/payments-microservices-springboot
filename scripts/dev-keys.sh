#!/usr/bin/env bash
# Writes a throwaway RSA key for signing access tokens on a laptop, into
# secrets/ (git ignores it). Never use a key from here in production.
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p secrets
if [ -f secrets/auth-signing-key.pem ]; then
  chmod 644 secrets/auth-signing-key.pem   # older versions of this script wrote 600
  echo "secrets/auth-signing-key.pem already exists"
  exit 0
fi
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out secrets/auth-signing-key.pem 2>/dev/null
# Readable by others: Compose mounts this file into the auth-service container,
# which runs as a non-root user (uid 10001). On Linux the mount keeps this
# file's owner and mode, so 600 would lock that user out. Fine for a throwaway
# laptop key; a real platform (a Kubernetes Secret) sets ownership for you.
chmod 644 secrets/auth-signing-key.pem
echo "wrote secrets/auth-signing-key.pem"
