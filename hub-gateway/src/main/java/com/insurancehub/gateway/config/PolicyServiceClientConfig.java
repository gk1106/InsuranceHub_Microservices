package com.insurancehub.gateway.config;

import com.insurancehub.common.web.CorrelationPropagationInterceptor;
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

// Same pattern as claims-service's own PolicyServiceClientConfig (phase 4a/4b) - built manually
// so this client gets its own explicit connect/read timeouts (cross-cutting.md §6) rather than
// layering property-driven customization onto an autoconfigured default.
@Configuration
@EnableConfigurationProperties(PolicyServiceClientProperties.class)
public class PolicyServiceClientConfig {

  @Bean
  RestClient policyServiceRestClient(PolicyServiceClientProperties properties) {
    HttpClientSettings settings =
        HttpClientSettings.defaults()
            .withConnectTimeout(properties.connectTimeout())
            .withReadTimeout(properties.readTimeout());
    ClientHttpRequestFactory requestFactory =
        ClientHttpRequestFactoryBuilder.detect().build(settings);
    return RestClient.builder()
        .baseUrl(properties.baseUrl())
        .requestFactory(requestFactory)
        .requestInterceptor(new CorrelationPropagationInterceptor())
        .build();
  }

  @Bean
  PolicyServiceHttpApi policyServiceHttpApi(RestClient policyServiceRestClient) {
    RestClientAdapter adapter = RestClientAdapter.create(policyServiceRestClient);
    HttpServiceProxyFactory factory = HttpServiceProxyFactory.builderFor(adapter).build();
    return factory.createClient(PolicyServiceHttpApi.class);
  }
}
