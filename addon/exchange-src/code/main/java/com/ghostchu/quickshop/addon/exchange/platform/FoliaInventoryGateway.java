package com.ghostchu.quickshop.addon.exchange.platform;

import com.ghostchu.quickshop.QuickShop;
import com.ghostchu.quickshop.addon.exchange.transfer.InventoryGateway;
import com.ghostchu.quickshop.addon.exchange.transfer.InventoryResult;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.BiPredicate;
import java.util.function.Function;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.persistence.PersistentDataType;

/** Runs all player-inventory access in the owning entity's Folia scheduler. */
public final class FoliaInventoryGateway implements InventoryGateway {
  private final Function<UUID, Player> playerLookup;
  private final BiConsumer<Player, Runnable> entityScheduler;
  private final BiPredicate<ItemStack, ItemStack> itemMatcher;
  private final Function<ItemStack, String> stackEncoder;
  private final NamespacedKey transferMarker;
  private final AtomicLong timeoutMillis = new AtomicLong(DEFAULT_TIMEOUT_MILLIS);

  public FoliaInventoryGateway(QuickShop quickShop, NamespacedKey transferMarker) {
    this(
        Bukkit::getPlayer,
        (player, action) -> ExchangeSchedulers.folia().getScheduler().runAtEntityWithFallback(
            player, ignored -> action.run(), () -> action.run()),
        quickShop.getItemMatcher()::matches,
        quickShop.platform()::encodeStack,
        transferMarker);
  }

  FoliaInventoryGateway(
      Function<UUID, Player> playerLookup,
      BiConsumer<Player, Runnable> entityScheduler,
      BiPredicate<ItemStack, ItemStack> itemMatcher,
      Function<ItemStack, String> stackEncoder,
      NamespacedKey transferMarker) {
    this.playerLookup = Objects.requireNonNull(playerLookup, "playerLookup");
    this.entityScheduler = Objects.requireNonNull(entityScheduler, "entityScheduler");
    this.itemMatcher = Objects.requireNonNull(itemMatcher, "itemMatcher");
    this.stackEncoder = Objects.requireNonNull(stackEncoder, "stackEncoder");
    this.transferMarker = Objects.requireNonNull(transferMarker, "transferMarker");
  }

  /** Hot-updatable wait budget for a single entity-scheduled inventory access. */
  public void updateTimeout(Duration timeout) {
    Objects.requireNonNull(timeout, "timeout");
    if (timeout.isNegative() || timeout.isZero()) {
      throw new IllegalArgumentException("timeout must be positive");
    }
    timeoutMillis.set(timeout.toMillis());
  }

  @Override
  public CompletableFuture<InventoryResult> markForDeposit(
      UUID playerId, ItemStack template, long quantity, UUID transferId) {
    if (!validRequest(template, quantity, transferId)) {
      return CompletableFuture.completedFuture(InventoryResult.UNKNOWN);
    }
    return atPlayer(
        playerId,
        player -> markForDeposit(player.getInventory(), template, quantity, transferId),
        InventoryResult.OFFLINE);
  }

  @Override
  public CompletableFuture<InventoryResult> removeMarked(UUID playerId, UUID transferId, long quantity) {
    if (transferId == null || quantity <= 0) {
      return CompletableFuture.completedFuture(InventoryResult.UNKNOWN);
    }
    return atPlayer(
        playerId,
        player -> removeMarked(player.getInventory(), transferId, quantity),
        InventoryResult.OFFLINE);
  }

  @Override
  public CompletableFuture<InventoryResult> deliverMarked(
      UUID playerId, ItemStack template, long quantity, UUID transferId) {
    if (!validRequest(template, quantity, transferId)) {
      return CompletableFuture.completedFuture(InventoryResult.UNKNOWN);
    }
    return atPlayer(
        playerId,
        player -> deliverMarked(player.getInventory(), template, quantity, transferId),
        InventoryResult.OFFLINE);
  }

  @Override
  public CompletableFuture<Long> markedQuantity(UUID playerId, UUID transferId) {
    if (transferId == null) {
      return CompletableFuture.completedFuture(0L);
    }
    return atPlayer(playerId, player -> markedQuantity(player.getInventory(), transferId), 0L);
  }

  @Override
  public CompletableFuture<InventoryResult> clearMarker(UUID playerId, UUID transferId) {
    if (transferId == null) {
      return CompletableFuture.completedFuture(InventoryResult.UNKNOWN);
    }
    return atPlayer(
        playerId,
        player -> clearMarker(player.getInventory(), transferId),
        InventoryResult.OFFLINE);
  }

