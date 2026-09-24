package com.insurancehub.gateway.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

// CLAUDE.md rule 8: crypto can be bypassed only in the local profile. base application.yml
// keeps enabled: true as an explicit default; only application-local.yml overrides it.
//
// bankKeys: the first entry is the active signing key (crypto.KeyRegistry.activeBankKid()) -
// every entry is tried in order when decrypting an inbound JWE, so an older key stays usable
// for decrypt during rotation even once a newer key has taken over signing. No default path
// here - a checked-in default key path makes no sense; local/test supply their own.
@ConfigurationProperties(prefix = "hub.crypto")
public record HubCryptoProperties(boolean enabled, List<BankKey> bankKeys) {

  public record BankKey(String kid, String privateKeyPath) {}
}
