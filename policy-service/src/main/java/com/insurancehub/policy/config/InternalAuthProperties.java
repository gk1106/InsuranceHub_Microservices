package com.insurancehub.policy.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

// docs/adr/0007-internal-service-auth.md. Same literal default across all three services so
// ITs (no compose, no env var) work with zero extra wiring.
@ConfigurationProperties("hub.internal-auth")
@Validated
public record InternalAuthProperties(@NotBlank String secret) {}
