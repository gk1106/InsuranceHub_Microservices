package com.insurancehub.policy;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

// Real MySQL, not H2 (see testing-and-deploy.md); no Kafka container needed until phase 7 adds a
// listener.
@SpringBootTest
@Testcontainers
class PolicyServiceApplicationTests {

  @Container @ServiceConnection static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4");

  @Test
  void contextLoads() {}
}
