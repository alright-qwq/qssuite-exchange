package com.ghostchu.quickshop.addon.exchange.platform;

public record ItemFingerprint(String algorithm, String value) {
  public ItemFingerprint {
    if (!algorithm.equals("material-v1") && !algorithm.equals("sha256-stack-v1")) {
      throw new IllegalArgumentException("unsupported fingerprint algorithm");
    }
  }
}
