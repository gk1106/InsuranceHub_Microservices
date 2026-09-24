package com.insurancehub.gateway.mapping;

import static org.assertj.core.api.Assertions.assertThat;

import com.insurancehub.gateway.domain.RawPolicyDetails;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class NewPolicyRequestMapperTest {

  private final NewPolicyRequestMapper mapper = new NewPolicyRequestMapper();

  @Test
  void mapsRawStringsToTypedCommandFields() {
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
            "15/05/2024",
            "14/05/2025",
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
    assertThat(command.cif()).isEqualTo("CIF456789");
    assertThat(command.insuredName()).isEqualTo("Ravi Kumar");
    assertThat(command.startDate()).isEqualTo(LocalDate.of(2024, 5, 15));
    assertThat(command.expiryDate()).isEqualTo(LocalDate.of(2025, 5, 14));
    assertThat(command.netPremium()).isEqualByComparingTo(new BigDecimal("15000"));
    assertThat(command.sumInsured()).isEqualByComparingTo(new BigDecimal("500000"));
    assertThat(command.commissionPer()).isEqualByComparingTo(new BigDecimal("5"));
  }

  @Test
  void emptyOptionalFieldsMapToNull() {
    RawPolicyDetails details =
        new RawPolicyDetails(
            "",
            "",
            "",
            "",
            "CIF1",
            "",
            "GEN",
            "",
            "",
            "POL1",
            "Name",
            "",
            "",
            "",
            "",
            "01/01/2024",
            "01/01/2025",
            "1000",
            "",
            "1000",
            "1000",
            "",
            "",
            "",
            "",
            "");

    var command = mapper.toCommand(details);

    assertThat(command.issueDate()).isNull();
    assertThat(command.gstAmt()).isNull();
    assertThat(command.commissionPer()).isNull();
    assertThat(command.commissionAmt()).isNull();
  }
}
