#!/usr/bin/env bash
# Generates local dev-only JWS/JWE keypairs into ./.secrets/ (git-ignored, never committed).
# One signing (EC P-256, ES256) and one encryption (RSA-2048, RSA-OAEP-256) keypair per party:
# the gateway itself, and one fictitious insurer (insp001) used by scripts/send-sample.sh.
set -euo pipefail

OUT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/.secrets"
mkdir -p "$OUT_DIR"

gen_sign_keypair() {
  local name="$1"
  openssl ecparam -name prime256v1 -genkey -noout -out "$OUT_DIR/${name}-sign-private.pem"
  openssl ec -in "$OUT_DIR/${name}-sign-private.pem" -pubout -out "$OUT_DIR/${name}-sign-public.pem" 2>/dev/null
}

gen_enc_keypair() {
  local name="$1"
  openssl genrsa -out "$OUT_DIR/${name}-enc-private.pem" 2048 2>/dev/null
  openssl rsa -in "$OUT_DIR/${name}-enc-private.pem" -pubout -out "$OUT_DIR/${name}-enc-public.pem" 2>/dev/null
}

for party in gateway insp001; do
  gen_sign_keypair "$party"
  gen_enc_keypair "$party"
  echo "generated $party sign + enc keypairs"
done

chmod 600 "$OUT_DIR"/*-private.pem
echo "dev keys written to $OUT_DIR (git-ignored)"
