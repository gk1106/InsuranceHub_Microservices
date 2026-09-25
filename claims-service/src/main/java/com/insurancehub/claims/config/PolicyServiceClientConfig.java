package com.insurancehub.claims.config;

import com.insurancehub.claims.infrastructure.client.InternalAuthHeaderInterceptor;
import com.insurancehub.claims.infrastructure.client.PolicyServiceHttpApi;
import com.insurancehub.common.web.CorrelationPropagationInterceptor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

// Built from the injected RestClient.Builder bean (not a bare RestClient.builder()), so this
// client gets its own explicit connect/read timeouts (cross-cutting.md §6) *and* keeps whatever
// auto-instrumentation Boot applies to that bean - phase 8 found this client was bypassing
// Micrometer's client-side observation instrumentation entirely by starting from
// RestClient.builder() instead of the injected builder bean; fixed here so an outbound call
// actually carries a traceparent header once tracing is added.
// Single-class @EnableConfigurationProperties only - phase 8 found the array form
// ({A.class, B.class}) breaks annotation-metadata resolution at context-refresh time under this
// Boot version (IllegalArgumentException: Could not find class [PolicyServiceClientProperties],
// surfaced only by a real @SpringBootTest context load, not by compilation). InternalAuthProperties
// is registered by InternalAuthFilterConfig instead (same module) - Spring dedupes
// @ConfigurationProperties bean registration by type, so declaring it there is enough for it to
// be injectable here too.
@Configuration
@EnableConfigurationProperties(PolicyServiceClientProperties.class)
public class PolicyServiceClientConfig {

  @Bean
  RestClient policyServiceRestClient(
      RestClient.Builder builder,
      PolicyServiceClientProperties properties,
      InternalAuthProperties internalAuthProperties) {
    // Boot 4.1.1 renamed ClientHttpRequestFactorySettings to HttpClientSettings (found at
    // compile time - the old name doesn't exist in spring-boot-http-client 4.1.1).
    HttpClientSettings settings =
        HttpClientSettings.defaults()
            .withConnectTimeout(properties.connectTimeout())
            .withReadTimeout(properties.readTimeout());
    ClientHttpRequestFactory requestFactory =
        ClientHttpRequestFactoryBuilder.detect().build(settings);
    return builder
        .baseUrl(properties.baseUrl())
        .requestFactory(requestFactory)
        // Propagates the current request's reqId/inspId/txnId onto the outgoing call so
        // policy-service's CorrelationFilter sees the same correlation values.
        .requestInterceptor(new CorrelationPropagationInterceptor())
        .requestInterceptor(new InternalAuthHeaderInterceptor(internalAuthProperties.secret()))
        .build();
  }

  @Bean
  PolicyServiceHttpApi policyServiceHttpApi(RestClient policyServiceRestClient) {
    RestClientAdapter adapter = RestClientAdapter.create(policyServiceRestClient);
    HttpServiceProxyFactory factory = HttpServiceProxyFactory.builderFor(adapter).build();
    return factory.createClient(PolicyServiceHttpApi.class);
  }
}
