package com.ghostchu.quickshop.addon.exchange.service;

final class InjectedFailure extends RuntimeException {
  InjectedFailure(String stage) {
    super(stage);
  }
}
