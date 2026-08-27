package com.ghostchu.quickshop.addon.exchange.persistence;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TableNamesTest {
  @Test
  void appliesValidatedQuickShopPrefix() {
    TableNames names = new TableNames("qs_");
    assertThat(names.orders()).isEqualTo("qs_exchange_orders");
    assertThat(names.schemaVersion()).isEqualTo("qs_exchange_schema_version");
  }

  @Test
  void rejectsSqlInPrefix() {
    assertThatThrownBy(() -> new TableNames("qs_; DROP TABLE users;--"))
        .hasMessage("invalid table prefix");
  }
}