  private InventoryResult markForDeposit(
      PlayerInventory inventory, ItemStack template, long quantity, UUID transferId) {
    ItemStack[] original = copyContents(inventory.getContents());
    ItemStack[] updated = copyContents(original);
    int storageSlots = inventory.getStorageContents().length;
    long remaining = quantity;
    for (int slot = 0; slot < storageSlots && remaining > 0; slot++) {
      ItemStack stack = original[slot];
      if (!isUnmarkedMatch(template, stack)) {
        continue;
      }
      int markedAmount = (int) Math.min(remaining, stack.getAmount());
      if (markedAmount == stack.getAmount()) {
        updated[slot] = markedCopy(stack, markedAmount, transferId);
      } else {
        int emptySlot = firstEmpty(updated, storageSlots);
        if (emptySlot < 0) {
          return InventoryResult.NO_SPACE;
        }
        updated[slot] = markedCopy(stack, markedAmount, transferId);
        ItemStack remainder = stack.clone();
        remainder.setAmount(stack.getAmount() - markedAmount);
        updated[emptySlot] = remainder;
      }
      remaining -= markedAmount;
    }
    if (remaining != 0) {
      return InventoryResult.NOT_ENOUGH_MATCHING_ITEMS;
    }
    return replaceStorageContents(inventory, original, updated);
  }

  private InventoryResult removeMarked(PlayerInventory inventory, UUID transferId, long quantity) {
    ItemStack[] original = copyContents(inventory.getContents());
    if (markedQuantity(original, transferId) < quantity) {
      return InventoryResult.NOT_ENOUGH_MATCHING_ITEMS;
    }
    ItemStack[] updated = copyContents(original);
    long remaining = quantity;
    for (int slot = 0; slot < updated.length && remaining > 0; slot++) {
      ItemStack stack = updated[slot];
      if (!hasMarker(stack, transferId)) {
        continue;
      }
      int removed = (int) Math.min(remaining, stack.getAmount());
      if (removed == stack.getAmount()) {
        updated[slot] = null;
      } else {
        ItemStack remainder = stack.clone();
        remainder.setAmount(stack.getAmount() - removed);
        updated[slot] = remainder;
      }
      remaining -= removed;
    }
    return replaceStorageContents(inventory, original, updated);
  }

  private InventoryResult deliverMarked(
      PlayerInventory inventory, ItemStack template, long quantity, UUID transferId) {
    ItemStack[] original = copyContents(inventory.getContents());
    int storageSlots = inventory.getStorageContents().length;
    if (quantity > maximumCapacity(storageSlots, template.getMaxStackSize())) {
      return InventoryResult.NO_SPACE;
    }
    List<ItemStack> delivery = splitMarked(template, quantity, transferId);
    if (!canFit(original, storageSlots, delivery)) {
      return InventoryResult.NO_SPACE;
    }
    try {
      Map<Integer, ItemStack> leftovers = inventory.addItem(delivery.toArray(ItemStack[]::new));
      if (leftovers.isEmpty()) {
        return InventoryResult.SUCCESS;
      }
    } catch (RuntimeException ignored) {
      // The original snapshot below restores any partial addItem mutation.
    }
    restoreContents(inventory, original);
    return InventoryResult.UNKNOWN;
  }

  private long markedQuantity(PlayerInventory inventory, UUID transferId) {
    return markedQuantity(inventory.getContents(), transferId);
  }

  private long markedQuantity(ItemStack[] contents, UUID transferId) {
    return markedQuantity(contents, contents.length, transferId);
  }

  private long markedQuantity(ItemStack[] contents, int limit, UUID transferId) {
    long quantity = 0;
    for (int slot = 0; slot < limit; slot++) {
      ItemStack stack = contents[slot];
      if (hasMarker(stack, transferId)) {
        quantity = Math.addExact(quantity, stack.getAmount());
      }
    }
    return quantity;
  }

  private InventoryResult clearMarker(PlayerInventory inventory, UUID transferId) {
    ItemStack[] original = copyContents(inventory.getContents());
    ItemStack[] updated = copyContents(original);
    for (int slot = 0; slot < updated.length; slot++) {
      ItemStack stack = updated[slot];
      if (hasMarker(stack, transferId)) {
        ItemStack cleaned = stack.clone();
        cleaned.editMeta(meta -> meta.getPersistentDataContainer().remove(transferMarker));
        updated[slot] = cleaned;
      }
    }
    return replaceStorageContents(inventory, original, updated);
  }

  private <T> CompletableFuture<T> atPlayer(
      UUID playerId, Function<Player, T> action, T offlineResult) {
    CompletableFuture<T> future = new CompletableFuture<>();
    Player player = playerLookup.apply(playerId);
    if (player == null || !player.isOnline()) {
      future.complete(offlineResult);
      return future;
    }
    try {
      entityScheduler.accept(player, () -> {
        try {
          future.complete(player.isOnline() ? action.apply(player) : offlineResult);
        } catch (RuntimeException failure) {
          future.completeExceptionally(failure);
        }
      });
    } catch (RuntimeException failure) {
      future.completeExceptionally(failure);
    }
    long timeout = timeoutMillis.get();
    if (timeout > 0) {
      future.completeOnTimeout(offlineResult, timeout, TimeUnit.MILLISECONDS);
    }
    future.whenComplete((result, failure) -> {
      if (failure == null) {
        return;
      }
      LOGGER.log(java.util.logging.Level.WARNING,
          "exchange inventory access failed for player " + playerId, failure);
    });
    return future;
  }

