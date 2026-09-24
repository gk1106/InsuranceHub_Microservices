package com.insurancehub.gateway.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "policy-service")
public record PolicyServiceClientProperties(
    String baseUrl, Duration connectTimeout, Duration readTimeout) {}
