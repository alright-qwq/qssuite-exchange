package com.ghostchu.quickshop.addon.exchange.platform;

import be.seeseemelk.mockbukkit.MockBukkit;
import be.seeseemelk.mockbukkit.ServerMock;
import be.seeseemelk.mockbukkit.entity.PlayerMock;
import com.ghostchu.quickshop.addon.exchange.transfer.InventoryResult;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FoliaInventoryGatewayTest {
  private static ServerMock server;

  @BeforeAll
  static void startMockServer() {
    server = MockBukkit.mock();
  }

  @AfterAll
  static void stopMockServer() {
    MockBukkit.unmock();
  }

  @BeforeEach
  void clearPlayers() {
    server.getOnlinePlayers().forEach(player -> player.kick());
  }

  @Test
  void fullInventoryDoesNotReceivePartialMarkedDelivery() {
    GatewayFixture fixture = onlineFixture();
    for (int slot = 0; slot < fixture.player().getInventory().getStorageContents().length; slot++) {
      fixture.player().getInventory().setItem(slot, new ItemStack(Material.STONE, 64));
    }
    UUID transferId = UUID.randomUUID();

    InventoryResult result = fixture.gateway().deliverMarked(
        fixture.player().getUniqueId(), new ItemStack(Material.DIAMOND), 64, transferId).join();

    assertThat(result).isEqualTo(InventoryResult.NO_SPACE);
    assertThat(fixture.schedulerCalls()).hasValue(1);
    assertThat(markedQuantity(fixture.player(), fixture.marker(), transferId)).isZero();
    assertThat(fixture.player().getInventory().all(Material.DIAMOND)).isEmpty();
  }

  @Test
  void offlinePlayerIsReportedWithoutSchedulingInventoryAccess() {
    AtomicInteger schedulerCalls = new AtomicInteger();
    FoliaInventoryGateway gateway = new FoliaInventoryGateway(
        ignored -> null,
        (player, action) -> schedulerCalls.incrementAndGet(),
        ItemStack::isSimilar,
        FoliaInventoryGatewayTest::encode,
        new NamespacedKey("exchange", "transfer"));

    InventoryResult result = gateway.deliverMarked(
        UUID.randomUUID(), new ItemStack(Material.DIAMOND), 1, UUID.randomUUID()).join();

    assertThat(result).isEqualTo(InventoryResult.OFFLINE);
    assertThat(schedulerCalls).hasValue(0);
  }

  @Test
  void insufficientDepositDoesNotAlterAnyMatchingStack() {
    GatewayFixture fixture = onlineFixture();
    fixture.player().getInventory().setItem(0, new ItemStack(Material.DIAMOND, 32));
    UUID transferId = UUID.randomUUID();

    InventoryResult result = fixture.gateway().markForDeposit(
        fixture.player().getUniqueId(), new ItemStack(Material.DIAMOND), 64, transferId).join();

    assertThat(result).isEqualTo(InventoryResult.NOT_ENOUGH_MATCHING_ITEMS);
    assertThat(fixture.player().getInventory().getItem(0).getAmount()).isEqualTo(32);
    assertThat(markedQuantity(fixture.player(), fixture.marker(), transferId)).isZero();
  }

  @Test
  void depositRejectsMetadataThatOnlyACustomMatcherAccepts() {
    PlayerMock player = server.addPlayer();
    NamespacedKey marker = new NamespacedKey("exchange", "transfer");
    FoliaInventoryGateway gateway = new FoliaInventoryGateway(
        playerId -> playerId.equals(player.getUniqueId()) ? player : null,
        (scheduledPlayer, task) -> task.run(),
        (template, candidate) -> true,
        FoliaInventoryGatewayTest::encode,
        marker);
    ItemStack namedDiamond = new ItemStack(Material.DIAMOND, 64);
    namedDiamond.editMeta(meta -> meta.setDisplayName("not the market template"));
    player.getInventory().setItem(0, namedDiamond);
    UUID transferId = UUID.randomUUID();

    InventoryResult result = gateway.markForDeposit(
        player.getUniqueId(), new ItemStack(Material.DIAMOND), 64, transferId).join();

    assertThat(result).isEqualTo(InventoryResult.NOT_ENOUGH_MATCHING_ITEMS);
    assertThat(markedQuantity(player, marker, transferId)).isZero();
  }

  @Test
  void depositMarkingTagsExactlyTheRequestedQuantity() {
    GatewayFixture fixture = onlineFixture();
    fixture.player().getInventory().setItem(0, new ItemStack(Material.DIAMOND, 64));
    fixture.player().getInventory().setItem(1, new ItemStack(Material.DIAMOND, 64));
    UUID transferId = UUID.randomUUID();

    InventoryResult result = fixture.gateway().markForDeposit(
        fixture.player().getUniqueId(), new ItemStack(Material.DIAMOND), 96, transferId).join();

    assertThat(result).isEqualTo(InventoryResult.SUCCESS);
    assertThat(markedQuantity(fixture.player(), fixture.marker(), transferId)).isEqualTo(96);
    assertThat(unmarkedQuantity(fixture.player(), fixture.marker(), Material.DIAMOND)).isEqualTo(32);
  }

  @Test
  void partialDepositWithoutAnEmptySlotDoesNotChangeItems() {
    GatewayFixture fixture = onlineFixture();
    for (int slot = 0; slot < fixture.player().getInventory().getStorageContents().length; slot++) {
      fixture.player().getInventory().setItem(slot, new ItemStack(Material.STONE, 64));
    }
    fixture.player().getInventory().setItem(0, new ItemStack(Material.DIAMOND, 64));
    UUID transferId = UUID.randomUUID();

    InventoryResult result = fixture.gateway().markForDeposit(
        fixture.player().getUniqueId(), new ItemStack(Material.DIAMOND), 32, transferId).join();

    assertThat(result).isEqualTo(InventoryResult.NO_SPACE);
    assertThat(fixture.player().getInventory().getItem(0).getAmount()).isEqualTo(64);
    assertThat(markedQuantity(fixture.player(), fixture.marker(), transferId)).isZero();
  }

  @Test
  void removesOnlyTheRequestedTransferMarker() {
    GatewayFixture fixture = onlineFixture();
    UUID requestedTransfer = UUID.randomUUID();
    UUID otherTransfer = UUID.randomUUID();
    fixture.player().getInventory().setItem(0, markedStack(fixture.marker(), requestedTransfer, 64));
    fixture.player().getInventory().setItem(1, markedStack(fixture.marker(), otherTransfer, 64));

    InventoryResult result = fixture.gateway().removeMarked(
        fixture.player().getUniqueId(), requestedTransfer, 32).join();

    assertThat(result).isEqualTo(InventoryResult.SUCCESS);
    assertThat(markedQuantity(fixture.player(), fixture.marker(), requestedTransfer)).isEqualTo(32);
    assertThat(markedQuantity(fixture.player(), fixture.marker(), otherTransfer)).isEqualTo(64);
  }

  @Test
  void clearingOneMarkerPreservesOtherTransfers() {
    GatewayFixture fixture = onlineFixture();
    UUID requestedTransfer = UUID.randomUUID();
    UUID otherTransfer = UUID.randomUUID();
    fixture.player().getInventory().setItem(0, markedStack(fixture.marker(), requestedTransfer, 64));
    fixture.player().getInventory().setItem(1, markedStack(fixture.marker(), otherTransfer, 64));

    InventoryResult result = fixture.gateway().clearMarker(
        fixture.player().getUniqueId(), requestedTransfer).join();

    assertThat(result).isEqualTo(InventoryResult.SUCCESS);
    assertThat(markedQuantity(fixture.player(), fixture.marker(), requestedTransfer)).isZero();
    assertThat(markedQuantity(fixture.player(), fixture.marker(), otherTransfer)).isEqualTo(64);
  }

  @Test
  void removesMarkedItemsMovedToTheOffHand() {
    GatewayFixture fixture = onlineFixture();
    UUID transferId = UUID.randomUUID();
    fixture.player().getInventory().setItemInOffHand(markedStack(fixture.marker(), transferId, 1));

    InventoryResult result = fixture.gateway().removeMarked(
        fixture.player().getUniqueId(), transferId, 1).join();

    assertThat(result).isEqualTo(InventoryResult.SUCCESS);
    assertThat(fixture.player().getInventory().getItemInOffHand().getType()).isEqualTo(Material.AIR);
  }

  @Test
  void deliverySplitsMarkedItemsAtTheStackLimit() {
    GatewayFixture fixture = onlineFixture();
    UUID transferId = UUID.randomUUID();

    InventoryResult result = fixture.gateway().deliverMarked(
        fixture.player().getUniqueId(), new ItemStack(Material.DIAMOND), 65, transferId).join();

    assertThat(result).isEqualTo(InventoryResult.SUCCESS);
    assertThat(markedQuantity(fixture.player(), fixture.marker(), transferId)).isEqualTo(65);
    assertThat(fixture.player().getInventory().getItem(0).getAmount()).isEqualTo(64);
    assertThat(fixture.player().getInventory().getItem(1).getAmount()).isEqualTo(1);
  }

  private static GatewayFixture onlineFixture() {
    PlayerMock player = server.addPlayer();
    NamespacedKey marker = new NamespacedKey("exchange", "transfer");
    AtomicInteger schedulerCalls = new AtomicInteger();
    FoliaInventoryGateway gateway = new FoliaInventoryGateway(
        playerId -> playerId.equals(player.getUniqueId()) ? player : null,
        (scheduledPlayer, task) -> {
          schedulerCalls.incrementAndGet();
          task.run();
        },
        ItemStack::isSimilar,
        FoliaInventoryGatewayTest::encode,
        marker);
    return new GatewayFixture(player, marker, gateway, schedulerCalls);
  }

  private static ItemStack markedStack(NamespacedKey marker, UUID transferId, int amount) {
    ItemStack stack = new ItemStack(Material.DIAMOND, amount);
    stack.editMeta(meta -> meta.getPersistentDataContainer()
        .set(marker, PersistentDataType.STRING, transferId.toString()));
    return stack;
  }

  private static String encode(ItemStack stack) {
    return Base64.getEncoder().encodeToString(stack.serializeAsBytes());
  }

  private static long markedQuantity(PlayerMock player, NamespacedKey marker, UUID transferId) {
    return java.util.Arrays.stream(player.getInventory().getStorageContents())
        .filter(stack -> stack != null && !stack.getType().isAir())
        .filter(stack -> transferId.toString().equals(stack.getItemMeta()
            .getPersistentDataContainer().get(marker, PersistentDataType.STRING)))
        .mapToLong(ItemStack::getAmount)
        .sum();
  }

  private static long unmarkedQuantity(PlayerMock player, NamespacedKey marker, Material material) {
    return java.util.Arrays.stream(player.getInventory().getStorageContents())
        .filter(stack -> stack != null && stack.getType() == material)
        .filter(stack -> !stack.getItemMeta().getPersistentDataContainer().has(marker))
        .mapToLong(ItemStack::getAmount)
        .sum();
  }

  private record GatewayFixture(
      PlayerMock player,
      NamespacedKey marker,
      FoliaInventoryGateway gateway,
      AtomicInteger schedulerCalls) {
  }
}
