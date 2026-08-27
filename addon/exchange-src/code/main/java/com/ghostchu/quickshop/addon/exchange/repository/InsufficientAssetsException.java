package com.ghostchu.quickshop.addon.exchange.repository;

public final class InsufficientAssetsException extends RuntimeException {
  public InsufficientAssetsException(String asset) {
    super("insufficient " + asset);
  }
}
