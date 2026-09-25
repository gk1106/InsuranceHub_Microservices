package com.insurancehub.gateway.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

// docs/adr/0007-internal-service-auth.md. Same literal default across all three services'
// base application.yml so ITs (no compose, no env var) work with zero extra wiring; a real
// deployment overrides INTERNAL_AUTH_SECRET identically on all three (env var locally,
// Secrets Manager from phase 9).
@ConfigurationProperties("hub.internal-auth")
@Validated
public record InternalAuthProperties(@NotBlank String secret) {}
