package com.ghostchu.quickshop.addon.exchange.transfer;

import com.ghostchu.quickshop.addon.exchange.repository.ExchangeRepository;
import com.ghostchu.quickshop.addon.exchange.transfer.model.TransferRecord;
import com.ghostchu.quickshop.addon.exchange.transfer.model.TransferStatus;
import com.ghostchu.quickshop.addon.exchange.transfer.model.TransferType;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.Clock;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Function;
import java.util.function.Supplier;
import org.bukkit.inventory.ItemStack;

public final class ItemTransferService {
  private static final String INVENTORY_CAPACITY_RACE = "inventory-capacity-race";

  private final TransferRepository transfers;
  private final ExchangeRepository repository;
  private final InventoryGateway inventory;
  private final Function<String, ItemStack> templates;
  private final PlayerOperationSerialiser serialiser;
  private final Clock clock;
  private final Supplier<UUID> ids;

  public ItemTransferService(
      TransferRepository transfers, ExchangeRepository repository, InventoryGateway inventory,
      Function<String, ItemStack> templates, PlayerOperationSerialiser serialiser,
      Clock clock, Supplier<UUID> ids) {
    this.transfers = Objects.requireNonNull(transfers, "transfers");
    this.repository = Objects.requireNonNull(repository, "repository");
    this.inventory = Objects.requireNonNull(inventory, "inventory");
    this.templates = Objects.requireNonNull(templates, "templates");
    this.serialiser = Objects.requireNonNull(serialiser, "serialiser");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.ids = Objects.requireNonNull(ids, "ids");
  }

  public CompletableFuture<TransferRecord> deposit(
      UUID requestId, UUID accountId, String marketId, long quantity) {
    requireTransferArguments(requestId, accountId, marketId, quantity);
    return serialiser.submit(accountId, () -> {
      try {
        return depositSynchronously(requestId, accountId, marketId, quantity);
      } catch (SQLException failure) {
        throw new CompletionException(failure);
      }
    });
  }

  public CompletableFuture<TransferRecord> withdraw(
      UUID requestId, UUID accountId, String marketId, long quantity) {
    requireTransferArguments(requestId, accountId, marketId, quantity);
    return serialiser.submit(accountId, () -> {
      try {
        return withdrawSynchronously(requestId, accountId, marketId, quantity);
      } catch (SQLException failure) {
        throw new CompletionException(failure);
      }
    });
  }

  private TransferRecord depositSynchronously(
      UUID requestId, UUID accountId, String marketId, long quantity) throws SQLException {
    TransferRecord prepared = transfers.create(TransferRecord.prepared(
        ids.get(), requestId, accountId, TransferType.ITEM_DEPOSIT,
        marketId, amount(quantity), clock.instant()));
    if (prepared.status() != TransferStatus.PREPARED) {
      return prepared;
    }
    ItemStack template = template(marketId);
    InventoryResult marking = markForDeposit(accountId, template, quantity, prepared.transferId());
    if (marking == InventoryResult.OFFLINE) {
      return prepared;
    }
    if (marking == InventoryResult.NOT_ENOUGH_MATCHING_ITEMS) {
      return transfers.transition(prepared.transferId(), prepared.version(), TransferStatus.PREPARED,
          TransferStatus.FAILED, "not enough matching inventory items");
    }
    if (marking != InventoryResult.SUCCESS) {
      return transfers.transition(prepared.transferId(), prepared.version(), TransferStatus.PREPARED,
          TransferStatus.REVIEW_REQUIRED, "inventory deposit marking result unknown");
    }
    TransferRecord processing = transfers.transition(prepared.transferId(), prepared.version(),
        TransferStatus.PREPARED, TransferStatus.PROCESSING, null);
    InventoryResult removal = removeMarked(accountId, processing.transferId(), quantity);
    if (removal != InventoryResult.SUCCESS) {
      return transfers.transition(processing.transferId(), processing.version(),
          TransferStatus.PROCESSING, TransferStatus.REVIEW_REQUIRED,
          "inventory deposit removal result unknown");
    }
    TransferRecord completed = repository.inTransaction(transaction -> {
      transaction.creditAvailableItems(accountId, marketId, quantity);
      transaction.appendJournal(TransferJournals.itemDeposit(processing, clock.instant()));
      return transaction.completeTransfer(processing.transferId(), processing.version());
    });
    clearMarker(accountId, completed.transferId());
    return completed;
  }

