#!/usr/bin/env bash
# Sends a real, end-to-end signed+encrypted sample request for the given appStatusCode
# (01/02/03/04) through the running docker-compose hub-gateway stack, and decrypts+verifies the
# response. Requires: `docker compose up -d --build` already running, and
# `scripts/gen-dev-keys.sh` already run (this script never touches key material itself - it
# only reads what that script already wrote to .secrets/, which stays git-ignored).
set -euo pipefail

CODE="${1:-}"
if [[ -z "$CODE" ]]; then
  echo "Usage: $0 <01|02|03|04>" >&2
  exit 1
fi

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SECRETS_DIR="$ROOT_DIR/.secrets"
if [[ ! -f "$SECRETS_DIR/bank-public.pem" ]]; then
  echo "No keys found in $SECRETS_DIR - run scripts/gen-dev-keys.sh first." >&2
  exit 1
fi

GATEWAY_URL="http://localhost:8090"
KEYCLOAK_TOKEN_URL="http://localhost:8190/realms/insurancehub/protocol/openid-connect/token"
INSP_ID="INSP001"
INSP_CLIENT_ID="insp001-client"
INSP_CLIENT_SECRET="insp001-secret"

case "$CODE" in
  01)
    ENDPOINT="/v1/policydetail"
    PAYLOAD='{"header":{"reqId":"REQ-SAMPLE-01","serviceType":"NewPolicyService","appStatusCode":"01","inspId":"INSP001","inspName":"universalsompo"},"policyDetails":{"regionCode":"RC01","regionName":"South Region","branchCode":"BR102","branchName":"Chennai Main Branch","cif":"CIF456789","accountNum":"ACC99887766","insuranceType":"GENERAL","insuranceName":"Motor Insurance","applicationNum":"APP112233","policyNum":"POL-SAMPLE-01","name":"Ravi Kumar","mobileNum":"9876543210","address":"12, MG Road, Chennai, TN","applicationStatus":"ACTIVE","issueDate":"10/05/2024","startDate":"15/05/2024","expiryDate":"14/05/2025","netPremium":"15000","gstAmt":"2700","grossPremium":"17700","sumInsured":"500000","loanAcctNum":"LN22334455","specPerNum":"SP7890","specPerName":"Agent Suresh","commissionPer":"5","commissionAmt":"750"},"claimDetails":{"claimNum":"","claimType":"","lossDisc":"","natureOfLoss":"","lossCity":"","dateOfLoss":"","intimationDate":"","claimedAmt":"","claimStatus":"","claimCode":""}}'
    ;;
  02)
    ENDPOINT="/v1/policydetail"
    PAYLOAD='{"header":{"reqId":"REQ-SAMPLE-02","serviceType":"RenewalService","appStatusCode":"02","inspId":"INSP001","inspName":"universalsompo"},"policyDetails":{"regionCode":"RC01","regionName":"South Region","branchCode":"BR102","branchName":"Chennai Main Branch","cif":"CIF456789","accountNum":"ACC99887766","insuranceType":"GENERAL","insuranceName":"Motor Insurance","applicationNum":"APP112233","policyNum":"POL-SAMPLE-01","name":"Ravi Kumar","mobileNum":"9876543210","address":"12, MG Road, Chennai, TN","applicationStatus":"ACTIVE","issueDate":"10/05/2025","startDate":"15/05/2025","expiryDate":"14/05/2026","netPremium":"15500","gstAmt":"2790","grossPremium":"18290","sumInsured":"500000","loanAcctNum":"LN22334455","specPerNum":"SP7890","specPerName":"Agent Suresh","commissionPer":"5","commissionAmt":"775"},"claimDetails":{"claimNum":"","claimType":"","lossDisc":"","natureOfLoss":"","lossCity":"","dateOfLoss":"","intimationDate":"","claimedAmt":"","claimStatus":"","claimCode":""}}'
    ;;
  03)
    ENDPOINT="/v1/policydetail"
    PAYLOAD='{"header":{"reqId":"REQ-SAMPLE-03","serviceType":"ClaimService","appStatusCode":"03","inspId":"INSP001","inspName":"universalsompo"},"policyDetails":{"regionCode":"","regionName":"","branchCode":"","branchName":"","cif":"","accountNum":"","insuranceType":"","insuranceName":"","applicationNum":"","policyNum":"POL-SAMPLE-01","name":"","mobileNum":"","address":"","applicationStatus":"","issueDate":"","startDate":"","expiryDate":"","netPremium":"","gstAmt":"","grossPremium":"","sumInsured":"","loanAcctNum":"","specPerNum":"","specPerName":"","commissionPer":"","commissionAmt":""},"claimDetails":{"claimNum":"CLM-SAMPLE-01","claimType":"ACCIDENT","lossDisc":"Minor collision","natureOfLoss":"Collision","lossCity":"Chennai","dateOfLoss":"01/08/2024","intimationDate":"05/08/2024","claimedAmt":"85000","claimStatus":"","claimCode":""}}'
    ;;
  04)
    ENDPOINT="/v1/policydetail/claimupdatestatus"
    PAYLOAD='{"header":{"reqId":"REQ-SAMPLE-04","serviceType":"ClaimStatusService","appStatusCode":"04","inspId":"INSP001","inspName":"universalsompo"},"claimDetails":{"policyNum":"POL-SAMPLE-01","claimNum":"CLM-SAMPLE-01","claimType":"ACCIDENT","intimationDate":"21/08/2024","claimedAmt":"85000","settledAmt":"80000","claimStatus":"CLOSED","claimCode":"CLM-CLOSE-01","finalizationDate":"30/08/2024","osAgeing":"9","requireDetails":"All documents submitted and verified","repuCancelDate":"","reasonOfClosure":"Claim settled successfully"}}'
    ;;
  *)
    echo "Unknown code: $CODE (expected 01, 02, 03 or 04)" >&2
    exit 1
    ;;
