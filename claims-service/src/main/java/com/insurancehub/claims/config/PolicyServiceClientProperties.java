package com.insurancehub.claims.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

// cross-cutting.md §3: typed @ConfigurationProperties, no @Value scattered across the code.
@ConfigurationProperties(prefix = "policy-service")
public record PolicyServiceClientProperties(
    String baseUrl, Duration connectTimeout, Duration readTimeout) {}
