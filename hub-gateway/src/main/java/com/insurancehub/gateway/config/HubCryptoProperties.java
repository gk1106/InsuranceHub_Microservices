package com.insurancehub.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

// CLAUDE.md rule 8: crypto can be bypassed only in the local profile. base application.yml
// keeps enabled: true as an explicit default; only application-local.yml overrides it.
@ConfigurationProperties(prefix = "hub.crypto")
public record HubCryptoProperties(boolean enabled) {}
