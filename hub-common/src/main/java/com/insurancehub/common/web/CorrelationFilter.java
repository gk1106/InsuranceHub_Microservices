package com.insurancehub.common.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

// Reads X-Req-Id/X-Insp-Id/X-Txn-Id into MDC and clears it in finally.
//
// trustInboundHeaders distinguishes two callers with very different trust levels:
// - false (hub-gateway): the insurer's raw request is untrusted input. reqId/inspId are never
//   read from headers here - they come from the decrypted body and the validated JWT
//   respectively, set into MDC later by gateway-specific code, well after this filter runs.
//   Only txnId is generated here, since the gateway is where correlation begins.
// - true (policy-service, claims-service): the gateway is the only caller (private subnets,
//   security groups), and it always sets all three headers - so trust and read them directly,
//   never generating a fallback.
//
// Every value that reaches MDC, from a header or generated, is sanitized first: log-injection
// (CRLF, control characters) is possible via a spoofed header, so whitelisting to
// [A-Za-z0-9._-] and capping length closes that off regardless of trust mode.
public class CorrelationFilter extends OncePerRequestFilter {

  private static final Pattern DISALLOWED = Pattern.compile("[^A-Za-z0-9._-]");
  private static final int MAX_VALUE_LENGTH = 64;

  private final boolean trustInboundHeaders;
  private final Supplier<String> txnIdGenerator;

  public CorrelationFilter(boolean trustInboundHeaders) {
    this(trustInboundHeaders, Ulid::generate);
  }

  // txnIdGenerator is overridable for tests; production code should use the single-arg
  // constructor, which defaults to a real ULID (docs/adr/0002-txn-id-format.md).
  public CorrelationFilter(boolean trustInboundHeaders, Supplier<String> txnIdGenerator) {
    this.trustInboundHeaders = trustInboundHeaders;
    this.txnIdGenerator = txnIdGenerator;
  }

  public boolean trustInboundHeaders() {
    return trustInboundHeaders;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    try {
      if (trustInboundHeaders) {
        putIfPresent(HubHeaders.MDC_REQ_ID, request.getHeader(HubHeaders.REQ_ID));
        putIfPresent(HubHeaders.MDC_INSP_ID, request.getHeader(HubHeaders.INSP_ID));
        String txnId = sanitize(request.getHeader(HubHeaders.TXN_ID));
        MDC.put(HubHeaders.MDC_TXN_ID, txnId != null ? txnId : sanitize(txnIdGenerator.get()));
      } else {
        MDC.put(HubHeaders.MDC_TXN_ID, sanitize(txnIdGenerator.get()));
      }
      filterChain.doFilter(request, response);
    } finally {
      MDC.remove(HubHeaders.MDC_REQ_ID);
      MDC.remove(HubHeaders.MDC_INSP_ID);
      MDC.remove(HubHeaders.MDC_TXN_ID);
    }
  }

  private static void putIfPresent(String mdcKey, String rawValue) {
    String value = sanitize(rawValue);
    if (value != null) {
      MDC.put(mdcKey, value);
    }
  }

  private static String sanitize(String value) {
    if (value == null) {
      return null;
    }
    String stripped = DISALLOWED.matcher(value).replaceAll("");
    return stripped.length() > MAX_VALUE_LENGTH
        ? stripped.substring(0, MAX_VALUE_LENGTH)
        : stripped;
  }
}
