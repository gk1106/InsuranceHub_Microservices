package com.insurancehub.claims.config;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

// cross-cutting.md §3: typed @ConfigurationProperties, no @Value scattered across the code.
// service-design.md §3's ClaimStatusPolicy config, bound here and handed to the domain rule
// class (domain.ClaimStatusPolicy) by ClaimStatusConfig - the domain class itself stays
// Spring-free.
@ConfigurationProperties(prefix = "claims.status")
public record ClaimStatusProperties(Set<String> terminal, Map<String, List<String>> transitions) {}
