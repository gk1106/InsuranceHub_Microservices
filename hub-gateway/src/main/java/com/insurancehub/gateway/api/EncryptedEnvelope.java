package com.insurancehub.gateway.api;

import jakarta.validation.constraints.NotBlank;

// The wire shape for both request and response bodies (api-contract.md §2): {"enc": "<string>"}.
// With crypto off, `enc` literally *is* the plain JSON payload (crypto.NoOpHubCryptoService) -
// the controller never changes shape between phase 5 and phase 6, only what's underneath it.
public record EncryptedEnvelope(@NotBlank String enc) {}