  private boolean validRequest(ItemStack template, long quantity, UUID transferId) {
    return template != null && !template.getType().isAir() && quantity > 0 && transferId != null;
  }

  private boolean isUnmarkedMatch(ItemStack template, ItemStack stack) {
    return stack != null
        && !stack.getType().isAir()
        && !stack.getItemMeta().getPersistentDataContainer().has(transferMarker)
        && strictlyMatches(template, stack)
        && itemMatcher.test(template, stack);
  }

  private boolean strictlyMatches(ItemStack template, ItemStack candidate) {
    ItemStack normalizedTemplate = template.clone();
    normalizedTemplate.setAmount(1);
    ItemStack normalizedCandidate = candidate.clone();
    normalizedCandidate.setAmount(1);
    return stackEncoder.apply(normalizedTemplate).equals(stackEncoder.apply(normalizedCandidate));
  }

  private boolean hasMarker(ItemStack stack, UUID transferId) {
    return stack != null
        && !stack.getType().isAir()
        && transferId.toString().equals(stack.getItemMeta().getPersistentDataContainer()
            .get(transferMarker, PersistentDataType.STRING));
  }

  private ItemStack markedCopy(ItemStack source, int amount, UUID transferId) {
    ItemStack marked = source.clone();
    marked.setAmount(amount);
    marked.editMeta(meta -> meta.getPersistentDataContainer()
        .set(transferMarker, PersistentDataType.STRING, transferId.toString()));
    return marked;
  }

  private List<ItemStack> splitMarked(ItemStack template, long quantity, UUID transferId) {
    List<ItemStack> stacks = new ArrayList<>();
    long remaining = quantity;
    int maxStackSize = template.getMaxStackSize();
    while (remaining > 0) {
      int amount = (int) Math.min(remaining, maxStackSize);
      stacks.add(markedCopy(template, amount, transferId));
      remaining -= amount;
    }
    return stacks;
  }

  private boolean canFit(ItemStack[] contents, int storageSlots, List<ItemStack> additions) {
    ItemStack[] simulated = copyContents(contents);
    for (ItemStack addition : additions) {
      int remaining = addition.getAmount();
      for (int slot = 0; slot < storageSlots; slot++) {
        ItemStack stack = simulated[slot];
        if (stack != null && stack.isSimilar(addition)) {
          int capacity = Math.min(stack.getMaxStackSize(), addition.getMaxStackSize()) - stack.getAmount();
          int inserted = Math.min(remaining, Math.max(capacity, 0));
          stack.setAmount(stack.getAmount() + inserted);
          remaining -= inserted;
          if (remaining == 0) {
            break;
          }
        }
      }
      for (int slot = 0; slot < storageSlots && remaining > 0; slot++) {
        if (isEmpty(simulated[slot])) {
          int inserted = Math.min(remaining, addition.getMaxStackSize());
          ItemStack insertedStack = addition.clone();
          insertedStack.setAmount(inserted);
          simulated[slot] = insertedStack;
          remaining -= inserted;
        }
      }
      if (remaining > 0) {
        return false;
      }
    }
    return true;
  }

  private InventoryResult replaceStorageContents(
      PlayerInventory inventory, ItemStack[] original, ItemStack[] replacement) {
    try {
      inventory.setContents(replacement);
      return InventoryResult.SUCCESS;
    } catch (RuntimeException failure) {
      restoreContents(inventory, original);
      return InventoryResult.UNKNOWN;
    }
  }

  private void restoreContents(PlayerInventory inventory, ItemStack[] original) {
    try {
      inventory.setContents(original);
    } catch (RuntimeException ignored) {
      // There is no additional recovery action available from this boundary.
    }
  }

  private int firstEmpty(ItemStack[] contents, int limit) {
    for (int slot = 0; slot < limit; slot++) {
      if (isEmpty(contents[slot])) {
        return slot;
      }
    }
    return -1;
  }

  private boolean isEmpty(ItemStack stack) {
    return stack == null || stack.getType().isAir();
  }

  private long maximumCapacity(int slots, int maxStackSize) {
    return Math.multiplyExact((long) slots, maxStackSize);
  }

  private ItemStack[] copyContents(ItemStack[] contents) {
    ItemStack[] copy = new ItemStack[contents.length];
    for (int index = 0; index < contents.length; index++) {
      copy[index] = contents[index] == null ? null : contents[index].clone();
    }
    return copy;
  }

  private static final long DEFAULT_TIMEOUT_MILLIS = Duration.ofSeconds(10).toMillis();
  private static final java.util.logging.Logger LOGGER =
      java.util.logging.Logger.getLogger("QuickShop-Exchange.Inventory");
}
