package com.ghostchu.quickshop.addon.exchange.repository;

import java.math.BigDecimal;
import java.util.UUID;

public record CurrencyBalance(UUID accountId, String currencyId,
                              BigDecimal available, BigDecimal frozen, long version) {}
