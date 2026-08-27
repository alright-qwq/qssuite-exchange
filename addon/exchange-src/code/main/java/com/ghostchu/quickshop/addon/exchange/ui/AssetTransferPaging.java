package com.ghostchu.quickshop.addon.exchange.ui;

/** Fixed transfer-page sizing and pagination math for the assets page. */
final class AssetTransferPaging {
  /** Visible transfers per page; one extra row is fetched as a next-page probe. */
  static final int PAGE_SIZE = 12;

  private AssetTransferPaging() {}

  static int page(int requested) {
    return Math.max(1, requested);
  }

  static int offset(int page) {
    return Math.multiplyExact(page(page) - 1, PAGE_SIZE);
  }

  static int fetchLimit() {
    return PAGE_SIZE + 1;
  }

  static boolean hasNext(int fetched) {
    return fetched > PAGE_SIZE;
  }
}
