package com.ghostchu.quickshop.addon.exchange.ui;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AssetTransferPagingTest {
  @Test
  void clampsRequestedPageToOne() {
    assertThat(AssetTransferPaging.page(1)).isEqualTo(1);
    assertThat(AssetTransferPaging.page(0)).isEqualTo(1);
    assertThat(AssetTransferPaging.page(-5)).isEqualTo(1);
  }

  @Test
  void computesOffsetsOnTwelveRowPages() {
    assertThat(AssetTransferPaging.offset(1)).isZero();
    assertThat(AssetTransferPaging.offset(2)).isEqualTo(12);
    assertThat(AssetTransferPaging.offset(4)).isEqualTo(36);
  }

  @Test
  void capsHugePagesSoTheOffsetCanNeverOverflow() {
    assertThat(AssetTransferPaging.page(Integer.MAX_VALUE))
        .isEqualTo(AssetTransferPaging.MAX_PAGE);
    assertThat(AssetTransferPaging.offset(Integer.MAX_VALUE))
        .isEqualTo((long) (AssetTransferPaging.MAX_PAGE - 1) * AssetTransferPaging.PAGE_SIZE);
  }

  @Test
  void fetchesOneExtraRowAsNextPageProbe() {
    assertThat(AssetTransferPaging.fetchLimit()).isEqualTo(13);
    assertThat(AssetTransferPaging.hasNext(12)).isFalse();
    assertThat(AssetTransferPaging.hasNext(13)).isTrue();
  }
}
