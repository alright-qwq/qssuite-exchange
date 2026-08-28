package com.ghostchu.quickshop.addon.exchange.platform;

import com.tcoded.folialib.FoliaLib;
import java.util.Objects;
import org.bukkit.plugin.java.JavaPlugin;

/** Owns the addon-bundled FoliaLib instance so scheduling never crosses plugin classloaders. */
public final class ExchangeSchedulers {
  private static FoliaLib folia;

  private ExchangeSchedulers() {
  }

  public static synchronized void initialize(JavaPlugin plugin) {
    if (folia == null) {
      folia = new FoliaLib(Objects.requireNonNull(plugin, "plugin"));
    }
  }

  public static FoliaLib folia() {
    if (folia == null) {
      throw new IllegalStateException("exchange scheduler is not initialized");
    }
    return folia;
  }
}
