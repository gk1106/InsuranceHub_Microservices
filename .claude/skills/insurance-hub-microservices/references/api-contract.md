# External API contract (Insurance Hub spec v1.3)

This is the contract insurers call. Only `hub-gateway` knows this shape.

## Contents
1. Endpoints and service codes
2. Transport, auth, envelope
3. Request fields
4. Response
5. Error catalogue (project-defined, provisional)
6. Corrected sample requests
7. Spec quirks you must handle
8. Open questions to track

## 1. Endpoints and service codes

| Path | serviceType | appStatusCode | Routed to |
|---|---|---|---|
| `POST /v1/policydetail` | `NewPolicyService` | `01` | policy-service `POST /internal/policies` |
| `POST /v1/policydetail` | `RenewalService` | `02` | policy-service `POST /internal/policies/{policyNum}/renewals` |
| `POST /v1/policydetail` | `ClaimService` | `03` | claims-service `POST /internal/claims` |
| `POST /v1/policydetail/claimupdatestatus` | `ClaimStatusService` | `04` | claims-service `PATCH /internal/claims/{claimNum}/status` |

Reject a mismatch between `serviceType` and `appStatusCode` (e.g., `RenewalService` with
`03`) with `INVALID_SERVICE_CODE`. Accept the 04 path with and without a trailing slash.

Known insurers (`inspId` → `inspName`): `INSP001` universalsompo, `INSP002` sbigeneral,
`INSP003` nivabupa. Keep this in config (`hub.insurers[]`), not code. Each entry also holds
the insurer's OAuth client id, public key reference and IP allowlist.

## 2. Transport, auth, envelope

HTTPS only. JSON only. `Authorization: Bearer <token>`. Tokens are issued with OAuth2
`client_credentials`, scope `Insurance`. Locally, Keycloak issues them. The gateway validates
the JWT, requires scope `Insurance`, and maps the token's client id to an `inspId`. A request
whose `header.inspId` doesn't match the token's insurer is rejected with `INSURER_MISMATCH`.

Request and response bodies are `{"enc": "<string>"}`.

Request, client side: random 256-bit key → payload encrypted AES-256-GCM → key wrapped with
the bank public key RSA-OAEP-256 (i.e., a JWE with `alg=RSA-OAEP-256`, `enc=A256GCM`) → the
JWE compact string signed with the client private key RS256 (JWS) → final JWS Base64-encoded.

Bank side: Base64-decode → verify JWS with the insurer's public key → decrypt JWE with the
bank private key → plain JSON.

Response: the same in reverse. JWE for the insurer's public key, JWS signed with the bank
private key, Base64.

## 3. Request fields

Plain payload shape for codes 01–03: `{ header, policyDetails, claimDetails }`. For 04:
`{ header, claimDetails }`. Every value is a JSON string.

`header`: `reqId`, `serviceType`, `appStatusCode`, `inspId`, `inspName`.

`policyDetails`: `regionCode`, `regionName`, `branchCode`, `branchName`, `cif`, `accountNum`,
`insuranceType`, `insuranceName`, `applicationNum`, `policyNum`, `name`, `mobileNum`,
`address`, `applicationStatus`, `issueDate`, `startDate`, `expiryDate`, `netPremium`,
`gstAmt`, `grossPremium`, `sumInsured`, `loanAcctNum`, `specPerNum`, `specPerName`,
`commissionPer`, `commissionAmt`.

`claimDetails` (03): `claimNum`, `claimType`, `lossDisc`, `natureOfLoss`, `lossCity`,
`dateOfLoss`, `intimationDate`, `claimedAmt`, `claimStatus`, `claimCode`.

`claimDetails` (04): `policyNum`, `claimNum`, `claimType`, `intimationDate`, `claimedAmt`,
`settledAmt`, `claimStatus`, `claimCode`, `finalizationDate`, `osAgeing`, `requireDetails`,
`repuCancelDate`, `reasonOfClosure`.

### Validation (Bean Validation groups `NewPolicy`, `Renewal`, `Claim`, `ClaimStatus`)

Dates must match `^\d{2}/\d{2}/\d{4}$` and parse as a real date. Amounts must match
`^\d{1,13}(\.\d{1,2})?$`. `commissionPer` is 0–100. `mobileNum` must be 10 digits.

Required for 01/02: header fields, `policyNum`, `cif`, `name`, `insuranceType`, `startDate`,
`expiryDate`, `netPremium`, `grossPremium`, `sumInsured`. Also `startDate <= expiryDate`.

Required for 03: header fields, `policyNum`, `claimNum`, `claimType`, `dateOfLoss`,
`intimationDate`, `claimedAmt`. Also `dateOfLoss <= intimationDate`.

Required for 04: header fields, `policyNum`, `claimNum`, `claimStatus`.

For 01–02, `claimDetails` arrives with empty strings. Treat empty string as null.

## 4. Response

```json
{ "errorDesc": "SUCCESS", "respCode": "200", "status": "S", "txnId": "01J...", "reqId": "REQ987654321" }
```

`status` is `S` or `F`. Use lowercase `status` (as the sample does), even though the field
table says `Status`. The gateway generates `txnId` as a ULID when it receives the request.
For an idempotent replay, the domain service returns the ORIGINAL `txnId`, and the gateway
passes that one back.

HTTP status mirrors `respCode` (200/4xx/5xx). The body always carries `respCode` too.
Record this as open question Q2.

Failures before trust is established (invalid/expired token, IP not allowed, signature
invalid, undecryptable) return a PLAIN JSON body like the spec's sample:
`{"errorDesc":"Token Expired","respCode":"401","status":"F"}`. Every other response is
encrypted and signed.

