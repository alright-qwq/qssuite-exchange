package com.ghostchu.quickshop.addon.exchange.transfer;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.bukkit.inventory.ItemStack;

public interface InventoryGateway {
  CompletableFuture<InventoryResult> markForDeposit(
      UUID playerId, ItemStack template, long quantity, UUID transferId);

  CompletableFuture<InventoryResult> removeMarked(UUID playerId, UUID transferId, long quantity);

  CompletableFuture<InventoryResult> deliverMarked(
      UUID playerId, ItemStack template, long quantity, UUID transferId);

  CompletableFuture<Long> markedQuantity(UUID playerId, UUID transferId);

  CompletableFuture<InventoryResult> clearMarker(UUID playerId, UUID transferId);
}
