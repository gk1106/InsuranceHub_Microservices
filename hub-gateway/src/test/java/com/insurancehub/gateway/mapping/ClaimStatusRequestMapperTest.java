package com.insurancehub.gateway.mapping;

import static org.assertj.core.api.Assertions.assertThat;

import com.insurancehub.gateway.domain.RawClaimDetails;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class ClaimStatusRequestMapperTest {

  private final ClaimStatusRequestMapper mapper = new ClaimStatusRequestMapper();

  @Test
  void mapsRawStringsToTypedCommandFields() {
    RawClaimDetails claim =
        new RawClaimDetails(
            "POL445566",
            "CLM778899",
            "ACCIDENT",
            null,
            null,
            null,
            "",
            "21/08/2024",
            "85000",
            "CLOSED",
            "CLM-CLOSE-01",
            "80000",
            "30/08/2024",
            "9",
            "All documents submitted and verified",
            "",
            "Claim settled successfully");

    var command = mapper.toCommand(claim);

    assertThat(command.claimNum()).isEqualTo("CLM778899");
    assertThat(command.policyNum()).isEqualTo("POL445566");
    assertThat(command.claimStatus()).isEqualTo("CLOSED");
    assertThat(command.settledAmt()).isEqualByComparingTo(new BigDecimal("80000"));
    assertThat(command.finalizationDate()).isEqualTo(LocalDate.of(2024, 8, 30));
    assertThat(command.osAgeing()).isEqualTo(9);
    assertThat(command.repuCancelDate()).isNull();
  }

  @Test
  void blankOsAgeingMapsToNullNotAnException() {
    RawClaimDetails claim =
        new RawClaimDetails(
            "POL1",
            "CLM1",
            "",
            null,
            null,
            null,
            "",
            "",
            "",
            "UNDER_PROCESS",
            "",
            "",
            "",
            "",
            "",
            "",
            "");

    var command = mapper.toCommand(claim);

    assertThat(command.osAgeing()).isNull();
  }
}
