package com.insurancehub.gateway.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "claims-service")
public record ClaimsServiceClientProperties(
    String baseUrl, Duration connectTimeout, Duration readTimeout) {}
