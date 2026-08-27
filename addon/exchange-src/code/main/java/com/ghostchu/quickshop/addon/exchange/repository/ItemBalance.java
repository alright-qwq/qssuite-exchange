package com.ghostchu.quickshop.addon.exchange.repository;

import java.util.UUID;

public record ItemBalance(UUID accountId, String marketId,
                          long availableQuantity, long frozenQuantity, long version) {}