## 5. Error catalogue (`HubErrorCode` in hub-common — provisional)

| Code | respCode | errorDesc | When |
|---|---|---|---|
| SUCCESS | 200 | SUCCESS | |
| INVALID_JSON | 400 | Invalid Json Format | Unparseable decrypted payload |
| VALIDATION_FAILED | 400 | Validation failed: <field> | Bean Validation errors (first 5 fields, no values) |
| INVALID_SERVICE_CODE | 400 | Invalid serviceType/appStatusCode | Unknown or mismatched code |
| TOKEN_INVALID | 401 | Invalid token | |
| TOKEN_EXPIRED | 401 | Token Expired | |
| IP_NOT_ALLOWED | 403 | IP not allowed | |
| INSURER_MISMATCH | 403 | Insurer not authorised for inspId | |
| SIGNATURE_INVALID | 400 | Signature verification failed | |
| DECRYPTION_FAILED | 400 | Decryption failed | |
| POLICY_NOT_FOUND | 404 | Policy not found | 02, 03, 04 |
| CLAIM_NOT_FOUND | 404 | Claim not found | 04 |
| POLICY_ALREADY_EXISTS | 409 | Policy already exists | 01, same policyNum with a different reqId |
| CLAIM_ALREADY_EXISTS | 409 | Claim already exists | 03, same claimNum with a different reqId |
| POLICY_NOT_ACTIVE | 422 | Policy not active on date of loss | 03 |
| INVALID_STATUS_TRANSITION | 422 | Invalid claim status transition | 04 |
| RENEWAL_NOT_ALLOWED | 422 | Renewal term overlaps existing term | 02 |
| DOWNSTREAM_UNAVAILABLE | 503 | Service temporarily unavailable | Circuit open or timeout |
| INTERNAL_ERROR | 500 | Internal error | Anything unmapped (never leak stack traces) |

## 6. Corrected sample requests (the spec's samples have JSON syntax errors)

01 NewPolicyService:
```json
{
  "header": { "reqId": "REQ987654321", "serviceType": "NewPolicyService", "appStatusCode": "01",
              "inspId": "INSP001", "inspName": "universalsompo" },
  "policyDetails": {
    "regionCode": "RC01", "regionName": "South Region", "branchCode": "BR102",
    "branchName": "Chennai Main Branch", "cif": "CIF456789", "accountNum": "ACC99887766",
    "insuranceType": "GENERAL", "insuranceName": "Motor Insurance", "applicationNum": "APP112233",
    "policyNum": "POL445566", "name": "Ravi Kumar", "mobileNum": "9876543210",
    "address": "12, MG Road, Chennai, TN", "applicationStatus": "ACTIVE",
    "issueDate": "10/05/2024", "startDate": "15/05/2024", "expiryDate": "14/05/2025",
    "netPremium": "15000", "gstAmt": "2700", "grossPremium": "17700", "sumInsured": "500000",
    "loanAcctNum": "LN22334455", "specPerNum": "SP7890", "specPerName": "Agent Suresh",
    "commissionPer": "5", "commissionAmt": "750"
  },
  "claimDetails": { "claimNum": "", "claimType": "", "lossDisc": "", "natureOfLoss": "",
                    "lossCity": "", "dateOfLoss": "", "intimationDate": "", "claimedAmt": "",
                    "claimStatus": "", "claimCode": "" }
}
```

04 ClaimStatusService:
```json
{
  "header": { "reqId": "REQ556677", "serviceType": "ClaimStatusService", "appStatusCode": "04",
              "inspId": "INSP001", "inspName": "universalsompo" },
  "claimDetails": {
    "policyNum": "POL445566", "claimNum": "CLM778899", "claimType": "ACCIDENT",
    "intimationDate": "21/08/2024", "claimedAmt": "85000", "settledAmt": "80000",
    "claimStatus": "CLOSED", "claimCode": "CLM-CLOSE-01", "finalizationDate": "30/08/2024",
    "osAgeing": "9", "requireDetails": "All documents submitted and verified",
    "repuCancelDate": "", "reasonOfClosure": "Claim settled successfully"
  }
}
```

Put these under `hub-gateway/src/test/resources/samples/` and reuse them in tests and
`scripts/send-sample.sh`.

## 7. Spec quirks you must handle

The field is `IntimationDate` in some places and `intimationDate` in others. Accept both
with `@JsonAlias`. Response `Status` vs `status`: emit lowercase. The spec's samples have
missing and trailing commas. Our fixtures are corrected, and the parser stays strict
(invalid JSON → `INVALID_JSON`). `applicationStatus` is described as "Fresh / Renewal" but
the samples use `ACTIVE`. Store it as a free string. The field table's `appStatusCode` for 04
says "Claim code:04". 04's `claimDetails` includes `policyNum`, so verify the claim belongs to
that policy.

## 8. Open questions (seed `docs/open-questions.md` with these)

Q1 Official respCodes for crypto/IP/HMAC failures. Q2 HTTP status vs always-200 with body
code. Q3 Allowed `claimStatus` values and transitions. Q4 Is `reqId` unique per insurer or
globally? (We assume per insurer.) Q5 Must `grossPremium == netPremium + gstAmt` be enforced?
(We log a warning only.) Q6 Must `claimedAmt <= sumInsured`? (We don't enforce it.) Q7 Is the
"HMAC" in the error list separate from the JWS signature? Q8 Can a renewal change `sumInsured`
or the insured's details? (We allow it and keep term history.)
