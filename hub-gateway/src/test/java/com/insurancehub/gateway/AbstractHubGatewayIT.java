package com.insurancehub.gateway;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.MountableFile;

// Singleton Testcontainers pattern: one Keycloak container, started once via a static
// initializer (not @Testcontainers/@Container, which would restart it per test class), shared
// for the whole JVM by every IT class extending this. Seven-plus IT classes each paying
// Keycloak's own startup cost independently would make `mvn verify` painfully slow within a
// phase or two.
//
// Reads the exact same realm file docker-compose uses (../docker/keycloak/insurancehub-realm.json,
// relative to this module's working directory) rather than a duplicated test copy, so the test
// realm can never drift from the one docker-compose actually runs.
//
// No KC_HOSTNAME pinning here, unlike docker-compose - this test JVM and the embedded gateway
// under test reach Keycloak via the exact same localhost:{mappedPort} URL (see
// @DynamicPropertySource below), so the issuer-mismatch problem that pinning fixes in
// docker-compose never arises in the first place. See docs/adr for the KC_HOSTNAME ADR.
public abstract class AbstractHubGatewayIT {

  private static final GenericContainer<?> KEYCLOAK =
      new GenericContainer<>("quay.io/keycloak/keycloak:26.0")
          .withCommand("start-dev", "--import-realm")
          .withEnv("KEYCLOAK_ADMIN", "admin")
          .withEnv("KEYCLOAK_ADMIN_PASSWORD", "admin")
          .withCopyFileToContainer(
              MountableFile.forHostPath("../docker/keycloak/insurancehub-realm.json"),
              "/opt/keycloak/data/import/insurancehub-realm.json")
          .withExposedPorts(8080)
          .waitingFor(
              Wait.forHttp("/realms/insurancehub")
                  .forStatusCode(200)
                  .withStartupTimeout(Duration.ofMinutes(2)));

  static {
    KEYCLOAK.start();
  }

  @DynamicPropertySource
  static void keycloakProperties(DynamicPropertyRegistry registry) {
    registry.add(
        "spring.security.oauth2.resourceserver.jwt.issuer-uri", AbstractHubGatewayIT::issuerUri);
  }

  private static String issuerUri() {
    return "http://"
        + KEYCLOAK.getHost()
        + ":"
        + KEYCLOAK.getMappedPort(8080)
        + "/realms/insurancehub";
  }

  protected static String fetchToken(String clientId, String clientSecret) {
    MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
    form.add("grant_type", "client_credentials");
    form.add("client_id", clientId);
    form.add("client_secret", clientSecret);

    @SuppressWarnings("unchecked")
    Map<String, Object> response =
        RestClient.create()
            .post()
            .uri(issuerUri() + "/protocol/openid-connect/token")
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .body(form)
            .retrieve()
            .body(LinkedHashMap.class);
    return (String) response.get("access_token");
  }
}
