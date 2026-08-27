package com.ghostchu.quickshop.addon.exchange.ui;

import com.ghostchu.quickshop.addon.exchange.platform.AddonMessageService;
import java.time.Duration;
import java.time.Instant;
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
