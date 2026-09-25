package com.insurancehub.claims.application;

import java.math.BigDecimal;
import java.time.LocalDate;

// reqId/inspId/txnId come from the internal request's X-Req-Id/X-Insp-Id/X-Txn-Id headers,
// claimNum from the path. All settlement fields are optional - a null one means "not provided
// this round" (see Claim.applyStatusUpdate).
public record UpdateClaimStatusCommand(
    String reqId,
    String inspId,
    String txnId,
    String traceparent,
    String claimNum,
    String policyNum,
    String claimStatus,
    BigDecimal settledAmt,
    String claimCode,
    LocalDate finalizationDate,
    Integer osAgeing,
    String requireDetails,
    LocalDate repuCancelDate,
    String reasonOfClosure) {}
