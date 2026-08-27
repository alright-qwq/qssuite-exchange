package com.ghostchu.quickshop.addon.exchange.transfer;

import com.ghostchu.quickshop.addon.exchange.ledger.LedgerEntry;
import com.ghostchu.quickshop.addon.exchange.ledger.LedgerJournal;
import com.ghostchu.quickshop.addon.exchange.transfer.model.TransferRecord;
import java.nio.charset.StandardCharsets;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class TransferJournals {
  private TransferJournals() {}

  public static LedgerJournal moneyDeposit(TransferRecord transfer, Instant at) {
    String reference = transfer.transferId().toString();
    return new LedgerJournal(id("money-deposit:journal:" + reference), "MONEY_DEPOSIT",
        transfer.transferId(), at, null, List.of(
            new LedgerEntry(id("money-deposit:liability:" + reference),
                "liability:currency:" + transfer.accountId(), transfer.assetId(),
                transfer.amount(), at),
            new LedgerEntry(id("money-deposit:custody:" + reference),
                "custody:currency:" + transfer.assetId(), transfer.assetId(),
                transfer.amount().negate(), at)));
  }

  public static LedgerJournal freezeMoneyWithdrawal(TransferRecord transfer, Instant at) {
    String reference = transfer.transferId().toString();
    return new LedgerJournal(id("money-withdrawal-freeze:journal:" + reference),
        "MONEY_WITHDRAWAL_FREEZE", transfer.transferId(), at, null, List.of(
            new LedgerEntry(id("money-withdrawal-freeze:available:" + reference),
                "liability:currency:available:" + transfer.accountId(), transfer.assetId(),
                transfer.amount().negate(), at),
            new LedgerEntry(id("money-withdrawal-freeze:frozen:" + reference),
                "liability:currency:frozen:" + transfer.accountId(), transfer.assetId(),
                transfer.amount(), at)));
  }

  public static LedgerJournal moneyWithdrawal(TransferRecord transfer, Instant at) {
    String reference = transfer.transferId().toString();
    return new LedgerJournal(id("money-withdrawal:journal:" + reference), "MONEY_WITHDRAWAL",
        transfer.transferId(), at, null, List.of(
            new LedgerEntry(id("money-withdrawal:liability:" + reference),
                "liability:currency:" + transfer.accountId(), transfer.assetId(),
                transfer.amount().negate(), at),
            new LedgerEntry(id("money-withdrawal:custody:" + reference),
                "custody:currency:" + transfer.assetId(), transfer.assetId(),
                transfer.amount(), at)));
  }

  public static LedgerJournal releaseMoneyWithdrawal(TransferRecord transfer, Instant at) {
    String reference = transfer.transferId().toString();
    return new LedgerJournal(id("money-withdrawal-release:journal:" + reference),
        "MONEY_WITHDRAWAL_RELEASE", transfer.transferId(), at, null, List.of(
            new LedgerEntry(id("money-withdrawal-release:frozen:" + reference),
                "liability:currency:frozen:" + transfer.accountId(), transfer.assetId(),
                transfer.amount().negate(), at),
            new LedgerEntry(id("money-withdrawal-release:available:" + reference),
                "liability:currency:available:" + transfer.accountId(), transfer.assetId(),
                transfer.amount(), at)));
  }

  public static LedgerJournal itemDeposit(TransferRecord transfer, Instant at) {
    String reference = transfer.transferId().toString();
    BigDecimal quantity = transfer.amount();
    return new LedgerJournal(id("item-deposit:journal:" + reference), "ITEM_DEPOSIT",
        transfer.transferId(), at, null, List.of(
            new LedgerEntry(id("item-deposit:liability:" + reference),
                "liability:item:" + transfer.accountId(), transfer.assetId(), quantity, at),
            new LedgerEntry(id("item-deposit:custody:" + reference),
                "custody:item:" + transfer.assetId(), transfer.assetId(), quantity.negate(), at)));
  }

  public static LedgerJournal freezeItemWithdrawal(TransferRecord transfer, Instant at) {
    String reference = transfer.transferId().toString();
    BigDecimal quantity = transfer.amount();
    return new LedgerJournal(id("item-withdrawal-freeze:journal:" + reference),
        "ITEM_WITHDRAWAL_FREEZE", transfer.transferId(), at, null, List.of(
            new LedgerEntry(id("item-withdrawal-freeze:available:" + reference),
                "liability:item:available:" + transfer.accountId(), transfer.assetId(),
                quantity.negate(), at),
            new LedgerEntry(id("item-withdrawal-freeze:frozen:" + reference),
                "liability:item:frozen:" + transfer.accountId(), transfer.assetId(), quantity, at)));
  }

  public static LedgerJournal itemWithdrawal(TransferRecord transfer, Instant at) {
    String reference = transfer.transferId().toString();
    BigDecimal quantity = transfer.amount();
    return new LedgerJournal(id("item-withdrawal:journal:" + reference), "ITEM_WITHDRAWAL",
        transfer.transferId(), at, null, List.of(
            new LedgerEntry(id("item-withdrawal:liability:" + reference),
                "liability:item:" + transfer.accountId(), transfer.assetId(), quantity.negate(), at),
            new LedgerEntry(id("item-withdrawal:custody:" + reference),
                "custody:item:" + transfer.assetId(), transfer.assetId(), quantity, at)));
  }

  public static LedgerJournal releaseItemWithdrawal(TransferRecord transfer, Instant at) {
    String reference = transfer.transferId().toString();
    BigDecimal quantity = transfer.amount();
    return new LedgerJournal(id("item-withdrawal-release:journal:" + reference),
        "ITEM_WITHDRAWAL_RELEASE", transfer.transferId(), at, null, List.of(
            new LedgerEntry(id("item-withdrawal-release:frozen:" + reference),
                "liability:item:frozen:" + transfer.accountId(), transfer.assetId(),
                quantity.negate(), at),
            new LedgerEntry(id("item-withdrawal-release:available:" + reference),
                "liability:item:available:" + transfer.accountId(), transfer.assetId(),
                quantity, at)));
  }

  private static UUID id(String value) {
    return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8));
  }
}