esac

echo "Fetching a Keycloak token for $INSP_CLIENT_ID..." >&2
TOKEN=$(curl -sf -X POST "$KEYCLOAK_TOKEN_URL" \
  -d "grant_type=client_credentials" -d "client_id=$INSP_CLIENT_ID" -d "client_secret=$INSP_CLIENT_SECRET" \
  | grep -o '"access_token":"[^"]*"' | cut -d'"' -f4)
if [[ -z "$TOKEN" ]]; then
  echo "Failed to fetch a Keycloak token - is docker compose up?" >&2
  exit 1
fi

echo "Encrypting and signing the sample $CODE request (as $INSP_ID)..." >&2
# Paths relative to ROOT_DIR (not $SECRETS_DIR's absolute POSIX form) - git-bash's automatic
# path conversion for arguments passed to a Windows-native java.exe mangles a leading /c/...
# (drops the drive-letter colon), which a plain relative path never triggers.
ENC=$(echo -n "$PAYLOAD" | (cd "$ROOT_DIR" && ./mvnw -q -pl hub-gateway exec:java@envelope-cli \
  -Dexec.args="encrypt-and-sign .secrets/bank-public.pem .secrets/insp001-private.pem $INSP_ID"))

echo "Calling $GATEWAY_URL$ENDPOINT..." >&2
RESPONSE_ENC=$(curl -sf -X POST "$GATEWAY_URL$ENDPOINT" \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d "{\"enc\":\"$ENC\"}" | grep -o '"enc":"[^"]*"' | cut -d'"' -f4)

if [[ -z "$RESPONSE_ENC" ]]; then
  echo "Gateway returned no enc field - it may have rejected the request pre-trust." >&2
  exit 1
fi

echo "Decrypting and verifying the response (against the bank's public key)..." >&2
echo -n "$RESPONSE_ENC" | (cd "$ROOT_DIR" && ./mvnw -q -pl hub-gateway exec:java@envelope-cli \
  -Dexec.args="verify-and-decrypt .secrets/bank-public.pem .secrets/insp001-private.pem")
