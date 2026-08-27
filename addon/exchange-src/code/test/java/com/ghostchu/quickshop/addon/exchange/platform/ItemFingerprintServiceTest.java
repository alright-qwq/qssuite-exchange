package com.ghostchu.quickshop.addon.exchange.platform;

import be.seeseemelk.mockbukkit.MockBukkit;
import java.util.Base64;
import java.util.UUID;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ItemFingerprintServiceTest {
  @BeforeAll
  static void startMockServer() {
    MockBukkit.mock();
  }

  @AfterAll
  static void stopMockServer() {
    MockBukkit.unmock();
  }

  @Test
  void strictFingerprintIgnoresAmountAndTransferMarkerOnly() {
    ItemFingerprintFixture fixture = ItemFingerprintFixture.create();
    ItemStack one = new ItemStack(Material.DIAMOND, 1);
    ItemStack sixtyFour = one.clone();
    sixtyFour.setAmount(64);
    fixture.mark(sixtyFour, UUID.randomUUID());
    ItemStack named = one.clone();
    named.editMeta(meta -> meta.setDisplayName("Special"));
    ItemStack customData = one.clone();
    customData.editMeta(meta -> meta.getPersistentDataContainer()
        .set(new NamespacedKey("other-plugin", "custom"), PersistentDataType.STRING, "value"));

    assertThat(fixture.service().fingerprint(one, FingerprintMode.STRICT))
        .isEqualTo(fixture.service().fingerprint(sixtyFour, FingerprintMode.STRICT));
    assertThat(fixture.service().fingerprint(named, FingerprintMode.STRICT))
        .isNotEqualTo(fixture.service().fingerprint(one, FingerprintMode.STRICT));
    assertThat(fixture.service().fingerprint(customData, FingerprintMode.STRICT))
        .isNotEqualTo(fixture.service().fingerprint(one, FingerprintMode.STRICT));
  }

  @Test
  void vanillaMaterialMarketRejectsMetadata() {
    ItemFingerprintFixture fixture = ItemFingerprintFixture.create();
    ItemStack vanilla = new ItemStack(Material.DIAMOND);
    ItemStack named = new ItemStack(Material.DIAMOND);
    named.editMeta(meta -> meta.setDisplayName("Special"));

    assertThat(fixture.service().acceptsVanillaMaterial(vanilla, Material.DIAMOND)).isTrue();
    assertThat(fixture.service().acceptsVanillaMaterial(named, Material.DIAMOND)).isFalse();
  }

  private record ItemFingerprintFixture(ItemFingerprintService service, NamespacedKey marker) {
    static ItemFingerprintFixture create() {
      NamespacedKey marker = new NamespacedKey("exchange", "transfer");
      return new ItemFingerprintFixture(
          new ItemFingerprintService(
              stack -> Base64.getEncoder().encodeToString(stack.serializeAsBytes()),
              ItemStack::isSimilar,
              marker),
          marker);
    }

    void mark(ItemStack stack, UUID transferId) {
      stack.editMeta(meta -> meta.getPersistentDataContainer()
          .set(marker, PersistentDataType.STRING, transferId.toString()));
    }
  }
}
