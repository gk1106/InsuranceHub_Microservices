package com.insurancehub.gateway.security;

import com.insurancehub.gateway.config.HubSecurityProperties;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

// Shared by IpAllowlistFilter and RequestAuditFilter - one place decides where the client IP
// comes from, so the allowlist check and the audit row can never disagree about it.
@Component
public class ClientIpResolver {

  private static final String FORWARDED_FOR_HEADER = "X-Forwarded-For";

  private final boolean trustForwardedFor;

  public ClientIpResolver(HubSecurityProperties properties) {
    this.trustForwardedFor = properties.trustForwardedFor();
  }

  public String resolve(HttpServletRequest request) {
    if (trustForwardedFor) {
      String forwardedFor = request.getHeader(FORWARDED_FOR_HEADER);
      if (forwardedFor != null && !forwardedFor.isBlank()) {
        // Leftmost entry is the original client (subsequent entries are intermediate proxies).
        return forwardedFor.split(",")[0].trim();
      }
    }
    return request.getRemoteAddr();
  }
}
