package com.insurancehub.claims.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ClaimStatusPolicyTest {

  private static final Set<String> TERMINAL = Set.of("CLOSED", "REPUDIATED", "CANCELLED");
  private static final Map<String, List<String>> TRANSITIONS =
      Map.of(
          "REGISTERED", List.of("UNDER_PROCESS", "CLOSED", "REPUDIATED", "CANCELLED"),
          "UNDER_PROCESS",
              List.of("UNDER_PROCESS", "REQUIREMENT_PENDING", "CLOSED", "REPUDIATED", "CANCELLED"),
          "REQUIREMENT_PENDING", List.of("UNDER_PROCESS", "CLOSED", "REPUDIATED", "CANCELLED"));

  private final ClaimStatusPolicy policy = new ClaimStatusPolicy(TERMINAL, TRANSITIONS);

  @Test
  void allowsATransitionListedForTheSourceStatus() {
    assertThat(policy.isTransitionAllowed("REGISTERED", "UNDER_PROCESS")).isTrue();
  }

  @Test
  void rejectsATransitionNotListedForTheSourceStatus() {
    assertThat(policy.isTransitionAllowed("REGISTERED", "REQUIREMENT_PENDING")).isFalse();
  }

  @Test
  void rejectsAnyTransitionOutOfATerminalStatus() {
    assertThat(policy.isTransitionAllowed("CLOSED", "UNDER_PROCESS")).isFalse();
  }

  @Test
  void rejectsAnUnknownTargetStatus() {
    assertThat(policy.isTransitionAllowed("REGISTERED", "FOOBAR")).isFalse();
  }

  @Test
  void allowsASameStatusTransitionWhenTheConfigListsItExplicitly() {
    // docs/open-questions.md Q3: UNDER_PROCESS lists itself as an allowed target.
    assertThat(policy.isTransitionAllowed("UNDER_PROCESS", "UNDER_PROCESS")).isTrue();
  }

  @Test
  void rejectsASameStatusTransitionWhenTheConfigDoesNotListIt() {
    // REGISTERED and REQUIREMENT_PENDING do not list themselves.
    assertThat(policy.isTransitionAllowed("REGISTERED", "REGISTERED")).isFalse();
    assertThat(policy.isTransitionAllowed("REQUIREMENT_PENDING", "REQUIREMENT_PENDING")).isFalse();
  }
}
