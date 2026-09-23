package com.insurancehub.claims.application;

import java.math.BigDecimal;
import java.time.LocalDate;

// reqId/inspId/txnId come from the internal request's X-Req-Id/X-Insp-Id/X-Txn-Id headers
// (service-design.md §1), never from the body. lossDesc corrects api-contract.md's external
// "lossDisc" spelling - internal APIs use clean naming (same precedent as insuredName vs. the
// external "name").
public record RegisterClaimCommand(
    String reqId,
    String inspId,
    String txnId,
    String policyNum,
    String claimNum,
    String claimType,
    String lossDesc,
    String natureOfLoss,
    String lossCity,
    LocalDate dateOfLoss,
    LocalDate intimationDate,
    BigDecimal claimedAmt,
    String claimStatus,
    String claimCode) {}
