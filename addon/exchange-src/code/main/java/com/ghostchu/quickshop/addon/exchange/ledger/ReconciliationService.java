package com.ghostchu.quickshop.addon.exchange.ledger;

import com.ghostchu.quickshop.addon.exchange.repository.ExchangeRepository;
import java.sql.SQLException;

public final class ReconciliationService {
  private final ExchangeRepository repository;

  public ReconciliationService(ExchangeRepository repository) {
    this.repository = repository;
  }

  public ReconciliationReport run() throws SQLException {
    return repository.reconcile();
  }
}
