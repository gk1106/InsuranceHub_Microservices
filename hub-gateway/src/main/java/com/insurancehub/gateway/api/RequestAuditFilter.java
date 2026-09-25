package com.insurancehub.gateway.api;

import com.insurancehub.common.web.HubHeaders;
import com.insurancehub.gateway.application.RequestAuditService;
import com.insurancehub.gateway.audit.AuditContext;
import com.insurancehub.gateway.security.ClientIpResolver;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

// Wraps the ENTIRE pipeline - registered outside Spring Security's own filter chain (see
// config.RequestAuditFilterConfig), right after CorrelationFilter, so it also sees pre-trust
// rejections (bad token, IP not allowed, oversized body) that never reach a controller. Nothing
// else ever writes an audit row directly; every other pipeline stage only populates AuditContext
// as it learns things.
//
// Order matters in the finally block: filterChain.doFilter() completes - the response is fully
// written and its status committed - BEFORE the audit write is even attempted. A failing audit
// write is caught locally (log ERROR with the audit fields as structured key-values, increment a
// counter) and can therefore never turn an already-decided response into a 500.
public class RequestAuditFilter extends OncePerRequestFilter {

  private static final Logger log = LoggerFactory.getLogger(RequestAuditFilter.class);
  // logging-and-monitoring.md §4: a dedicated logger, routed independently from ordinary
  // application logs by a CloudWatch subscription filter on logger_name (phase 9). One INFO
  // line per request, alongside (not instead of) the request_audit DB row below.
  private static final Logger auditLog = LoggerFactory.getLogger("insurancehub.audit");

  private final RequestAuditService auditService;
  private final ClientIpResolver clientIpResolver;
  private final Counter auditWriteFailureCounter;

  public RequestAuditFilter(
      RequestAuditService auditService,
      ClientIpResolver clientIpResolver,
      MeterRegistry meterRegistry) {
    this.auditService = auditService;
    this.clientIpResolver = clientIpResolver;
    this.auditWriteFailureCounter =
        Counter.builder("hub_gateway.audit.write.failures")
            .description("request_audit rows that failed to write")
            .register(meterRegistry);
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    AuditContext auditContext = AuditContext.attachTo(request);
    long startNanos = System.nanoTime();
    try {
      filterChain.doFilter(request, response);
    } finally {
      long latencyMs = (System.nanoTime() - startNanos) / 1_000_000;
      String txnId = MDC.get(HubHeaders.MDC_TXN_ID);
      String clientIp = clientIpResolver.resolve(request);
      int respCode = response.getStatus();
      // cross-cutting.md §2: "one INFO line per request at the gateway (code, respCode,
      // latency)". Structured key-values, not string concatenation, so respCode/latencyMs
      // become queryable JSON fields once logging.structured.format.console=logstash is on
      // (phase 8's json-logs profile); txnId/reqId/inspId/serviceType/traceId/spanId come from
      // MDC automatically as top-level fields.
      auditLog
          .atInfo()
          .addKeyValue("respCode", respCode)
          .addKeyValue("latencyMs", latencyMs)
          .log("request completed");
      try {
        auditService.record(
            txnId,
            auditContext.reqId(),
            auditContext.inspId(),
            auditContext.serviceType(),
            respCode,
            latencyMs,
            clientIp);
      } catch (Exception e) {
        log.error(
            "request_audit write failed txnId={} reqId={} inspId={} serviceType={} "
                + "respCode={} latencyMs={} clientIp={}",
            txnId,
            auditContext.reqId(),
            auditContext.inspId(),
            auditContext.serviceType(),
            respCode,
            latencyMs,
            clientIp,
            e);
        auditWriteFailureCounter.increment();
      } finally {
        // HubDispatcher sets this mid-pipeline (only once the code is resolved); CorrelationFilter
        // doesn't know about it and can't clear it, so the filter that wraps the whole pipeline
        // does instead - same reasoning as this class already wrapping audit's own try/finally.
        MDC.remove(HubHeaders.MDC_SERVICE_TYPE);
      }
    }
  }
}
