package com.ghostchu.quickshop.addon.exchange.core.service;

import java.util.UUID;

public record CommandResult(UUID requestId, String outcome) {}
