package com.insurancehub.gateway.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.insurancehub.common.error.HubBusinessException;
import com.insurancehub.common.error.HubErrorCode;
import com.insurancehub.gateway.domain.Claim;
import com.insurancehub.gateway.domain.NewPolicy;
import com.insurancehub.gateway.domain.RawClaimDetails;
import com.insurancehub.gateway.domain.RawHeader;
import com.insurancehub.gateway.domain.RawHubRequestBody;
import com.insurancehub.gateway.domain.RawPolicyDetails;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;

class HubRequestValidatorTest {

  private final HubRequestValidator validator =
      new HubRequestValidator(Validation.buildDefaultValidatorFactory().getValidator());

  @Test
  void acceptsAFullyValidNewPolicyRequest() {
    validator.validate(bodyWithPolicyDetails(validPolicyDetails()), NewPolicy.class);
  }

  @Test
  void rejectsAMissingRequiredField() {
    RawPolicyDetails missingCif = withCif(validPolicyDetails(), "");

    assertThatThrownBy(() -> validator.validate(bodyWithPolicyDetails(missingCif), NewPolicy.class))
        .isInstanceOf(HubBusinessException.class)
        .satisfies(
            ex -> {
              assertThat(((HubBusinessException) ex).code())
                  .isEqualTo(HubErrorCode.VALIDATION_FAILED);
              assertThat(((HubBusinessException) ex).safeDetail()).contains("cif");
            });
  }

  @Test
  void rejectsADateThatFailsThePattern() {
    RawPolicyDetails badFormat = withStartDate(validPolicyDetails(), "2024-05-15");

    assertThatThrownBy(() -> validator.validate(bodyWithPolicyDetails(badFormat), NewPolicy.class))
        .isInstanceOf(HubBusinessException.class)
        .satisfies(
            ex -> assertThat(((HubBusinessException) ex).safeDetail()).contains("startDate"));
  }

  @Test
  void rejectsADateThatMatchesThePatternButIsntReal() {
    RawPolicyDetails impossibleDate = withStartDate(validPolicyDetails(), "30/02/2024");

    assertThatThrownBy(
            () -> validator.validate(bodyWithPolicyDetails(impossibleDate), NewPolicy.class))
        .isInstanceOf(HubBusinessException.class)
        .satisfies(
            ex -> assertThat(((HubBusinessException) ex).safeDetail()).contains("startDate"));
  }

  @Test
  void rejectsAnAmountThatFailsThePattern() {
    RawPolicyDetails badAmount = withNetPremium(validPolicyDetails(), "not-a-number");

    assertThatThrownBy(() -> validator.validate(bodyWithPolicyDetails(badAmount), NewPolicy.class))
        .isInstanceOf(HubBusinessException.class)
        .satisfies(
            ex -> assertThat(((HubBusinessException) ex).safeDetail()).contains("netPremium"));
  }

  @Test
  void rejectsACommissionPercentageOutOfRange() {
    RawPolicyDetails over100 = withCommissionPer(validPolicyDetails(), "150");

    assertThatThrownBy(() -> validator.validate(bodyWithPolicyDetails(over100), NewPolicy.class))
        .isInstanceOf(HubBusinessException.class)
        .satisfies(
            ex -> assertThat(((HubBusinessException) ex).safeDetail()).contains("commissionPer"));
  }

  @Test
  void rejectsAMobileNumberThatIsntTenDigits() {
    RawPolicyDetails badMobile = withMobileNum(validPolicyDetails(), "12345");

    assertThatThrownBy(() -> validator.validate(bodyWithPolicyDetails(badMobile), NewPolicy.class))
        .isInstanceOf(HubBusinessException.class)
        .satisfies(
            ex -> assertThat(((HubBusinessException) ex).safeDetail()).contains("mobileNum"));
  }

  @Test
  void rejectsStartDateAfterExpiryDate() {
    RawPolicyDetails reversed = withStartDate(validPolicyDetails(), "20/05/2025");

    assertThatThrownBy(() -> validator.validate(bodyWithPolicyDetails(reversed), NewPolicy.class))
        .isInstanceOf(HubBusinessException.class)
        .satisfies(
            ex -> {
              String detail = ((HubBusinessException) ex).safeDetail();
              assertThat(detail).contains("startDate");
              assertThat(detail).contains("expiryDate");
            });
  }

