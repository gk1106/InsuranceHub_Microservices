package com.insurancehub.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

// cross-cutting.md §4: X-Forwarded-For is only trusted when the request arrives through the
// trusted ALB (server.forward-headers-strategy=native, ALB's CIDR) - off by default, on only in
// prod's own config.
@ConfigurationProperties(prefix = "hub.security")
public record HubSecurityProperties(boolean trustForwardedFor) {}
