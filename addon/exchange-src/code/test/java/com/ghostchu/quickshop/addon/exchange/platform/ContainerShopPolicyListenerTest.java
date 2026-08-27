package com.ghostchu.quickshop.addon.exchange.platform;

import com.ghostchu.quickshop.addon.exchange.config.MarketDefinition;
import com.ghostchu.quickshop.addon.exchange.config.MarketRegistry;
import com.ghostchu.quickshop.api.event.Phase;
import java.math.BigDecimal;
import java.util.Map;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ContainerShopPolicyListenerTest {
  @Test
  void blocksOnlyPreCancellableCreationOfExchangeOnlyItem() {
    ContainerShopPolicyListener listener = new ContainerShopPolicyListener(registry());

    assertThat(listener.shouldCancel(Phase.PRE_CANCELLABLE, Material.DIAMOND)).isTrue();
    assertThat(listener.shouldCancel(Phase.PRE_CANCELLABLE, Material.EMERALD)).isFalse();
    assertThat(listener.shouldCancel(Phase.POST, Material.DIAMOND)).isFalse();
  }

  private static MarketRegistry registry() {
    MarketDefinition definition = new MarketDefinition("diamond", "Diamond", false,
        new MarketDefinition.ItemDefinition(FingerprintMode.VANILLA_MATERIAL, "DIAMOND", null, null),
        new MarketDefinition.StructuralRules("default", new BigDecimal("100.00"),
            BigDecimal.ONE, new BigDecimal("10000.00"), new BigDecimal("0.01"), 2, 2,
            1, 2304, 100),
        new MarketDefinition.RiskRules(new BigDecimal("0.001"), new BigDecimal("0.002"),
            new BigDecimal("0.20"), new BigDecimal("0.05"), new BigDecimal("0.20"),
            new BigDecimal("0.10"), 120, new BigDecimal("0.20"), 600, 100000,
            new BigDecimal("10000000.00"), 100, 5, 60), true);
    return new MarketRegistry(Map.of("diamond", definition));
  }
}
