package com.ghostchu.quickshop.addon.exchange.ledger;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record LedgerEntry(UUID entryId, String accountCode, String assetId,
                          BigDecimal amount, Instant createdAt) {}
