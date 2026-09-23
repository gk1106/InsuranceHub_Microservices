package com.insurancehub.common.web;

import java.util.concurrent.ThreadLocalRandom;

// Crockford Base32-encoded ULID: 48-bit millisecond timestamp + 80-bit randomness, 26
// characters, lexicographically sortable by generation time. See
// docs/adr/0002-txn-id-format.md for why (not a third-party dependency) and the matching
// CHAR(26) column type for phase 2's schema. ThreadLocalRandom, not SecureRandom, on purpose:
// this is a correlation id, not a security token, and avoids any entropy-pool blocking risk on
// a startup/health-check-sensitive path.
final class Ulid {

  private static final char[] ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();
  private static final int RANDOM_BYTES = 10;

  private Ulid() {}

  static String generate() {
    byte[] randomness = new byte[RANDOM_BYTES];
    ThreadLocalRandom.current().nextBytes(randomness);
    return encode(System.currentTimeMillis(), randomness);
  }

  // Package-visible for deterministic testing; not part of the public API.
  static String encode(long timestampMs, byte[] randomness) {
    if (randomness.length != RANDOM_BYTES) {
      throw new IllegalArgumentException("randomness must be " + RANDOM_BYTES + " bytes");
    }
    byte[] bytes = new byte[16];
    bytes[0] = (byte) (timestampMs >>> 40);
    bytes[1] = (byte) (timestampMs >>> 32);
    bytes[2] = (byte) (timestampMs >>> 24);
    bytes[3] = (byte) (timestampMs >>> 16);
    bytes[4] = (byte) (timestampMs >>> 8);
    bytes[5] = (byte) timestampMs;
    System.arraycopy(randomness, 0, bytes, 6, RANDOM_BYTES);

    char[] out = new char[26];
    int buffer = 0;
    int bufferedBits = 0;
    int outIdx = 0;
    for (byte b : bytes) {
      buffer = (buffer << 8) | (b & 0xFF);
      bufferedBits += 8;
      while (bufferedBits >= 5) {
        bufferedBits -= 5;
        out[outIdx++] = ALPHABET[(buffer >>> bufferedBits) & 0x1F];
      }
      buffer &= (1 << bufferedBits) - 1;
    }
    if (bufferedBits > 0) {
      out[outIdx++] = ALPHABET[(buffer << (5 - bufferedBits)) & 0x1F];
    }
    return new String(out);
  }
}
