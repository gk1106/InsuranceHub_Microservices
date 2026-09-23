package com.insurancehub.claims.config;

import com.insurancehub.claims.domain.ClaimStatusPolicy;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(ClaimStatusProperties.class)
public class ClaimStatusConfig {

  @Bean
  ClaimStatusPolicy claimStatusPolicy(ClaimStatusProperties properties) {
    return new ClaimStatusPolicy(properties.terminal(), properties.transitions());
  }
}
