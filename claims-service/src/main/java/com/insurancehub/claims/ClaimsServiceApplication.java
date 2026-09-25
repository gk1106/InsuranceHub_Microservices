package com.insurancehub.claims;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

// @EnableScheduling: infrastructure/messaging/OutboxRelay's @Scheduled poll.
@SpringBootApplication
@EnableScheduling
public class ClaimsServiceApplication {

  public static void main(String[] args) {
    SpringApplication.run(ClaimsServiceApplication.class, args);
  }
}
