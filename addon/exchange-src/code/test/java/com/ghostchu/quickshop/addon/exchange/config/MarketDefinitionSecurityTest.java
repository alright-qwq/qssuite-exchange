package com.ghostchu.quickshop.addon.exchange.config;

import com.ghostchu.quickshop.addon.exchange.platform.FingerprintMode;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MarketDefinitionSecurityTest {
  @Test
  void acceptsPureVirtualSecurityDefinition() {
    MarketDefinition definition = new MarketDefinition("ABC", "Alpha", true, null, rules(),
        risks(), false, AssetType.VIRTUAL_SECURITY,
        new SecurityDefinition("ABC", "Alpha", "Concept stock", "default",
            new BigDecimal("10.00"), 1_000, 1));

    assertThat(definition.assetType()).isEqualTo(AssetType.VIRTUAL_SECURITY);
    assertThat(definition.item()).isNull();
    assertThat(definition.security().symbol()).isEqualTo("ABC");
  }

  @Test
  void rejectsVirtualSecurityWithItemDefinition() {
    MarketDefinition.ItemDefinition item = new MarketDefinition.ItemDefinition(
        FingerprintMode.VANILLA_MATERIAL, "DIAMOND", null, null);
    assertThatThrownBy(() -> new MarketDefinition("ABC", "Alpha", true, item, rules(),
        risks(), false, AssetType.VIRTUAL_SECURITY,
        new SecurityDefinition("ABC", "Alpha", "Concept stock", "default",
            new BigDecimal("10.00"), 1_000, 1)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("must not define an item");
  }

  @Test
  void validatesSymbolSupplyAndUnit() {
    assertThatThrownBy(() -> new SecurityDefinition("ab", "Alpha", "x", "default",
        BigDecimal.ONE, 10, 1)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new SecurityDefinition("ABC", "Alpha", "x", "default",
        BigDecimal.ONE, 10, 3)).isInstanceOf(IllegalArgumentException.class);
  }

  private static MarketDefinition.StructuralRules rules() {
    return new MarketDefinition.StructuralRules("default", new BigDecimal("10.00"),
        BigDecimal.ONE, new BigDecimal("1000.00"), new BigDecimal("0.01"), 2, 2,
        1, 1000, 100);
  }

  private static MarketDefinition.RiskRules risks() {
    return new MarketDefinition.RiskRules(BigDecimal.ZERO, BigDecimal.ZERO,
        new BigDecimal("0.20"), new BigDecimal("0.05"), new BigDecimal("0.20"),
        new BigDecimal("0.10"), 120, new BigDecimal("0.20"), 600, 100000,
        new BigDecimal("10000000.00"), 100, 5, 60);
  }
}
