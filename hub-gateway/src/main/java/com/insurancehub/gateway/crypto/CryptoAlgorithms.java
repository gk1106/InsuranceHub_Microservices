package com.insurancehub.gateway.crypto;

import com.nimbusds.jose.EncryptionMethod;
import com.nimbusds.jose.JWEAlgorithm;
import com.nimbusds.jose.JWSAlgorithm;

// The exact allow-list (cross-cutting.md §5) - defined once so NimbusHubCryptoService's checks
// and CryptoAlgorithmsTest reference the same constants, not re-typed literals that could
// drift apart.
public final class CryptoAlgorithms {

  public static final JWSAlgorithm JWS_ALGORITHM = JWSAlgorithm.RS256;
  public static final JWEAlgorithm JWE_ALGORITHM = JWEAlgorithm.RSA_OAEP_256;
  public static final EncryptionMethod ENCRYPTION_METHOD = EncryptionMethod.A256GCM;

  private CryptoAlgorithms() {}
}
