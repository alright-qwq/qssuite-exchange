package com.ghostchu.quickshop.addon.exchange.command;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Immutable player rollout gate. Administrative routes apply their own permissions instead. */
public record RolloutPolicy(boolean whitelistEnabled, Set<UUID> allowedPlayers) {
  public static final RolloutPolicy DISABLED = new RolloutPolicy(false, Set.of());

  public RolloutPolicy {
    allowedPlayers = Set.copyOf(Objects.requireNonNull(allowedPlayers, "allowedPlayers"));
  }

  public boolean allows(UUID accountId) {
    return !whitelistEnabled || allowedPlayers.contains(Objects.requireNonNull(accountId, "accountId"));
  }
}
