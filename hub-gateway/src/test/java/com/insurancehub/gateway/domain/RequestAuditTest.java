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

  @Test
  void anOversizedReqIdIsTruncatedRatherThanFailingTheInsert() {
    // reqId/serviceType come from the insurer's raw JSON header with no upstream length bound
    // (RawHeader's own @Size is a courtesy rejection on the happy path, not a hard guarantee -
    // AuditContext captures the header's raw value before HubRequestValidator ever runs) - the
    // req_id column is VARCHAR(64), so of() must never hand Hibernate a longer value.
    String oversized = "R".repeat(100);

    RequestAudit audit =
        RequestAudit.of("01TXN", oversized, "INSP001", "NewPolicyService", "422", 5L, "10.0.0.1");

    assertThat(audit.getReqId()).hasSize(64).isEqualTo("R".repeat(64));
  }

  @Test
  void anOversizedServiceTypeIsTruncatedRatherThanFailingTheInsert() {
    String oversized = "S".repeat(50);

    RequestAudit audit =
        RequestAudit.of("01TXN", "REQ1", "INSP001", oversized, "422", 5L, "10.0.0.1");

    assertThat(audit.getServiceType()).hasSize(30).isEqualTo("S".repeat(30));
  }
}
