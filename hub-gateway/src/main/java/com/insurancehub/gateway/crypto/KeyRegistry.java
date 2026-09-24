package com.insurancehub.gateway.crypto;

import com.insurancehub.gateway.config.HubCryptoProperties;
import com.insurancehub.gateway.config.InsurerProperties;
import jakarta.annotation.PostConstruct;
import java.nio.file.Path;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

// Loads every key once at startup (cross-cutting.md §5) and holds the parsed
// RSAPrivateKey/RSAPublicKey objects for the life of the process - nothing about a request's own
// payload is cacheable, but the key material parsed from PEM is, and re-parsing it per request
// would be pure waste.
//
// Deliberately never throws out of @PostConstruct (unlike config.HubStartupGuard, a loud,
// fail-the-whole-process check for a config mistake that's always wrong): a key-loading failure
// here is reported through KeyRegistryHealthIndicator failing the readiness probe instead,
// leaving the process up and inspectable rather than crash-looping with nothing to query
// (cross-cutting.md §2: "Readiness includes... the key registry being loaded").
//
// Same condition as NimbusHubCryptoService: skipped entirely when crypto is off, so a
// local-profile run with no generated keys never attempts to load anything.
@Component
@ConditionalOnProperty(name = "hub.crypto.enabled", havingValue = "true")
public class KeyRegistry {

  private static final Logger log = LoggerFactory.getLogger(KeyRegistry.class);

  private final HubCryptoProperties cryptoProperties;
  private final InsurerProperties insurerProperties;

  // LinkedHashMap: config order preserved, first entry is the active (signing) key.
  private final Map<String, RSAPrivateKey> bankKeysByKid = new LinkedHashMap<>();
  private final Map<String, RSAPublicKey> insurerPublicKeys = new LinkedHashMap<>();
  private volatile boolean loaded = false;

  public KeyRegistry(HubCryptoProperties cryptoProperties, InsurerProperties insurerProperties) {
    this.cryptoProperties = cryptoProperties;
    this.insurerProperties = insurerProperties;
  }

  @PostConstruct
  void load() {
    try {
      List<HubCryptoProperties.BankKey> bankKeys = cryptoProperties.bankKeys();
      if (bankKeys == null || bankKeys.isEmpty()) {
        log.error("hub.crypto.bank-keys is empty - no bank key configured to sign/decrypt with");
        return;
      }
      for (HubCryptoProperties.BankKey bankKey : bankKeys) {
        RSAPrivateKey privateKey = PemKeys.readPrivateKey(Path.of(bankKey.privateKeyPath()));
        bankKeysByKid.put(bankKey.kid(), privateKey);
      }
      for (InsurerProperties.InsurerConfig insurer : insurerProperties.insurers()) {
        if (insurer.publicKeyPath() == null || insurer.publicKeyPath().isBlank()) {
          // Not yet onboarded for crypto - insurerPublicKey() returns empty for this inspId,
          // not a load failure (docs/open-questions.md).
          continue;
        }
        RSAPublicKey publicKey = PemKeys.readPublicKey(Path.of(insurer.publicKeyPath()));
        insurerPublicKeys.put(insurer.inspId(), publicKey);
      }
      loaded = true;
      log.info(
          "KeyRegistry loaded {} bank key(s), {} insurer public key(s)",
          bankKeysByKid.size(),
          insurerPublicKeys.size());
    } catch (Exception e) {
      // Never log the path's contents or the exception's own message if it could echo key
      // bytes - java.security parsing exceptions don't include key material, only structural
      // complaints, so the exception itself is safe to log; just never log a key value directly.
      log.error("KeyRegistry failed to load - readiness will report DOWN", e);
    }
  }

  public boolean isLoaded() {
    return loaded;
  }

  public String activeBankKid() {
    return cryptoProperties.bankKeys().get(0).kid();
  }

  public RSAPrivateKey activeBankPrivateKey() {
    return bankKeysByKid.get(activeBankKid());
  }

  // Decrypt tries each in config order (active key first) until one succeeds - see
  // NimbusHubCryptoService. Order matters for the common case (usually the active key is
  // right) but every configured key must remain usable for a caller still encrypting against
  // an older published bank public key during rotation.
  public Map<String, RSAPrivateKey> bankPrivateKeysByKid() {
    return bankKeysByKid;
  }

  public Optional<RSAPublicKey> insurerPublicKey(String inspId) {
    return Optional.ofNullable(insurerPublicKeys.get(inspId));
  }
}
