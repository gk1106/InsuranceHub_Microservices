package com.insurancehub.claims.config;

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

// Built manually, not via Boot's autoconfigured RestClient.Builder - this client needs its own
// explicit connect/read timeouts (cross-cutting.md §6), which is most directly expressed by
// building the request factory here rather than layering property-driven customization onto an
// autoconfigured default.
@Configuration
@EnableConfigurationProperties(PolicyServiceClientProperties.class)
public class PolicyServiceClientConfig {

  @Bean
  RestClient policyServiceRestClient(PolicyServiceClientProperties properties) {
    // Boot 4.1.1 renamed ClientHttpRequestFactorySettings to HttpClientSettings (found at
    // compile time - the old name doesn't exist in spring-boot-http-client 4.1.1).
    HttpClientSettings settings =
        HttpClientSettings.defaults()
            .withConnectTimeout(properties.connectTimeout())
            .withReadTimeout(properties.readTimeout());
    ClientHttpRequestFactory requestFactory =
        ClientHttpRequestFactoryBuilder.detect().build(settings);
    return RestClient.builder()
        .baseUrl(properties.baseUrl())
        .requestFactory(requestFactory)
        // Propagates the current request's reqId/inspId/txnId onto the outgoing call so
        // policy-service's CorrelationFilter sees the same correlation values.
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
