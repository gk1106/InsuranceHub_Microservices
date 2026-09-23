package com.insurancehub.gateway.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class HubServiceCodeTest {

  @Test
  void resolvesEachKnownPairOnItsOwnEndpoint() {
    assertThat(HubServiceCode.resolve("NewPolicyService", "01", HubEndpoint.POLICY_DETAIL))
        .contains(HubServiceCode.NEW_POLICY);
    assertThat(HubServiceCode.resolve("RenewalService", "02", HubEndpoint.POLICY_DETAIL))
        .contains(HubServiceCode.RENEWAL);
    assertThat(HubServiceCode.resolve("ClaimService", "03", HubEndpoint.POLICY_DETAIL))
        .contains(HubServiceCode.CLAIM_REGISTER);
    assertThat(HubServiceCode.resolve("ClaimStatusService", "04", HubEndpoint.CLAIM_STATUS_UPDATE))
        .contains(HubServiceCode.CLAIM_STATUS_UPDATE);
  }

  @Test
  void rejectsAMismatchedPair() {
    // Individually-known serviceType and appStatusCode, but not paired together.
    assertThat(HubServiceCode.resolve("RenewalService", "03", HubEndpoint.POLICY_DETAIL)).isEmpty();
  }

  @Test
  void rejectsAnUnknownServiceType() {
    assertThat(HubServiceCode.resolve("SomethingElseService", "01", HubEndpoint.POLICY_DETAIL))
        .isEmpty();
  }

  @Test
  void rejectsAKnownPairOnTheWrongEndpoint() {
    // 04 is only valid on CLAIM_STATUS_UPDATE, not POLICY_DETAIL.
    assertThat(HubServiceCode.resolve("ClaimStatusService", "04", HubEndpoint.POLICY_DETAIL))
        .isEmpty();
  }
}
