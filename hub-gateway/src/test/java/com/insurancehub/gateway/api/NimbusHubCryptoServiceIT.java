package com.insurancehub.gateway.api;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.patch;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.insurancehub.gateway.AbstractHubGatewayCryptoIT;
import com.insurancehub.gateway.domain.RawClaimDetails;
import com.insurancehub.gateway.domain.RawHeader;
import com.insurancehub.gateway.domain.RawHubRequestBody;
import com.insurancehub.gateway.domain.RawPolicyDetails;
import com.insurancehub.gateway.testsupport.SampleEnvelopeCodec;
import com.nimbusds.jose.util.Base64URL;
import java.util.Base64;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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

// Crypto ON (AbstractHubGatewayCryptoIT) - real JWS-verify/JWE-decrypt inbound, real
// JWE-encrypt/JWS-sign outbound, against throwaway keys generated once for the whole class.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class NimbusHubCryptoServiceIT extends AbstractHubGatewayCryptoIT {

  private static final String INSP_ID = "INSP001";

  @Autowired private TestRestTemplate restTemplate;
  @Autowired private ObjectMapper objectMapper;

  private final ListAppender<ILoggingEvent> logCapture = new ListAppender<>();

  @BeforeEach
  void resetWireMock() {
    POLICY_SERVICE.resetAll();
    CLAIMS_SERVICE.resetAll();
  }

  @BeforeEach
  void attachLogCapture() {
    logCapture.start();
    ((Logger) org.slf4j.LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME)).addAppender(logCapture);
  }

  @AfterEach
  void detachLogCapture() {
    ((Logger) org.slf4j.LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME))
        .detachAppender(logCapture);
  }

  @Test
  void noKeyMaterialOrEncStringEverReachesTheLogsAcrossEveryCryptoFailureMode() throws Exception {
    var body =
        new RawHubRequestBody(
            new RawHeader("REQ-LOG-1", "NewPolicyService", "01", INSP_ID, "universalsompo"),
            validPolicyDetails("POL1"),
            null);
    String wrongSignerEnc =
        SampleEnvelopeCodec.encryptAndSign(
            objectMapper.writeValueAsString(body),
            bankPublicKey(),
            insurerPrivateKey("INSP002"),
            "insp002");
    submit(wrongSignerEnc, "/v1/policydetail");
    submit("not-valid-base64!!!", "/v1/policydetail");
    submit(fabricatedJws("HS256", "garbage-signature"), "/v1/policydetail");

    String allLogs =
        logCapture.list.stream()
            .map(ILoggingEvent::getFormattedMessage)
            .collect(Collectors.joining("\n"));

    assertThat(allLogs).doesNotContain(wrongSignerEnc);
    assertThat(allLogs).doesNotContain("BEGIN PRIVATE KEY");
    assertThat(allLogs).doesNotContain("BEGIN PUBLIC KEY");
    // A JOSE compact-serialization segment shape (base64url, 10+ chars) - catches an
    // accidentally-logged header/payload/signature fragment even if it isn't the exact enc
    // string captured above.
    assertThat(allLogs).doesNotContainPattern("[A-Za-z0-9_-]{40,}\\.[A-Za-z0-9_-]{10,}");
  }

  @Test
  void roundTripsAllFourCodesEndToEnd() {
    POLICY_SERVICE.stubFor(
        post(urlPathEqualTo("/internal/policies"))
            .willReturn(
                aResponse()
                    .withStatus(201)
                    .withHeader("Content-Type", "application/json")
                    .withBody("{\"txnId\":\"TXN-01\",\"replayed\":false,\"policyNum\":\"POL1\"}")));
    var body01 =
        new RawHubRequestBody(
            new RawHeader("REQ01", "NewPolicyService", "01", INSP_ID, "universalsompo"),
            validPolicyDetails("POL1"),
            null);
    var response01 = submitEncrypted(body01, "/v1/policydetail");
    assertThat(response01.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(decryptResponse(response01.getBody()).get("txnId")).isEqualTo("TXN-01");

    POLICY_SERVICE.stubFor(
        post(urlPathEqualTo("/internal/policies/POL1/renewals"))
            .willReturn(
                aResponse()
                    .withStatus(201)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        "{\"txnId\":\"TXN-02\",\"replayed\":false,\"policyNum\":\"POL1\",\"termNo\":2}")));
    var body02 =
        new RawHubRequestBody(
            new RawHeader("REQ02", "RenewalService", "02", INSP_ID, "universalsompo"),
            validPolicyDetails("POL1"),
            null);
    var response02 = submitEncrypted(body02, "/v1/policydetail");
    assertThat(decryptResponse(response02.getBody()).get("txnId")).isEqualTo("TXN-02");

    CLAIMS_SERVICE.stubFor(
        post(urlPathEqualTo("/internal/claims"))
            .willReturn(
                aResponse()
                    .withStatus(201)
                    .withHeader("Content-Type", "application/json")
                    .withBody("{\"txnId\":\"TXN-03\",\"replayed\":false,\"claimNum\":\"CLM1\"}")));
    var body03 =
        new RawHubRequestBody(
            new RawHeader("REQ03", "ClaimService", "03", INSP_ID, "universalsompo"),
            validPolicyDetails("POL1"),
            validClaimDetailsForRegistration("CLM1"));
    var response03 = submitEncrypted(body03, "/v1/policydetail");
    assertThat(decryptResponse(response03.getBody()).get("txnId")).isEqualTo("TXN-03");

    CLAIMS_SERVICE.stubFor(
        patch(urlPathEqualTo("/internal/claims/CLM1/status"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody("{\"txnId\":\"TXN-04\",\"replayed\":false,\"claimNum\":\"CLM1\"}")));
    var body04 =
        new RawHubRequestBody(
            new RawHeader("REQ04", "ClaimStatusService", "04", INSP_ID, "universalsompo"),
            null,
            validClaimDetailsForStatusUpdate("POL1", "CLM1"));
    var response04 = submitEncrypted(body04, "/v1/policydetail/claimupdatestatus");
    assertThat(decryptResponse(response04.getBody()).get("txnId")).isEqualTo("TXN-04");
  }

  @Test
  void tamperedCiphertextIsRejectedAsDecryptionFailedPlainJson() throws Exception {
    // The JWE is the JWS's own payload, so tampering the OUTER (already-signed) envelope
    // anywhere breaks the JWS signature instead - "tampered ciphertext, valid signature" is only
    // reachable by tampering the JWE BEFORE signing (a genuinely corrupted/buggy encryption on
    // the insurer's own side, correctly signed over the already-corrupted bytes).
    var body =
        new RawHubRequestBody(
            new RawHeader("REQ-TAMPER", "NewPolicyService", "01", INSP_ID, "universalsompo"),
            validPolicyDetails("POL1"),
            null);
    String json = objectMapper.writeValueAsString(body);
    var jwe =
        new com.nimbusds.jose.JWEObject(
            new com.nimbusds.jose.JWEHeader.Builder(
                    com.insurancehub.gateway.crypto.CryptoAlgorithms.JWE_ALGORITHM,
                    com.insurancehub.gateway.crypto.CryptoAlgorithms.ENCRYPTION_METHOD)
                .build(),
            new com.nimbusds.jose.Payload(json));
    jwe.encrypt(new com.nimbusds.jose.crypto.RSAEncrypter(bankPublicKey()));
    String[] parts = jwe.serialize().split("\\.");
    char[] cipherTextChars = parts[3].toCharArray();
    cipherTextChars[cipherTextChars.length / 2] =
        cipherTextChars[cipherTextChars.length / 2] == 'A' ? 'B' : 'A';
    parts[3] = new String(cipherTextChars);
    String tamperedJwe = String.join(".", parts);
    String tampered = signRawPayload(tamperedJwe);

    ResponseEntity<String> response = submit(tampered, "/v1/policydetail");

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    Map<String, Object> plain = parsePlain(response.getBody());
    assertThat(plain.get("respCode")).isEqualTo("400");
    assertThat(plain.get("errorDesc")).isEqualTo("Decryption failed");
    assertThat(plain).doesNotContainKey("txnId");
  }

  @Test
  void wrongSignerIsRejectedAsSignatureInvalidPlainJson() throws Exception {
    var body =
        new RawHubRequestBody(
            new RawHeader("REQ-WRONG-SIGNER", "NewPolicyService", "01", INSP_ID, "universalsompo"),
            validPolicyDetails("POL1"),
            null);
    String json = objectMapper.writeValueAsString(body);
    // Encrypted to the real bank key, but signed with a key that ISN'T INSP001's registered one
    // (its own, otherwise-legitimate, sibling key).
    String enc =
        SampleEnvelopeCodec.encryptAndSign(
            json, bankPublicKey(), insurerPrivateKey("INSP002"), "insp002");

    ResponseEntity<String> response = submit(enc, "/v1/policydetail");

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    Map<String, Object> plain = parsePlain(response.getBody());
    assertThat(plain.get("errorDesc")).isEqualTo("Signature verification failed");
  }

  @Test
  void jwsAlgNoneIsRejected() {
    String rawJws = fabricatedJws("none", "");
    assertRejectedAsSignatureInvalid(rawJws);
  }

  @Test
  void jwsAlgHs256IsRejected() {
    String rawJws = fabricatedJws("HS256", "garbage-signature");
    assertRejectedAsSignatureInvalid(rawJws);
  }

  @Test
  void jweAlgRsa15IsRejected() throws Exception {
    String rawJwe = fabricatedJwe("RSA1_5", "A256GCM");
    String signedOverFabricatedJwe = signRawPayload(rawJwe);
    assertRejectedAsDecryptionFailed(signedOverFabricatedJwe);
  }

  @Test
  void jweEncA128CbcHs256IsRejected() throws Exception {
    String rawJwe = fabricatedJwe("RSA-OAEP-256", "A128CBC-HS256");
    String signedOverFabricatedJwe = signRawPayload(rawJwe);
    assertRejectedAsDecryptionFailed(signedOverFabricatedJwe);
  }

  @Test
  void malformedBase64IsRejectedAsDecryptionFailedNever500() {
    ResponseEntity<String> response = submit("not-valid-base64!!!", "/v1/policydetail");

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(parsePlain(response.getBody()).get("errorDesc")).isEqualTo("Decryption failed");
  }

  @Test
  void emptyEncIsRejectedAsDecryptionFailed() {
    ResponseEntity<String> response = submit("", "/v1/policydetail");

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(parsePlain(response.getBody()).get("errorDesc")).isEqualTo("Decryption failed");
  }

  @Test
  void insurerMismatchIsEnvelopedNotPlain() {
    // Valid signature/algorithms, correctly encrypted - but the decrypted body's own
    // header.inspId disagrees with the token's resolved insurer (INSP001 signs/sends, body
    // claims INSP002). This is the existing post-decrypt HubDispatcher check, not a new
    // pre-trust one - proven by the response being enveloped.
    var body =
        new RawHubRequestBody(
            new RawHeader("REQ-MISMATCH", "NewPolicyService", "01", "INSP002", "sbigeneral"),
            validPolicyDetails("POL1"),
            null);
    String enc = encrypt(body, INSP_ID);

    ResponseEntity<String> response = submit(enc, "/v1/policydetail");

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    Map<String, Object> decrypted = decryptResponse(response.getBody());
    assertThat(decrypted.get("respCode")).isEqualTo("403");
    assertThat(decrypted.get("txnId")).isNotNull();
    assertThat(decrypted.get("reqId")).isEqualTo("REQ-MISMATCH");
  }

  private void assertRejectedAsSignatureInvalid(String enc) {
    ResponseEntity<String> response = submit(enc, "/v1/policydetail");
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(parsePlain(response.getBody()).get("errorDesc"))
        .isEqualTo("Signature verification failed");
  }

  private void assertRejectedAsDecryptionFailed(String enc) {
    ResponseEntity<String> response = submit(enc, "/v1/policydetail");
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(parsePlain(response.getBody()).get("errorDesc")).isEqualTo("Decryption failed");
  }

  // A raw compact JWS with a disallowed alg header - garbage payload/signature is fine, since
  // NimbusHubCryptoService rejects on the algorithm check before ever calling verify().
  private static String fabricatedJws(String alg, String signature) {
    String header = Base64URL.encode("{\"alg\":\"" + alg + "\"}").toString();
    String payload = Base64URL.encode("{}").toString();
    String sig = Base64URL.encode(signature).toString();
    String compact = header + "." + payload + "." + sig;
    return Base64.getEncoder()
        .encodeToString(compact.getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }

  // A raw (unencrypted-for-real) compact JWE with a disallowed alg/enc header - garbage for the
  // other 4 segments, since the algorithm check runs before decrypt() is ever attempted.
  private static String fabricatedJwe(String alg, String enc) {
    String header =
        Base64URL.encode("{\"alg\":\"" + alg + "\",\"enc\":\"" + enc + "\"}").toString();
    String garbage = Base64URL.encode("x").toString();
    return header + "." + garbage + "." + garbage + "." + garbage + "." + garbage;
  }

  // Wraps a raw JWE-shaped string as the payload of a REAL, validly-signed JWS (INSP001's real
  // key) - JWS verification must succeed for the JWE algorithm check to ever run.
  private String signRawPayload(String rawJwePayload) throws Exception {
    var jws =
        new com.nimbusds.jose.JWSObject(
            new com.nimbusds.jose.JWSHeader.Builder(com.nimbusds.jose.JWSAlgorithm.RS256).build(),
            new com.nimbusds.jose.Payload(rawJwePayload));
    jws.sign(new com.nimbusds.jose.crypto.RSASSASigner(insurerPrivateKey(INSP_ID)));
    return Base64.getEncoder()
        .encodeToString(jws.serialize().getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }

  private String encrypt(RawHubRequestBody body, String signerInspId) {
    try {
      String json = objectMapper.writeValueAsString(body);
      return SampleEnvelopeCodec.encryptAndSign(
          json, bankPublicKey(), insurerPrivateKey(signerInspId), signerInspId);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  private ResponseEntity<String> submitEncrypted(RawHubRequestBody body, String path) {
    return submit(encrypt(body, INSP_ID), path);
  }

  private ResponseEntity<String> submit(String enc, String path) {
    String envelope = objectMapper.writeValueAsString(Map.of("enc", enc));
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    headers.setBearerAuth(fetchToken("insp001-client", "insp001-secret"));
    return restTemplate.exchange(
        path, HttpMethod.POST, new HttpEntity<>(envelope, headers), String.class);
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> decryptResponse(String responseBody) {
    var wrapper = objectMapper.readValue(responseBody, Map.class);
    String enc = (String) wrapper.get("enc");
    try {
      String json =
          SampleEnvelopeCodec.verifyAndDecrypt(enc, bankPublicKey(), insurerPrivateKey(INSP_ID));
      return objectMapper.readValue(json, Map.class);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> parsePlain(String responseBody) {
    return objectMapper.readValue(responseBody, Map.class);
  }

  private static RawPolicyDetails validPolicyDetails(String policyNum) {
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
        policyNum,
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

  private static RawClaimDetails validClaimDetailsForRegistration(String claimNum) {
    return new RawClaimDetails(
        null,
        claimNum,
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

  private static RawClaimDetails validClaimDetailsForStatusUpdate(
      String policyNum, String claimNum) {
    return new RawClaimDetails(
        policyNum,
        claimNum,
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
  }
}
