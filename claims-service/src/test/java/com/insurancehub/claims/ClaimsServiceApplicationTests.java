package com.insurancehub.claims;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

// Real MySQL, not H2 (see testing-and-deploy.md); no Kafka container needed until phase 7 adds a
// listener.
@SpringBootTest
@Testcontainers
class ClaimsServiceApplicationTests {

  @Container @ServiceConnection static MySQLContainer mysql = new MySQLContainer("mysql:8.4");

  @Test
  void contextLoads() {}
}