  @Test
  void rejectsDateOfLossAfterIntimationDate() {
    RawClaimDetails reversed = validClaimDetails();
    reversed =
        new RawClaimDetails(
            null,
            reversed.claimNum(),
            reversed.claimType(),
            reversed.lossDisc(),
            reversed.natureOfLoss(),
            reversed.lossCity(),
            "10/08/2024",
            "01/08/2024",
            reversed.claimedAmt(),
            reversed.claimStatus(),
            reversed.claimCode(),
            reversed.settledAmt(),
            reversed.finalizationDate(),
            reversed.osAgeing(),
            reversed.requireDetails(),
            reversed.repuCancelDate(),
            reversed.reasonOfClosure());
    RawHubRequestBody body = new RawHubRequestBody(validHeader(), validPolicyDetails(), reversed);

    assertThatThrownBy(() -> validator.validate(body, Claim.class))
        .isInstanceOf(HubBusinessException.class)
        .satisfies(
            ex -> {
              String detail = ((HubBusinessException) ex).safeDetail();
              assertThat(detail).contains("dateOfLoss");
              assertThat(detail).contains("intimationDate");
            });
  }

  @Test
  void emptyStringClaimDetailsIsAcceptedOnANewPolicyRequest() {
    RawClaimDetails allBlank =
        new RawClaimDetails(
            null, "", "", "", "", "", "", "", "", "", "", null, null, null, null, null, null);
    RawHubRequestBody body = new RawHubRequestBody(validHeader(), validPolicyDetails(), allBlank);

    validator.validate(body, NewPolicy.class);
  }

  @Test
  void onlyPolicyNumIsRequiredFromPolicyDetailsForClaimRegistration() {
    RawPolicyDetails onlyPolicyNum =
        new RawPolicyDetails(
            "", "", "", "", "", "", "", "", "", "POL001", "", "", "", "", "", "", "", "", "", "",
            "", "", "", "", "", "");
    RawHubRequestBody body =
        new RawHubRequestBody(validHeader(), onlyPolicyNum, validClaimDetails());

    validator.validate(body, Claim.class);
  }

  private static RawHubRequestBody bodyWithPolicyDetails(RawPolicyDetails details) {
    return new RawHubRequestBody(validHeader(), details, null);
  }

  private static RawHeader validHeader() {
    return new RawHeader("REQ1", "NewPolicyService", "01", "INSP001", "universalsompo");
  }

  private static RawPolicyDetails validPolicyDetails() {
    return new RawPolicyDetails(
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
  }

  private static RawClaimDetails validClaimDetails() {
    return new RawClaimDetails(
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
  }

  private static RawPolicyDetails withCif(RawPolicyDetails d, String cif) {
    return replace(d, cif, d.startDate(), d.netPremium(), d.commissionPer(), d.mobileNum());
  }

  private static RawPolicyDetails withStartDate(RawPolicyDetails d, String startDate) {
    return replace(d, d.cif(), startDate, d.netPremium(), d.commissionPer(), d.mobileNum());
  }

  private static RawPolicyDetails withNetPremium(RawPolicyDetails d, String netPremium) {
    return replace(d, d.cif(), d.startDate(), netPremium, d.commissionPer(), d.mobileNum());
  }

  private static RawPolicyDetails withCommissionPer(RawPolicyDetails d, String commissionPer) {
    return replace(d, d.cif(), d.startDate(), d.netPremium(), commissionPer, d.mobileNum());
  }

  private static RawPolicyDetails withMobileNum(RawPolicyDetails d, String mobileNum) {
    return replace(d, d.cif(), d.startDate(), d.netPremium(), d.commissionPer(), mobileNum);
  }

  private static RawPolicyDetails replace(
      RawPolicyDetails d,
      String cif,
      String startDate,
      String netPremium,
      String commissionPer,
      String mobileNum) {
    return new RawPolicyDetails(
        d.regionCode(),
        d.regionName(),
        d.branchCode(),
        d.branchName(),
        cif,
        d.accountNum(),
        d.insuranceType(),
        d.insuranceName(),
        d.applicationNum(),
        d.policyNum(),
        d.name(),
        mobileNum,
        d.address(),
        d.applicationStatus(),
        d.issueDate(),
        startDate,
        d.expiryDate(),
        netPremium,
        d.gstAmt(),
        d.grossPremium(),
        d.sumInsured(),
        d.loanAcctNum(),
        d.specPerNum(),
        d.specPerName(),
        commissionPer,
        d.commissionAmt());
  }
}
