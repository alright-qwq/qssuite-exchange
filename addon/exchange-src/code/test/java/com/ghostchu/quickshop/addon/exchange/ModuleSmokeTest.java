package com.ghostchu.quickshop.addon.exchange;

import org.junit.jupiter.api.Test;

import java.net.URL;

import static org.assertj.core.api.Assertions.assertThat;

class ModuleSmokeTest {
  @Test
  void exposesAddonMainClass() {
    assertThat(Main.class.getName())
        .isEqualTo("com.ghostchu.quickshop.addon.exchange.Main");
  }

  @Test
  void includesDefaultConfigFromExchangeOutput() {
    URL codeSourceUrl = Main.class.getProtectionDomain().getCodeSource().getLocation();
    URL configUrl = Main.class.getResource("/config.yml");

    assertThat(codeSourceUrl)
        .isNotNull();
    assertThat(configUrl)
        .isNotNull();
    assertThat(configUrl.toExternalForm())
        .startsWith(codeSourceUrl.toExternalForm());
  }

  @Test
  void packagesAllFirstRunConfigurationResources() {
    assertThat(Main.class.getResource("/markets.yml")).isNotNull();
    assertThat(Main.class.getResource("/messages.yml")).isNotNull();
    assertThat(Main.firstRunResources()).containsExactly("markets.yml", "messages.yml");
  }
}
