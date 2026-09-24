package com.insurancehub.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

// Renamed from a plain *Tests.java smoke test once a real datasource (JPA/Flyway, commit 2)
// meant "does the context load" could no longer be answered without one - *IT.java so it runs
// through failsafe, alongside every other Testcontainers-backed test, not through surefire's
// otherwise-fast `mvn test` phase.
@SpringBootTest
class HubGatewayApplicationIT extends AbstractHubGatewayIT {

  @Test
  void contextLoads() {}
}
