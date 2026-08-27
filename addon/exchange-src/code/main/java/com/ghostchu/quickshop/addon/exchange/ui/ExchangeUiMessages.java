package com.ghostchu.quickshop.addon.exchange.ui;

import com.ghostchu.quickshop.addon.exchange.platform.AddonMessageService;
import java.time.Duration;
import java.time.Instant;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;

/** Small locale-aware adapter for player-visible exchange menu text. */
final class ExchangeUiMessages {
  private final AddonMessageService messages;

  ExchangeUiMessages(AddonMessageService messages) {
    this.messages = messages;
  }

  Component component(Player player, String key, Object... arguments) {
    return Component.text(text(player, key, arguments));
  }

  String text(Player player, String key, Object... arguments) {
    if (messages == null) return key;
    Locale locale = player.locale();
    return messages.message(key, locale, arguments);
  }

  /** Formats an aggregate or market-agnostic currency amount at the default two-decimal scale. */
  String formatCurrency(BigDecimal value) {
    return formatCurrency(value, 2);
  }

  /** Formats a currency amount with an explicit scale, falling back to two decimals. */
  String formatCurrency(BigDecimal value, int priceScale) {
    if (value == null) return "-";
    int scale = priceScale < 0 ? 2 : priceScale;
    return value.setScale(scale, RoundingMode.HALF_UP).toPlainString();
  }

  /** Localizes a rejection/error reason for the player, falling back to the raw reason. */
  String reasonText(Player player, String rawReason) {
    return localizeReason(messages, player.locale(), rawReason);
  }

  /** Pure reason localization used by the player-facing adapter and tests. */
  static String localizeReason(AddonMessageService messages, Locale locale, String rawReason) {
    if (rawReason == null || rawReason.isBlank()) {
      return "";
    }
    String key = switch (rawReason.toUpperCase(Locale.ROOT)) {
      case "MARKET_NOT_OPEN" -> "ui-reject-market-not-open";
      case "RATE_LIMITED" -> "ui-reject-rate-limited";
      case "PRICE_OUTSIDE_CAGE" -> "ui-reject-price-outside-cage";
      case "SLIPPAGE_TOO_HIGH" -> "ui-reject-slippage-too-high";
      case "HOLDING_LIMIT" -> "ui-reject-holding-limit";
      case "FROZEN_LIMIT" -> "ui-reject-frozen-limit";
      case "OPEN_ORDER_LIMIT" -> "ui-reject-open-order-limit";
      case "SELF_TRADE" -> "ui-reject-self-trade";
      case "INVENTORY_FULL" -> "inventory-full";
      default -> null;
    };
    if (key != null) {
      String localized = messages.message(key, locale);
      if (!localized.equals(key)) {
        return localized;
      }
    }
    return messages.message("ui-reject-fallback", locale, rawReason);
  }

  /** Compact relative time like "3m ago", "2h ago", or "2026-08-26" for very old timestamps. */
  String relativeTime(Instant at) {
    if (at == null) {
      return "-";
    }
    long seconds = Duration.between(at, Instant.now()).getSeconds();
    if (seconds < 0) {
      return at.toString();
    }
    if (seconds < 60) {
      return seconds + "s";
    }
    if (seconds < 3600) {
      return (seconds / 60) + "m";
    }
    if (seconds < 86400) {
      return (seconds / 3600) + "h";
    }
    if (seconds < 86400L * 30) {
      return (seconds / 86400) + "d";
    }
    return at.toString();
  }
}
