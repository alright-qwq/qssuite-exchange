package com.ghostchu.quickshop.addon.exchange.persistence;

public enum SqlDialect {
  SQLITE("INTEGER", "TEXT"),
  MYSQL("BIGINT", "VARCHAR(36)");

  private final String longType;
  private final String uuidType;

  SqlDialect(String longType, String uuidType) {
    this.longType = longType;
    this.uuidType = uuidType;
  }

  public String longType() { return longType; }
  public String uuidType() { return uuidType; }
  public String forUpdate() { return this == MYSQL ? " FOR UPDATE" : ""; }
  public String decimalType() { return this == MYSQL ? "DECIMAL(38,18)" : "TEXT"; }
}
