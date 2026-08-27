package com.ghostchu.quickshop.addon.exchange.persistence;

import java.util.List;

public final class SchemaV2 {
  private SchemaV2() {}

  public static List<ColumnDefinition> columns(SqlDialect dialect, TableNames tables) {
    return List.of(
        new ColumnDefinition(tables.marketState(), "discovery_quantity", dialect.longType()),
        new ColumnDefinition(tables.marketState(), "circuit_breaker_level", "INTEGER"));
  }

  public record ColumnDefinition(String table, String name, String type) {}
}
