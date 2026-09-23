package com.insurancehub.common.error;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class HubResponseTest {

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  void successSerializesAllFiveFields() {
    HubResponse response = HubResponse.success("01JTXN123", "REQ987654321");

    JsonNode json = objectMapper.valueToTree(response);

    assertThat(json.get("errorDesc").asText()).isEqualTo("SUCCESS");
    assertThat(json.get("respCode").asText()).isEqualTo("200");
    assertThat(json.get("status").asText()).isEqualTo("S");
    assertThat(json.get("txnId").asText()).isEqualTo("01JTXN123");
    assertThat(json.get("reqId").asText()).isEqualTo("REQ987654321");
    assertThat(json.size()).isEqualTo(5);
  }

  @Test
  void failureCarriesTxnIdAndReqId() {
    HubResponse response = HubResponse.failure(HubErrorCode.POLICY_NOT_FOUND, "01JTXN123", "REQ1");

    JsonNode json = objectMapper.valueToTree(response);

    assertThat(json.get("status").asText()).isEqualTo("F");
    assertThat(json.get("errorDesc").asText()).isEqualTo("Policy not found");
    assertThat(json.get("respCode").asText()).isEqualTo("404");
    assertThat(json.get("txnId").asText()).isEqualTo("01JTXN123");
    assertThat(json.get("reqId").asText()).isEqualTo("REQ1");
  }

  @Test
  void preTrustFailureOmitsTxnIdAndReqIdEntirely() {
    // Matches api-contract.md §4's exact sample:
    // {"errorDesc":"Token Expired","respCode":"401","status":"F"}
    HubResponse response = HubResponse.preTrustFailure(HubErrorCode.TOKEN_EXPIRED);

    JsonNode json = objectMapper.valueToTree(response);

    assertThat(json.get("errorDesc").asText()).isEqualTo("Token Expired");
    assertThat(json.get("respCode").asText()).isEqualTo("401");
    assertThat(json.get("status").asText()).isEqualTo("F");
    assertThat(json.has("txnId")).isFalse();
    assertThat(json.has("reqId")).isFalse();
    assertThat(json.size()).isEqualTo(3);
  }
}
