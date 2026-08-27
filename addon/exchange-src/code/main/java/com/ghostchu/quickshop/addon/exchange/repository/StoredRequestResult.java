package com.ghostchu.quickshop.addon.exchange.repository;

import java.util.UUID;

public record StoredRequestResult(UUID accountId, UUID requestId,
                                  String operation, String payload) {}
