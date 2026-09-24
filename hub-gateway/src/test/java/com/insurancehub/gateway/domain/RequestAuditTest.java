package com.insurancehub.gateway.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Set;
import org.junit.jupiter.api.Test;

// Proof that RequestAudit's declared fields are exactly the approved eight - fails immediately,
// not eventually in a data-driven test, if anyone adds a field (a payload column, say) without a
// conscious update to this test too.
class RequestAuditTest {

  private static final Set<String> APPROVED_FIELDS =
      Set.of(
          "id",
          "txnId",
          "reqId",
          "inspId",
          "serviceType",
          "respCode",
          "latencyMs",
          "clientIp",
          "createdAt");

  @Test
  void hasExactlyTheApprovedFieldsAndNoPayloadColumn() {
    Set<String> actualFields =
        Arrays.stream(RequestAudit.class.getDeclaredFields())
            .map(Field::getName)
            .filter(name -> !name.startsWith("$") && !name.contains("jacoco"))
            .collect(java.util.stream.Collectors.toSet());

    assertThat(actualFields).isEqualTo(APPROVED_FIELDS);
  }

  @Test
  void ofPopulatesAllSevenScalarFields() {
    RequestAudit audit =
        RequestAudit.of("01TXN", "REQ1", "INSP001", "NewPolicyService", "200", 42L, "10.0.0.1");

    assertThat(audit.getTxnId()).isEqualTo("01TXN");
    assertThat(audit.getReqId()).isEqualTo("REQ1");
    assertThat(audit.getInspId()).isEqualTo("INSP001");
    assertThat(audit.getServiceType()).isEqualTo("NewPolicyService");
    assertThat(audit.getRespCode()).isEqualTo("200");
    assertThat(audit.getLatencyMs()).isEqualTo(42L);
    assertThat(audit.getClientIp()).isEqualTo("10.0.0.1");
  }

  @Test
  void reqIdInspIdAndServiceTypeAcceptNullForPreTrustRejections() {
    RequestAudit audit = RequestAudit.of("01TXN", null, null, null, "401", 5L, "10.0.0.1");

    assertThat(audit.getReqId()).isNull();
    assertThat(audit.getInspId()).isNull();
    assertThat(audit.getServiceType()).isNull();
  }
}
