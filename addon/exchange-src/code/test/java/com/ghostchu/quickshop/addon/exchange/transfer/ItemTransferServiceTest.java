package com.ghostchu.quickshop.addon.exchange.transfer;

import com.ghostchu.quickshop.addon.exchange.persistence.ConnectionProvider;
import com.ghostchu.quickshop.addon.exchange.persistence.JdbcExchangeRepository;
import com.ghostchu.quickshop.addon.exchange.persistence.MigrationRunner;
import com.ghostchu.quickshop.addon.exchange.persistence.SqlDialect;
import com.ghostchu.quickshop.addon.exchange.persistence.TableNames;
import com.ghostchu.quickshop.addon.exchange.repository.ItemBalance;
import com.ghostchu.quickshop.addon.exchange.transfer.model.TransferRecord;
import com.ghostchu.quickshop.addon.exchange.transfer.model.TransferStatus;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ItemTransferServiceTest {
  @Test
  void depositsMarkedItemsOnce() throws Exception {
    try (TransferFixture fixture = TransferFixture.withInventoryItems(64)) {
      UUID request = UUID.randomUUID();

      TransferRecord first = fixture.items().deposit(request, fixture.player(),
          "diamond-usd", 32).join();
      TransferRecord duplicate = fixture.items().deposit(request, fixture.player(),
          "diamond-usd", 32).join();

      assertThat(first.status()).isEqualTo(TransferStatus.COMPLETED);
      assertThat(duplicate.transferId()).isEqualTo(first.transferId());
      assertThat(fixture.externalItemQuantity()).isEqualTo(32);
      assertThat(fixture.internalAvailableItems()).isEqualTo(32);
    }
  }

  @Test
  void withdrawalStaysPreparedWhenInventoryIsFull() throws Exception {
    try (TransferFixture fixture = TransferFixture.withInternalItemsAndFullInventory(64)) {
      TransferRecord result = fixture.items().withdraw(UUID.randomUUID(), fixture.player(),
          "diamond-usd", 32).join();

      assertThat(result.status()).isEqualTo(TransferStatus.PREPARED);
      assertThat(fixture.internalFrozenItems()).isEqualTo(32);
      assertThat(fixture.deliveredItems()).isZero();
    }
  }

  @Test
  void withdrawalRetriesAfterCapacityRaceWithoutFreezingAgain() throws Exception {
    try (TransferFixture fixture = TransferFixture.withInternalItemsAndFullInventory(64)) {
      fixture.gateway().deliver(InventoryResult.NO_SPACE, InventoryResult.SUCCESS);
      UUID request = UUID.randomUUID();

      TransferRecord pending = fixture.items().withdraw(request, fixture.player(),
          "diamond-usd", 32).join();
      TransferRecord completed = fixture.items().withdraw(request, fixture.player(),
          "diamond-usd", 32).join();

      assertThat(pending.status()).isEqualTo(TransferStatus.PREPARED);
      assertThat(completed.status()).isEqualTo(TransferStatus.COMPLETED);
      assertThat(fixture.internalAvailableItems()).isEqualTo(32);
      assertThat(fixture.internalFrozenItems()).isZero();
      assertThat(fixture.deliveredItems()).isEqualTo(32);
    }
  }

  private static final class TransferFixture implements AutoCloseable {
    private final UUID player = UUID.randomUUID();
    private final JdbcExchangeRepository repository;
    private final PlayerOperationSerialiser serialiser = new PlayerOperationSerialiser();
    private final FakeInventoryGateway gateway;
    private final ItemTransferService items;
    private long externalItems;

    private TransferFixture(long externalItems, long internalItems, InventoryResult initialDelivery)
        throws Exception {
      this.externalItems = externalItems;
      Path file = Files.createTempFile("quickshop-exchange-item-transfer-", ".sqlite");
      file.toFile().deleteOnExit();
      ConnectionProvider connections = () -> DriverManager.getConnection("jdbc:sqlite:" + file);
      TableNames tables = new TableNames("item_transfer_");
      new MigrationRunner(connections, SqlDialect.SQLITE, tables).migrate();
      repository = new JdbcExchangeRepository(connections, SqlDialect.SQLITE, tables);
      if (internalItems > 0) {
        repository.inTransaction(tx -> {
          tx.creditAvailableItems(player, "diamond-usd", internalItems);
          return null;
        });
      }
      gateway = new FakeInventoryGateway(initialDelivery, this);
      items = new ItemTransferService(repository, repository, gateway,
          ignored -> new ItemStack(Material.DIAMOND), serialiser,
          Clock.fixed(Instant.parse("2026-07-28T00:00:00Z"), ZoneOffset.UTC), UUID::randomUUID);
    }

    static TransferFixture withInventoryItems(long quantity) throws Exception {
      return new TransferFixture(quantity, 0, InventoryResult.SUCCESS);
    }

    static TransferFixture withInternalItemsAndFullInventory(long quantity) throws Exception {
      return new TransferFixture(0, quantity, InventoryResult.NO_SPACE);
    }

    ItemTransferService items() {
      return items;
    }

    UUID player() {
      return player;
    }

    FakeInventoryGateway gateway() {
      return gateway;
    }

    long externalItemQuantity() {
      return externalItems;
    }

    long deliveredItems() {
      return gateway.deliveredItems;
    }

    long internalAvailableItems() throws Exception {
      return balance().availableQuantity();
    }

    long internalFrozenItems() throws Exception {
      return balance().frozenQuantity();
    }

    private ItemBalance balance() throws Exception {
      return repository.inTransaction(tx -> tx.inventory(player, "diamond-usd"));
    }

    @Override
    public void close() {
      serialiser.close();
    }
  }

  private static final class FakeInventoryGateway implements InventoryGateway {
    private final ArrayDeque<InventoryResult> deliveryResults = new ArrayDeque<>();
    private final TransferFixture fixture;
    private long marked;
    private long deliveredItems;

    private FakeInventoryGateway(InventoryResult initialDelivery, TransferFixture fixture) {
      this.fixture = fixture;
      deliveryResults.add(initialDelivery);
    }

    void deliver(InventoryResult... results) {
      deliveryResults.clear();
      java.util.Collections.addAll(deliveryResults, results);
    }

    @Override
    public CompletableFuture<InventoryResult> markForDeposit(
        UUID playerId, ItemStack template, long quantity, UUID transferId) {
      if (fixture.externalItems < quantity) {
        return CompletableFuture.completedFuture(InventoryResult.NOT_ENOUGH_MATCHING_ITEMS);
      }
      marked = quantity;
      return CompletableFuture.completedFuture(InventoryResult.SUCCESS);
    }

    @Override
    public CompletableFuture<InventoryResult> removeMarked(
        UUID playerId, UUID transferId, long quantity) {
      if (marked < quantity) {
        return CompletableFuture.completedFuture(InventoryResult.NOT_ENOUGH_MATCHING_ITEMS);
      }
      marked -= quantity;
      fixture.externalItems -= quantity;
      return CompletableFuture.completedFuture(InventoryResult.SUCCESS);
    }

    @Override
    public CompletableFuture<InventoryResult> deliverMarked(
        UUID playerId, ItemStack template, long quantity, UUID transferId) {
      InventoryResult result = deliveryResults.isEmpty()
          ? InventoryResult.SUCCESS : deliveryResults.removeFirst();
      if (result == InventoryResult.SUCCESS) {
        marked += quantity;
        deliveredItems += quantity;
      }
      return CompletableFuture.completedFuture(result);
    }

    @Override
    public CompletableFuture<Long> markedQuantity(UUID playerId, UUID transferId) {
      return CompletableFuture.completedFuture(marked);
    }

    @Override
    public CompletableFuture<InventoryResult> clearMarker(UUID playerId, UUID transferId) {
      marked = 0;
      return CompletableFuture.completedFuture(InventoryResult.SUCCESS);
    }
  }
}
