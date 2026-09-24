package com.insurancehub.gateway.testsupport;

import com.insurancehub.gateway.crypto.PemKeys;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;

// Thin CLI wrapper around SampleEnvelopeCodec for scripts/send-sample.sh, run via
// `mvn exec:java@envelope-cli` (see hub-gateway/pom.xml - not bound to any normal build phase).
// Reads the JSON payload from stdin, writes the result to stdout; everything else (which key
// paths, which mode) comes from argv so send-sample.sh stays in charge of the actual insurer
// identity/key selection.
public final class EnvelopeCli {

  private EnvelopeCli() {}

  public static void main(String[] args) throws Exception {
    if (args.length < 3) {
      System.err.println(
          "Usage: EnvelopeCli <encrypt-and-sign|verify-and-decrypt> <publicKeyPath> "
              + "<privateKeyPath> [kid]");
      System.exit(1);
      return;
    }
    String mode = args[0];
    Path publicKeyPath = Path.of(args[1]);
    Path privateKeyPath = Path.of(args[2]);
    String input = new String(System.in.readAllBytes(), StandardCharsets.UTF_8);

    String result =
        switch (mode) {
          case "encrypt-and-sign" -> {
            RSAPublicKey recipientPublicKey = PemKeys.readPublicKey(publicKeyPath);
            RSAPrivateKey signerPrivateKey = PemKeys.readPrivateKey(privateKeyPath);
            String kid = args.length > 3 ? args[3] : "dev";
            yield SampleEnvelopeCodec.encryptAndSign(
                input, recipientPublicKey, signerPrivateKey, kid);
          }
          case "verify-and-decrypt" -> {
            RSAPublicKey signerPublicKey = PemKeys.readPublicKey(publicKeyPath);
            RSAPrivateKey recipientPrivateKey = PemKeys.readPrivateKey(privateKeyPath);
            yield SampleEnvelopeCodec.verifyAndDecrypt(input, signerPublicKey, recipientPrivateKey);
          }
          default -> throw new IllegalArgumentException("Unknown mode: " + mode);
        };
    System.out.println(result);
  }
}
