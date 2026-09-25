package com.insurancehub.policy.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.config.TopicBuilder;

// local only (service-design.md §5): in AWS, MSK topics are provisioned by Terraform, not code.
// Reads the topic name from OutboxRelayProperties (registered unconditionally by OutboxRelay
// itself, not here - this class is @Profile("local")-gated and every profile needs the relay's
// own properties to exist) rather than a second hardcoded literal, so the name the relay
// publishes to and the name this creates locally can never drift apart.
@Configuration
@Profile("local")
public class KafkaTopicConfig {

  @Bean
  NewTopic policyEventsTopic(OutboxRelayProperties properties) {
    return TopicBuilder.name(properties.topic()).partitions(3).replicas(1).build();
  }
}
