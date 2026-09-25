package com.insurancehub.gateway.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

// Sole registration point for InternalAuthProperties - PolicyServiceClientConfig and
// ClaimsServiceClientConfig both inject the resulting bean but deliberately don't declare
// @EnableConfigurationProperties for it themselves (see their own comments: the array form,
// {A.class, B.class}, breaks annotation-metadata resolution under this Boot version).
@Configuration
@EnableConfigurationProperties(InternalAuthProperties.class)
public class InternalAuthConfig {}
