package com.insurancehub.gateway.crypto;

// service-design.md §4 step 4 / cross-cutting.md §5. Two implementations: NoOpHubCryptoService
// (phase 5, hub.crypto.enabled=false) treats `enc` as the plain JSON already; phase 6 adds a
// Nimbus-based implementation doing the real JWS-verify/JWE-decrypt round trip. The controller
// and everything downstream of it never changes between the two - only this interface's bean
// swaps.
public interface HubCryptoService {

  // inspId is the token's already-resolved insurer (AuditContext, populated by
  // IpAllowlistFilter before this ever runs) - the JWS itself carries no insurer identity, so
  // the real implementation needs to be told which insurer's public key to verify against.
  // Throws HubBusinessException(SIGNATURE_INVALID/DECRYPTION_FAILED) on failure (phase 6 -
  // unreachable in phase 5, since the no-op implementation never fails to "decrypt").
  String verifyAndDecrypt(String enc, String inspId);

  // Same inspId - which insurer's public key to encrypt the response to.
  String encryptAndSign(String json, String inspId);
}
