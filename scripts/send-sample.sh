#!/usr/bin/env bash
# Sends a sample request for the given appStatusCode (01/02/03/04) through hub-gateway.
# Placeholder: hub-gateway has no routed endpoints yet (phase 5) and no JWS/JWE round-trip
# yet (phase 6). This script becomes real in phase 6, once scripts/gen-dev-keys.sh output
# can actually be used to sign/encrypt a request and decrypt/verify the response.
set -euo pipefail

echo "send-sample.sh is not implemented yet (lands in phase 6: hub-gateway crypto)." >&2
echo "Requested code: ${1:-<none>}" >&2
exit 1
