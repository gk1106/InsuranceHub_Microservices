package com.insurancehub.gateway.api;

// The wire shape for both request and response bodies (api-contract.md §2): {"enc": "<string>"}.
// With crypto off, `enc` literally *is* the plain JSON payload (crypto.NoOpHubCryptoService) -
// the controller never changes shape between phase 5 and phase 6, only what's underneath it.
//
// No @NotBlank (phase 6): a null/blank/malformed enc is HubCryptoService.verifyAndDecrypt's own
// job to reject, as DECRYPTION_FAILED - api-contract.md's own error catalogue has no separate
// code for "missing envelope", and the crypto layer already owns every other malformed-input
// shape (bad Base64, tampered ciphertext, wrong signer) uniformly.
public record EncryptedEnvelope(String enc) {}
