package com.insurancehub.gateway.config;

import com.insurancehub.common.web.CorrelationPropagationInterceptor;
import com.insurancehub.gateway.infrastructure.client.ClaimsServiceHttpApi;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

@Configuration
@EnableConfigurationProperties(ClaimsServiceClientProperties.class)
public class ClaimsServiceClientConfig {

  @Bean
  RestClient claimsServiceRestClient(ClaimsServiceClientProperties properties) {
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
  ClaimsServiceHttpApi claimsServiceHttpApi(RestClient claimsServiceRestClient) {
    RestClientAdapter adapter = RestClientAdapter.create(claimsServiceRestClient);
    HttpServiceProxyFactory factory = HttpServiceProxyFactory.builderFor(adapter).build();
    return factory.createClient(ClaimsServiceHttpApi.class);
  }
}