  private TransferRecord withdrawSynchronously(
      UUID requestId, UUID accountId, String marketId, long quantity) throws SQLException {
    TransferRecord candidate = TransferRecord.prepared(
        ids.get(), requestId, accountId, TransferType.ITEM_WITHDRAWAL,
        marketId, amount(quantity), clock.instant());
    TransferRecord prepared = repository.inTransaction(transaction -> {
      TransferRecord persisted = transaction.createTransfer(candidate);
      if (!persisted.transferId().equals(candidate.transferId())) {
        return persisted;
      }
      transaction.freezeItems(accountId, marketId, quantity);
      transaction.appendJournal(TransferJournals.freezeItemWithdrawal(candidate, clock.instant()));
      return candidate;
    });
    if (prepared.status() != TransferStatus.PREPARED) {
      return prepared;
    }
    ItemStack template = template(marketId);
    TransferRecord processing = transfers.transition(prepared.transferId(), prepared.version(),
        TransferStatus.PREPARED, TransferStatus.PROCESSING, null);
    InventoryResult delivery = deliverMarked(accountId, template, quantity, processing.transferId());
    if (delivery == InventoryResult.NO_SPACE || delivery == InventoryResult.OFFLINE) {
      return returnToPendingIfUnmarked(accountId, processing);
    }
    if (delivery != InventoryResult.SUCCESS) {
      return transfers.transition(processing.transferId(), processing.version(),
          TransferStatus.PROCESSING, TransferStatus.REVIEW_REQUIRED,
          "inventory withdrawal delivery result unknown");
    }
    TransferRecord completed = repository.inTransaction(transaction -> {
      transaction.consumeFrozenItems(accountId, marketId, quantity);
      transaction.appendJournal(TransferJournals.itemWithdrawal(processing, clock.instant()));
      return transaction.completeTransfer(processing.transferId(), processing.version());
    });
    clearMarker(accountId, completed.transferId());
    return completed;
  }

  private TransferRecord returnToPendingIfUnmarked(UUID accountId, TransferRecord processing)
      throws SQLException {
    if (markedQuantity(accountId, processing.transferId()) != 0) {
      return transfers.transition(processing.transferId(), processing.version(),
          TransferStatus.PROCESSING, TransferStatus.REVIEW_REQUIRED,
          "inventory withdrawal delivery marker is uncertain");
    }
    return transfers.transitionGuarded(processing.transferId(), processing.version(),
        TransferStatus.PROCESSING, TransferStatus.PREPARED, RecoveryEvidence.NO_MARKED_ITEMS,
        INVENTORY_CAPACITY_RACE);
  }

  private ItemStack template(String marketId) {
    ItemStack template = templates.apply(marketId);
    if (template == null || template.getType().isAir()) {
      throw new IllegalArgumentException("market item template is missing");
    }
    return template;
  }

  private InventoryResult markForDeposit(
      UUID accountId, ItemStack template, long quantity, UUID transferId) {
    try {
      return inventory.markForDeposit(accountId, template, quantity, transferId).join();
    } catch (RuntimeException failure) {
      return InventoryResult.UNKNOWN;
    }
  }

  private InventoryResult removeMarked(UUID accountId, UUID transferId, long quantity) {
    try {
      return inventory.removeMarked(accountId, transferId, quantity).join();
    } catch (RuntimeException failure) {
      return InventoryResult.UNKNOWN;
    }
  }

  private InventoryResult deliverMarked(
      UUID accountId, ItemStack template, long quantity, UUID transferId) {
    try {
      return inventory.deliverMarked(accountId, template, quantity, transferId).join();
    } catch (RuntimeException failure) {
      return InventoryResult.UNKNOWN;
    }
  }

  private long markedQuantity(UUID accountId, UUID transferId) {
    try {
      return inventory.markedQuantity(accountId, transferId).join();
    } catch (RuntimeException failure) {
      return -1;
    }
  }

  private void clearMarker(UUID accountId, UUID transferId) {
    try {
      inventory.clearMarker(accountId, transferId).join();
    } catch (RuntimeException ignored) {
      // A completed transfer remains settled; marker cleanup is retried by recovery.
    }
  }

  private static BigDecimal amount(long quantity) {
    return BigDecimal.valueOf(quantity);
  }

  private static void requireTransferArguments(
      UUID requestId, UUID accountId, String marketId, long quantity) {
    if (requestId == null || accountId == null || marketId == null || marketId.isBlank()
        || quantity <= 0) {
      throw new IllegalArgumentException("invalid transfer");
    }
  }
}
