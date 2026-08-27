package com.ghostchu.quickshop.addon.exchange.ledger;

public final class UnbalancedJournalException extends IllegalArgumentException {
  public UnbalancedJournalException(String asset) {
    super("journal is not balanced for asset " + asset);
  }
}
