package com.insurancehub.gateway.config;

import com.insurancehub.common.web.CorrelationPropagationInterceptor;
import com.insurancehub.gateway.infrastructure.client.InternalAuthHeaderInterceptor;
import com.insurancehub.gateway.infrastructure.client.PolicyServiceHttpApi;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

// Same pattern as claims-service's own PolicyServiceClientConfig (phase 4a/4b) - built from the
// Boot-managed RestClient.Builder (not a bare RestClient.builder()) so this client gets its own
// explicit connect/read timeouts (cross-cutting.md §6) *and* keeps whatever auto-instrumentation
// Boot applies to that bean - phase 8 found this client was bypassing Micrometer's client-side
// trace-context propagation entirely by starting from RestClient.builder() instead of the
// injected builder bean; fixed here so an outbound call actually carries a traceparent header.
// Single-class @EnableConfigurationProperties only - the array form ({A.class, B.class}) breaks
// annotation-metadata resolution at context-refresh time under this Boot version
// (IllegalArgumentException: Could not find class [...], surfaced only by a real
// @SpringBootTest context load, not by compilation). InternalAuthProperties is registered by
// InternalAuthConfig instead - Spring dedupes @ConfigurationProperties bean registration by
// type, so declaring it there is enough for it to be injectable here too.
@Configuration
@EnableConfigurationProperties(PolicyServiceClientProperties.class)
public class PolicyServiceClientConfig {

  @Bean
  RestClient policyServiceRestClient(
      RestClient.Builder builder,
      PolicyServiceClientProperties properties,
      InternalAuthProperties internalAuthProperties) {
    HttpClientSettings settings =
        HttpClientSettings.defaults()
            .withConnectTimeout(properties.connectTimeout())
            .withReadTimeout(properties.readTimeout());
    ClientHttpRequestFactory requestFactory =
        ClientHttpRequestFactoryBuilder.detect().build(settings);
    return builder
        .baseUrl(properties.baseUrl())
        .requestFactory(requestFactory)
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
