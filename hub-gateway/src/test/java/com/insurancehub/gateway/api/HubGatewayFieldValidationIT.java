package com.insurancehub.gateway.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.insurancehub.gateway.AbstractHubGatewayIT;
import com.insurancehub.gateway.domain.RawClaimDetails;
import com.insurancehub.gateway.domain.RawHeader;
import com.insurancehub.gateway.domain.RawHubRequestBody;
import com.insurancehub.gateway.domain.RawPolicyDetails;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.ObjectMapper;

// The rules themselves are unit-tested exhaustively (HubRequestValidatorTest). This proves the
// whole HTTP pipeline - real auth, real decrypt-off passthrough, real dispatch - correctly
// surfaces VALIDATION_FAILED as a 400 with the field name in errorDesc, for one representative
// case per rule category, not every rule again.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class HubGatewayFieldValidationIT extends AbstractHubGatewayIT {

  @Autowired private TestRestTemplate restTemplate;
  @Autowired private ObjectMapper objectMapper;

  @Test
  void aMissingRequiredFieldIsRejected() {
    var response = submitNewPolicy(withCif(""));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    var body = decrypt(response.getBody());
    assertThat(body.get("respCode")).isEqualTo("400");
    assertThat((String) body.get("errorDesc")).contains("cif");
  }

  @Test
  void aDateFailingThePatternIsRejected() {
    var response = submitNewPolicy(withStartDate("2024-05-15"));

    var body = decrypt(response.getBody());
    assertThat((String) body.get("errorDesc")).contains("startDate");
  }

  @Test
  void aDateMatchingThePatternButNotARealCalendarDateIsRejected() {
    var response = submitNewPolicy(withStartDate("30/02/2024"));

    var body = decrypt(response.getBody());
    assertThat((String) body.get("errorDesc")).contains("startDate");
  }

  @Test
  void anAmountFailingThePatternIsRejected() {
    var response = submitNewPolicy(withNetPremium("not-a-number"));

    var body = decrypt(response.getBody());
    assertThat((String) body.get("errorDesc")).contains("netPremium");
  }

  @Test
  void commissionPerOutOfRangeIsRejected() {
    var response = submitNewPolicy(withCommissionPer("150"));

    var body = decrypt(response.getBody());
    assertThat((String) body.get("errorDesc")).contains("commissionPer");
  }

  @Test
  void mobileNumNotTenDigitsIsRejected() {
    var response = submitNewPolicy(withMobileNum("12345"));

    var body = decrypt(response.getBody());
    assertThat((String) body.get("errorDesc")).contains("mobileNum");
  }

  @Test
  void startDateAfterExpiryDateIsRejected() {
    var response = submitNewPolicy(withStartDate("20/05/2025"));

    var body = decrypt(response.getBody());
    String errorDesc = (String) body.get("errorDesc");
    assertThat(errorDesc).contains("startDate");
    assertThat(errorDesc).contains("expiryDate");
  }

  @Test
  void emptyStringClaimDetailsIsAcceptedOnANewPolicyRequestReachingDispatch() {
    var allBlankClaimDetails =
        new RawClaimDetails(
            null, "", "", "", "", "", "", "", "", "", "", null, null, null, null, null, null);
    var body =
        new RawHubRequestBody(
            new RawHeader("REQ-FV-1", "NewPolicyService", "01", "INSP001", "universalsompo"),
            validPolicyDetails(),
            allBlankClaimDetails);

    var response = submit(body, "/v1/policydetail");

    // Not a 400 - validation passed, so it reached dispatch (no policy-service stub here means
    // it then fails downstream, but never with VALIDATION_FAILED).
    var decrypted = decrypt(response.getBody());
    assertThat(decrypted.get("errorDesc")).isNotEqualTo("Validation failed: <field>");
  }

  private ResponseEntity<String> submitNewPolicy(RawPolicyDetails details) {
    var body =
        new RawHubRequestBody(
            new RawHeader("REQ-FV", "NewPolicyService", "01", "INSP001", "universalsompo"),
            details,
            null);
    return submit(body, "/v1/policydetail");
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

  private static RawPolicyDetails withCif(String cif) {
    RawPolicyDetails d = validPolicyDetails();
    return replace(d, cif, d.startDate(), d.netPremium(), d.commissionPer(), d.mobileNum());
  }

  private static RawPolicyDetails withStartDate(String startDate) {
    RawPolicyDetails d = validPolicyDetails();
    return replace(d, d.cif(), startDate, d.netPremium(), d.commissionPer(), d.mobileNum());
  }

  private static RawPolicyDetails withNetPremium(String netPremium) {
    RawPolicyDetails d = validPolicyDetails();
    return replace(d, d.cif(), d.startDate(), netPremium, d.commissionPer(), d.mobileNum());
  }

  private static RawPolicyDetails withCommissionPer(String commissionPer) {
    RawPolicyDetails d = validPolicyDetails();
    return replace(d, d.cif(), d.startDate(), d.netPremium(), commissionPer, d.mobileNum());
  }

  private static RawPolicyDetails withMobileNum(String mobileNum) {
    RawPolicyDetails d = validPolicyDetails();
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

  private ResponseEntity<String> submit(RawHubRequestBody body, String path) {
    String json = objectMapper.writeValueAsString(body);
    String envelope = objectMapper.writeValueAsString(Map.of("enc", json));
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    headers.setBearerAuth(fetchToken("insp001-client", "insp001-secret"));
    return restTemplate.exchange(
        path, HttpMethod.POST, new HttpEntity<>(envelope, headers), String.class);
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> decrypt(String responseBody) {
    var wrapper = objectMapper.readValue(responseBody, Map.class);
    return objectMapper.readValue((String) wrapper.get("enc"), Map.class);
  }
}
