package com.ghostchu.quickshop.addon.exchange.service;

import com.ghostchu.quickshop.addon.exchange.core.model.OrderSide;
import java.math.BigDecimal;
import java.util.UUID;

public record OrderRequest(UUID requestId, UUID accountId, String marketId,
                           OrderSide side, String type, BigDecimal price,
                           BigDecimal slippageBoundary, long quantity) {}
