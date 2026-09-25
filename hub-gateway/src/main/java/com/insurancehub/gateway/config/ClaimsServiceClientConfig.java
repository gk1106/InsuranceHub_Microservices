package com.insurancehub.gateway.config;

import com.insurancehub.common.web.CorrelationPropagationInterceptor;
import com.insurancehub.gateway.infrastructure.client.ClaimsServiceHttpApi;
import com.insurancehub.gateway.infrastructure.client.InternalAuthHeaderInterceptor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

// See PolicyServiceClientConfig's own comment: built from the injected RestClient.Builder bean
// (not a bare RestClient.builder()), which is what keeps Micrometer's client-side observation
// instrumentation (and therefore traceparent propagation) attached to this client.
// Single-class @EnableConfigurationProperties only - see PolicyServiceClientConfig's own
// comment. InternalAuthProperties is registered by InternalAuthConfig instead.
@Configuration
@EnableConfigurationProperties(ClaimsServiceClientProperties.class)
public class ClaimsServiceClientConfig {

  @Bean
  RestClient claimsServiceRestClient(
      RestClient.Builder builder,
      ClaimsServiceClientProperties properties,
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
  ClaimsServiceHttpApi claimsServiceHttpApi(RestClient claimsServiceRestClient) {
    RestClientAdapter adapter = RestClientAdapter.create(claimsServiceRestClient);
    HttpServiceProxyFactory factory = HttpServiceProxyFactory.builderFor(adapter).build();
    return factory.createClient(ClaimsServiceHttpApi.class);
  }
}
