#!/usr/bin/env bash
# Generates local dev-only RSA-2048 keypairs into ./.secrets/ (git-ignored, never committed):
# one for the bank (hub-gateway itself) and one per sample insurer. api-contract.md §2 uses the
# *same* RSA key pair for both signing (RS256) and encryption (RSA-OAEP-256) per party - the
# bank's key signs outbound JWS and decrypts inbound JWE; each insurer's key signs its own
# outbound JWS, and its public half is what the bank encrypts responses *to*.
#
# Private keys are PKCS#8 (`-----BEGIN PRIVATE KEY-----`), generated with `openssl genpkey`, not
# `openssl genrsa` (which produces PKCS#1, a different encoding crypto.PemKeys - and plain
# java.security.KeyFactory generally - doesn't accept directly).
set -euo pipefail

OUT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/.secrets"
mkdir -p "$OUT_DIR"

gen_keypair() {
  local name="$1"
  openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 \
    -out "$OUT_DIR/${name}-private.pem" 2>/dev/null
  openssl pkey -in "$OUT_DIR/${name}-private.pem" -pubout -out "$OUT_DIR/${name}-public.pem" 2>/dev/null
}

for party in bank insp001 insp002 insp003; do
  gen_keypair "$party"
  echo "generated $party RSA-2048 keypair"
done

chmod 600 "$OUT_DIR"/*-private.pem
echo "dev keys written to $OUT_DIR (git-ignored)"
