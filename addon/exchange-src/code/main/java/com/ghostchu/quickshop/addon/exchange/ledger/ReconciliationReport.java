package com.ghostchu.quickshop.addon.exchange.ledger;

import java.math.BigDecimal;
import java.util.Map;

public record ReconciliationReport(Map<String, BigDecimal> ledgerDifferences,
                                   Map<String, BigDecimal> custodyDifferences,
                                   int underReservedOrders) {
  public ReconciliationReport {
    ledgerDifferences = Map.copyOf(ledgerDifferences);
    custodyDifferences = Map.copyOf(custodyDifferences);
  }

  public boolean balanced() {
    return ledgerDifferences.values().stream().allMatch(value -> value.signum() == 0)
        && custodyDifferences.values().stream().allMatch(value -> value.signum() == 0)
        && underReservedOrders == 0;
  }
}
