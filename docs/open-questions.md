# Open questions for the bank

Spec gaps hit during implementation, with the assumption made in the meantime. Confirm with
the bank and update/remove entries as they're answered. Seeded from
`references/api-contract.md` §8 before phase 1; assumptions get revisited as each phase
actually implements the affected behavior.

## Q1 — Official respCodes for crypto/IP/HMAC failures
Not finalized by the bank. **Assumption**: use the provisional codes in the `HubErrorCode`
table (`api-contract.md` §5) — 400 for signature/decryption failures, 401 for token issues,
403 for IP/insurer mismatch — until the bank confirms its own catalogue. Affects phase 6.

## Q2 — HTTP status vs. always-200 with a body error code
**Assumption**: HTTP status mirrors `respCode` (200/4xx/5xx), per `api-contract.md` §4 — the
body always carries `respCode` too, so a client reading only the body still works either way.
Affects phase 5 (gateway response mapping).

## Q3 — Allowed `claimStatus` values and transitions
Not finalized. **No assumption yet** — this blocks the claim status state machine
(`INVALID_STATUS_TRANSITION`). Must be resolved before phase 4 implements 04 (status update).

## Q4 — Is `reqId` unique per insurer or globally?
**Assumption**: per insurer. The idempotency key is `(inspId, reqId)`, not `reqId` alone
(CLAUDE.md rule 3). Affects phases 2–4 (`processed_request` unique constraint).

## Q5 — Must `grossPremium == netPremium + gstAmt` be enforced?
**Assumption**: no — log a warning only, don't reject the request. Affects phase 2 (create)
and phase 3 (renew).

## Q6 — Must `claimedAmt <= sumInsured`?
**Assumption**: not enforced. Affects phase 4 (claim registration).

## Q7 — Is the "HMAC" in the error list separate from the JWS signature?
**Assumption**: treat it as the same check as JWS signature verification
(`SIGNATURE_INVALID`) — no separate HMAC error code added — until the bank clarifies whether
a distinct HMAC step exists. Affects phase 6.

## Q8 — Can a renewal change `sumInsured` or the insured's details?
**Assumption**: yes — allowed, and term history is kept (each renewal is a new term row, not
an overwrite). Affects phase 3 (renewal schema and business rules).
