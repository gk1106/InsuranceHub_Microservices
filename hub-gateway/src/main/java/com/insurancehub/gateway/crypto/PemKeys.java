package com.insurancehub.gateway.crypto;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

// Plain java.security parsing, no new dependency - Nimbus's own RSAKey.parse(...) reads JWK
// (JSON) or an X509Certificate, not a bare PEM key file, so this fills that gap. Private keys
// must be PKCS#8 ("-----BEGIN PRIVATE KEY-----") - scripts/gen-dev-keys.sh uses
// `openssl genpkey` specifically for this, not `openssl genrsa` (which produces PKCS#1,
// "-----BEGIN RSA PRIVATE KEY-----", a different encoding KeyFactory won't accept directly).
public final class PemKeys {

  private PemKeys() {}

  public static RSAPrivateKey readPrivateKey(Path path)
      throws IOException, GeneralSecurityException {
    return parsePrivateKey(Files.readString(path));
  }

  public static RSAPublicKey readPublicKey(Path path) throws IOException, GeneralSecurityException {
    return parsePublicKey(Files.readString(path));
  }

  public static RSAPrivateKey parsePrivateKey(String pem) throws GeneralSecurityException {
    byte[] der = decode(pem, "PRIVATE KEY");
    KeyFactory keyFactory = KeyFactory.getInstance("RSA");
    return (RSAPrivateKey) keyFactory.generatePrivate(new PKCS8EncodedKeySpec(der));
  }

  public static RSAPublicKey parsePublicKey(String pem) throws GeneralSecurityException {
    byte[] der = decode(pem, "PUBLIC KEY");
    KeyFactory keyFactory = KeyFactory.getInstance("RSA");
    return (RSAPublicKey) keyFactory.generatePublic(new X509EncodedKeySpec(der));
  }

  private static byte[] decode(String pem, String label) {
    String base64 =
        pem.replace("-----BEGIN " + label + "-----", "")
            .replace("-----END " + label + "-----", "")
            .replaceAll("\\s", "");
    return Base64.getDecoder().decode(base64);
  }
}
