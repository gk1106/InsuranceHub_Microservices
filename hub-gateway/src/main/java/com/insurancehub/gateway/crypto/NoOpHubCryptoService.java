package com.insurancehub.gateway.crypto;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

// matchIfMissing = true: @ConditionalOnProperty(havingValue = "false") alone does not match
// when hub.crypto.enabled is simply absent from every property source, which would leave
// HubCryptoService with zero bean candidates and fail late, on first use, with a confusing
// "no bean of type HubCryptoService" error instead of a clear one at startup. Treating "unset"
// the same as "false" for bean selection matches the properties binder's own default (a missing
// primitive boolean binds to false) - and config.HubStartupGuard independently reads the same
// resolved property and fails startup outside the local profile regardless of *why* it resolved
// to disabled, so this can't silently ship even though the bean itself is now always available.
@Component
@ConditionalOnProperty(name = "hub.crypto.enabled", havingValue = "false", matchIfMissing = true)
public class NoOpHubCryptoService implements HubCryptoService {

  @Override
  public String verifyAndDecrypt(String enc, String inspId) {
    return enc;
  }

  @Override
  public String encryptAndSign(String json, String inspId) {
    return json;
  }
}
