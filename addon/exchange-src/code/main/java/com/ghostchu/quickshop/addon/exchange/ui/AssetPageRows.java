package com.ghostchu.quickshop.addon.exchange.ui;

import com.ghostchu.quickshop.addon.exchange.repository.AccountAssetBalance;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Merges configured transfer targets with persisted account balances. */
final class AssetPageRows {
  private AssetPageRows() {}

  static Merged merge(List<TransferTarget> targets, List<AccountAssetBalance> balances) {
    Map<String, AccountAssetBalance> remaining = new LinkedHashMap<>();
    for (AccountAssetBalance balance : balances) {
      if (balance.kind() != AccountAssetBalance.Kind.SECURITY) {
        remaining.put(key(balance.kind(), balance.assetId()), balance);
      }
    }
    List<Row> rows = new ArrayList<>();
    for (TransferTarget target : targets) {
      AccountAssetBalance balance = remaining.remove(key(target));
      rows.add(balance == null
          ? new Row(target, BigDecimal.ZERO, BigDecimal.ZERO)
          : new Row(target, balance.available(), balance.frozen()));
    }
    for (AccountAssetBalance balance : remaining.values()) {
      TransferTarget target = balance.kind() == AccountAssetBalance.Kind.CURRENCY
          ? TransferTarget.currency(balance.assetId())
          : TransferTarget.item(balance.assetId(), balance.assetId());
      rows.add(new Row(target, balance.available(), balance.frozen()));
    }
    List<SecurityRow> securities = balances.stream()
        .filter(balance -> balance.kind() == AccountAssetBalance.Kind.SECURITY)
        .map(balance -> new SecurityRow(balance.symbol() == null ? balance.assetId()
                : balance.symbol(),
            balance.displayName() == null ? balance.assetId() : balance.displayName(),
            balance.available(), balance.frozen(), balance.assetId()))
        .toList();
    return new Merged(List.copyOf(rows), List.copyOf(securities));
  }

  private static String key(TransferTarget target) {
    return key(target.kind() == TransferTarget.Kind.CURRENCY
        ? AccountAssetBalance.Kind.CURRENCY : AccountAssetBalance.Kind.ITEM, target.assetId());
  }

  private static String key(AccountAssetBalance.Kind kind, String assetId) {
    return kind.name() + ':' + assetId;
  }

  record Merged(List<Row> rows, List<SecurityRow> securities) {
    Merged {
      rows = List.copyOf(rows);
      securities = List.copyOf(securities);
    }
  }

  record SecurityRow(String symbol, String displayName, BigDecimal available,
                     BigDecimal frozen, String marketId) {
    SecurityRow {
      if (symbol == null || symbol.isBlank() || displayName == null || displayName.isBlank()
          || available == null || frozen == null || available.signum() < 0
          || frozen.signum() < 0) {
        throw new IllegalArgumentException("invalid security row");
      }
    }
  }

  record Row(TransferTarget target, BigDecimal available, BigDecimal frozen) {
    Row {
      if (target == null || available == null || frozen == null
          || available.signum() < 0 || frozen.signum() < 0) {
        throw new IllegalArgumentException("invalid asset page row");
      }
    }
  }
}
