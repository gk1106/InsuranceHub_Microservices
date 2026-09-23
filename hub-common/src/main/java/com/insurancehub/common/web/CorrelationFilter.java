package com.insurancehub.common.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

// Reads X-Req-Id/X-Insp-Id/X-Txn-Id into MDC and clears it in finally. On an internal service,
// the gateway has already set all three. At the gateway's own entry point (the insurer's raw
// request), reqId/inspId aren't known yet - they live inside the encrypted body, decrypted
// later in the pipeline - so only txnId gets generated here when no header is present;
// reqId/inspId are simply left unset until gateway-specific code adds them after decryption.
// traceId/spanId (Micrometer's job) and serviceType (only known post-decrypt) are deliberately
// out of scope - see docs/adr/0001-tech-stack.md phase-1 notes.
public class CorrelationFilter extends OncePerRequestFilter {

  private final Supplier<String> txnIdGenerator;

  public CorrelationFilter() {
    this(() -> UUID.randomUUID().toString());
  }

  // txnIdGenerator is a placeholder for the eventual ULID generator (api-contract.md §4).
  public CorrelationFilter(Supplier<String> txnIdGenerator) {
    this.txnIdGenerator = txnIdGenerator;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    try {
      putIfPresent(HubHeaders.MDC_REQ_ID, request.getHeader(HubHeaders.REQ_ID));
      putIfPresent(HubHeaders.MDC_INSP_ID, request.getHeader(HubHeaders.INSP_ID));
      String txnId = request.getHeader(HubHeaders.TXN_ID);
      MDC.put(HubHeaders.MDC_TXN_ID, txnId != null ? txnId : txnIdGenerator.get());
      filterChain.doFilter(request, response);
    } finally {
      MDC.remove(HubHeaders.MDC_REQ_ID);
      MDC.remove(HubHeaders.MDC_INSP_ID);
      MDC.remove(HubHeaders.MDC_TXN_ID);
    }
  }

  private static void putIfPresent(String mdcKey, String value) {
    if (value != null) {
      MDC.put(mdcKey, value);
    }
  }
}
