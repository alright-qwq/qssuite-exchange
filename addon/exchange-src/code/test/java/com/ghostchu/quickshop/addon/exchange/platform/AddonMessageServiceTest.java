package com.ghostchu.quickshop.addon.exchange.platform;

import java.util.Locale;
import java.util.Map;
import java.io.File;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AddonMessageServiceTest {
  @Test
  void replacesNamedRequestIdPlaceholderAndFallsBackToEnglish() {
    AddonMessageService messages = new AddonMessageService(Map.of(
        "en-US", Map.of("request-accepted", "Accepted: <requestId>"),
        "zh-CN", Map.of("request-accepted", "已受理：<requestId>")));

    assertThat(messages.message("request-accepted", Locale.US, "abc-123"))
        .isEqualTo("Accepted: abc-123");
    assertThat(messages.message("request-accepted", Locale.FRANCE, "abc-123"))
        .isEqualTo("Accepted: abc-123");
  }

  @Test
  void loadsBundledLocalesFromYaml() {
    AddonMessageService messages = AddonMessageService.load(
        new File("src/main/resources/messages.yml"));

    assertThat(messages.message("permission-denied", Locale.forLanguageTag("zh-CN")))
        .isEqualTo("你没有执行此交易所操作的权限。");
    assertThat(messages.message("ui-history-trade-title", Locale.forLanguageTag("zh-CN"),
        "diamond/default", "100.00")).isEqualTo("diamond/default 成交 @ 100.00");
    assertThat(messages.message("ui-confirm-order-title", Locale.forLanguageTag("zh-CN"),
        "LIMIT")).isEqualTo("确认 LIMIT 订单");
    assertThat(messages.message("ui-confirm-request", Locale.US, "abc-123"))
        .isEqualTo("Request: abc-123");
    assertThat(messages.message("ui-confirm-submit-failed", Locale.forLanguageTag("zh-CN")))
        .isEqualTo("交易请求提交失败，请稍后重试。");
    assertThat(messages.message("ui-confirm-submit-accepted", Locale.US, "order-1"))
        .isEqualTo("Exchange request accepted: order-1");
    assertThat(messages.message("ui-confirm-submit-rejected",
        Locale.forLanguageTag("zh-CN"), "order-1", "RATE_LIMITED"))
        .isEqualTo("交易所请求被拒绝：order-1（RATE_LIMITED）");
  }
}
