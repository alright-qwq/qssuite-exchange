package com.ghostchu.quickshop.addon.exchange.ledger;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class LedgerValidator {
  private LedgerValidator() {}

  public static void requireBalanced(List<LedgerEntry> entries) {
    if (entries.size() < 2) {
      throw new UnbalancedJournalException("missing counter-entry");
    }
    Map<String, BigDecimal> totals = new HashMap<>();
    for (LedgerEntry entry : entries) {
      totals.merge(entry.assetId(), entry.amount(), BigDecimal::add);
    }
    totals.forEach((asset, total) -> {
      if (total.signum() != 0) {
        throw new UnbalancedJournalException(asset);
      }
    });
  }
}
