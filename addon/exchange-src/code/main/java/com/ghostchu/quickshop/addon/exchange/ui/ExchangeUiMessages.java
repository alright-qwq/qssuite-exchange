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
  private volatile int lastPriceScale = -1;

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

  /** Formats a currency amount for the player's locale using the configured currency scale. */
  String formatCurrency(BigDecimal value) {
    return formatCurrency(value, lastPriceScale);
  }

  /** Formats a currency amount with an explicit scale, falling back to two decimals. */
  String formatCurrency(BigDecimal value, int priceScale) {
    if (value == null) return "-";
    int scale = priceScale < 0 ? 2 : priceScale;
    return value.setScale(scale, RoundingMode.HALF_UP).toPlainString();
  }

  /** Records the latest price scale observed by a page render for aggregate displays. */
  void notePriceScale(int priceScale) {
    lastPriceScale = priceScale;
  }

  int lastPriceScale() {
    return lastPriceScale;
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
