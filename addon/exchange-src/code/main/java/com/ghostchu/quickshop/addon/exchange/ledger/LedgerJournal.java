package com.ghostchu.quickshop.addon.exchange.ledger;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record LedgerJournal(UUID journalId, String journalType, UUID referenceId,
                            Instant createdAt, UUID reversalOf, List<LedgerEntry> entries) {
  public LedgerJournal {
    entries = List.copyOf(entries);
    LedgerValidator.requireBalanced(entries);
  }
}
