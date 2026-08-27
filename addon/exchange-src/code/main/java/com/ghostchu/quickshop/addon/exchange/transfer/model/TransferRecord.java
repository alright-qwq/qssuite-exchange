package com.ghostchu.quickshop.addon.exchange.transfer.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record TransferRecord(
    UUID transferId, UUID requestId, UUID accountId, TransferType type,
    String assetId, BigDecimal amount, TransferStatus status,
    String externalMarker, String failureReason,
    Instant createdAt, Instant updatedAt, long version) {

  public TransferRecord {
    if (transferId == null || requestId == null || accountId == null || type == null
        || assetId == null || assetId.isBlank() || amount == null || amount.signum() <= 0
        || status == null || createdAt == null || updatedAt == null || version < 0) {
      throw new IllegalArgumentException("invalid transfer");
    }
  }

  public static TransferRecord prepared(
      UUID transferId, UUID requestId, UUID accountId, TransferType type,
      String assetId, BigDecimal amount, Instant now) {
    if (transferId == null) {
      throw new IllegalArgumentException("invalid transfer");
    }
    return new TransferRecord(transferId, requestId, accountId, type, assetId, amount,
        TransferStatus.PREPARED, transferId.toString(), null, now, now, 0);
  }
}
