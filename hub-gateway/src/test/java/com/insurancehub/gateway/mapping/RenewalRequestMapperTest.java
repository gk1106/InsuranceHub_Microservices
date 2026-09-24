package com.insurancehub.gateway.mapping;

import static org.assertj.core.api.Assertions.assertThat;

import com.insurancehub.gateway.domain.RawPolicyDetails;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class RenewalRequestMapperTest {

  private final RenewalRequestMapper mapper = new RenewalRequestMapper();

  @Test
  void mapsRawStringsToTypedCommandFieldsAndKeepsPolicyNumForThePathVariable() {
    RawPolicyDetails details =
        new RawPolicyDetails(
            "RC01",
            "South Region",
            "BR102",
            "Chennai Main Branch",
            "CIF456789",
            "ACC99887766",
            "GENERAL",
            "Motor Insurance",
            "APP112233",
            "POL445566",
            "Ravi Kumar",
            "9876543210",
            "12, MG Road, Chennai, TN",
            "ACTIVE",
            "10/05/2024",
            "15/05/2025",
            "14/05/2026",
            "15000",
            "2700",
            "17700",
            "500000",
            "LN22334455",
            "SP7890",
            "Agent Suresh",
            "5",
            "750");

    var command = mapper.toCommand(details);

    assertThat(command.policyNum()).isEqualTo("POL445566");
    assertThat(command.startDate()).isEqualTo(LocalDate.of(2025, 5, 15));
  }
}
