package com.insurancehub.common.web;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

// Defaults to false (don't trust inbound X-Req-Id/X-Insp-Id/X-Txn-Id headers) so a service that
// forgets to set this explicitly fails safe rather than silently trusting spoofable headers.
// Domain services (policy-service, claims-service) must set
// hub.correlation.trust-inbound-headers=true; hub-gateway can rely on the default.
@ConfigurationProperties(prefix = "hub.correlation")
public record CorrelationProperties(@DefaultValue("false") boolean trustInboundHeaders) {}
