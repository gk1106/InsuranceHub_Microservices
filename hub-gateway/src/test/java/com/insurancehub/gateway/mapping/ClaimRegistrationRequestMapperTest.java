package com.insurancehub.gateway.mapping;

import static org.assertj.core.api.Assertions.assertThat;

import com.insurancehub.gateway.domain.RawClaimDetails;
import com.insurancehub.gateway.domain.RawPolicyDetails;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class ClaimRegistrationRequestMapperTest {

  private final ClaimRegistrationRequestMapper mapper = new ClaimRegistrationRequestMapper();

  @Test
  void policyNumComesFromPolicyDetailsNotClaimDetails() {
    RawPolicyDetails policyDetails =
        new RawPolicyDetails(
            "",
            "",
            "",
            "",
            "",
            "",
            "",
            "",
            "",
            "POL-FROM-POLICY-DETAILS",
            "",
            "",
            "",
            "",
            "",
            "",
            "",
            "",
            "",
            "",
            "",
            "",
            "",
            "",
            "",
            "");
    RawClaimDetails claim =
        new RawClaimDetails(
            null,
            "CLM778899",
            "ACCIDENT",
            "Minor collision",
            "Collision",
            "Chennai",
            "01/08/2024",
            "05/08/2024",
            "85000",
            "",
            "",
            null,
            null,
            null,
            null,
            null,
            null);

    var command = mapper.toCommand(policyDetails, claim);

    assertThat(command.policyNum()).isEqualTo("POL-FROM-POLICY-DETAILS");
    assertThat(command.claimNum()).isEqualTo("CLM778899");
    assertThat(command.dateOfLoss()).isEqualTo(LocalDate.of(2024, 8, 1));
    assertThat(command.intimationDate()).isEqualTo(LocalDate.of(2024, 8, 5));
    assertThat(command.claimedAmt()).isEqualByComparingTo(new BigDecimal("85000"));
  }
}
