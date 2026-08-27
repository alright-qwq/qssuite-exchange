package com.ghostchu.quickshop.addon.exchange.core.service;

import java.util.Optional;
import java.util.UUID;

public interface RequestResultStore {
  Optional<CommandResult> find(UUID accountId, UUID requestId);

  CommandResult putIfAbsent(UUID accountId, UUID requestId, CommandResult result);
}
