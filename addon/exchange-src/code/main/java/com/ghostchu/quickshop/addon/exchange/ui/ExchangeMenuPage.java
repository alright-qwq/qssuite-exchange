package com.ghostchu.quickshop.addon.exchange.ui;

import java.util.Locale;

/** Stable TNML page numbers for typed exchange menu requests. */
public enum ExchangeMenuPage {
  MARKETS("markets", 1),
  MARKET_DETAIL("market-detail", 2),
  ORDER_CONFIRM("order-confirm", 3),
  CANCEL_CONFIRM("cancel-confirm", 4),
  TRANSFER_CONFIRM("transfer-confirm", 5),
  ORDERS("orders", 6),
  ASSETS("assets", 7),
  HISTORY("history", 8),
  ADMIN("admin", 9),
  MARKET_TRADES("market-trades", 10);

  private final String menuName;
  private final int page;

  ExchangeMenuPage(String menuName, int page) {
    this.menuName = menuName;
    this.page = page;
  }

  public String menuName() {
    return menuName;
  }

  public int page() {
    return page;
  }

  public static ExchangeMenuPage forName(String name) {
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("unknown exchange menu: " + name);
    }
    String normalized = name.toLowerCase(Locale.ROOT);
    for (ExchangeMenuPage page : values()) {
      if (page.menuName.equals(normalized)) {
        return page;
      }
    }
    throw new IllegalArgumentException("unknown exchange menu: " + name);
  }
}
