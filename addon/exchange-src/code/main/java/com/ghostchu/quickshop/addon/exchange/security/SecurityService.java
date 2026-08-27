package com.ghostchu.quickshop.addon.exchange.security;

import com.ghostchu.quickshop.addon.exchange.core.model.MarketStatus;
import com.ghostchu.quickshop.addon.exchange.repository.ExchangeRepository;
import com.ghostchu.quickshop.addon.exchange.repository.ExchangeTransaction;
import com.ghostchu.quickshop.addon.exchange.repository.ExchangeTransaction.MarketState;
import com.ghostchu.quickshop.addon.exchange.repository.SecurityAuditRecord;
import com.ghostchu.quickshop.addon.exchange.repository.SecurityBalance;
import com.ghostchu.quickshop.addon.exchange.repository.SecurityDefinitionState;
import com.ghostchu.quickshop.addon.exchange.repository.SecurityLedgerEntry;
import com.ghostchu.quickshop.addon.exchange.config.AssetType;
import com.ghostchu.quickshop.addon.exchange.config.MarketDefinition;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Audited, idempotent lifecycle operations for pure-ledger virtual securities.
 *
 * <p>Every mutation runs in one repository transaction, writes an immutable security audit row
 * keyed by request id, and only mutates balances through the security ledger tables.</p>
 */
public final class SecurityService {
  private static final String CREATE_ACTION = "STOCK_CREATE";
  private static final String ISSUE_ACTION = "STOCK_ISSUE";
  private static final String PAUSE_ACTION = "STOCK_PAUSE";
  private static final String RESUME_ACTION = "STOCK_RESUME";
  private static final String CLOSE_ACTION = "STOCK_CLOSE";
  private static final String TRANSFER_ACTION = "STOCK_TRANSFER";

  private final ExchangeRepository repository;

  public SecurityService(ExchangeRepository repository) {
    this.repository = Objects.requireNonNull(repository, "repository");
  }

  public SecurityMutationResult create(
      UUID actorId, UUID requestId, String marketId, String symbol, String name,
      String description, String currencyId, BigDecimal basePrice, long totalSupply,
      long minimumUnit) throws SQLException {
    Objects.requireNonNull(actorId, "actorId");
    Objects.requireNonNull(requestId, "requestId");
    requireText(marketId, "market id");
    requireText(symbol, "symbol");
    if (!symbol.matches("[A-Z][A-Z0-9_]{0,15}")) {
      throw new IllegalArgumentException(
          "symbol must be uppercase letters, digits, and underscores (up to 16 characters)");
    }
    requireText(name, "name");
    requireText(description, "description");
    requireText(currencyId, "currency id");
    requirePositive(basePrice, "base price");
    requirePositive(totalSupply, "total supply");
    requirePositive(minimumUnit, "minimum unit");
    if (totalSupply % minimumUnit != 0) {
      throw new IllegalArgumentException("total supply must be a multiple of minimum unit");
    }
    if (minimumUnit > totalSupply) {
      throw new IllegalArgumentException("minimum unit must not exceed total supply");
    }
    if (totalSupply < 10) {
      throw new IllegalArgumentException("total supply must be at least 10");
    }
    MarketDefinition definition = buildMarketDefinition(
        marketId, symbol, name, description, currencyId, basePrice, totalSupply, minimumUnit);
    Instant now = Instant.now();
    String payload = createPayload(marketId, symbol, name, currencyId, basePrice,
        totalSupply, minimumUnit);
    return repository.inTransaction(tx -> {
      SecurityAuditRecord duplicate = requireNoDuplicate(tx, requestId, CREATE_ACTION, payload);
      if (duplicate != null) {
        return replayed(tx, marketId, CREATE_ACTION, duplicate);
      }
      SecurityDefinitionState existing = tx.existingSecurityDefinition(marketId).orElse(null);
      if (existing != null) {
        throw new IllegalArgumentException("security already exists for market: " + marketId);
      }
      boolean marketRowExists = tx.marketExists(marketId);
      if (marketRowExists) {
        String assetType = tx.marketAssetType(marketId).orElse(null);
        if (AssetType.PHYSICAL_ITEM.name().equals(assetType)) {
          throw new IllegalArgumentException(
              "cannot create a security on an existing physical market: " + marketId);
        }
      } else {
        // The persisted market row also materialises a CLOSED security row; it is promoted to
        // OPEN below instead of being inserted twice.
        tx.insertMarket(definition, false);
      }
      SecurityDefinitionState definitionState = new SecurityDefinitionState(
          marketId, symbol, name, description, currencyId, basePrice, totalSupply, 0,
          minimumUnit, SecurityStatus.OPEN.name(), null, now, now, 0);
      SecurityDefinitionState persisted = tx.existingSecurityDefinition(marketId).orElse(null);
      if (persisted != null) {
        tx.updateSecurityDefinition(definitionState, persisted.version());
      } else {
        tx.insertSecurityDefinition(definitionState);
      }
      appendAudit(tx, actorId, requestId, marketId, CREATE_ACTION, payload, "SUCCESS", now);
      return new SecurityMutationResult(
          marketId, symbol, CREATE_ACTION, SecurityStatus.OPEN.name(), payload, false);
    });
  }

  public SecurityMutationResult issue(
      UUID actorId, UUID requestId, String marketId, UUID targetAccount, long quantity,
      String reason) throws SQLException {
    Objects.requireNonNull(actorId, "actorId");
    Objects.requireNonNull(requestId, "requestId");
    Objects.requireNonNull(targetAccount, "targetAccount");
    requireText(marketId, "market id");
    requirePositive(quantity, "quantity");
    String normalizedReason = normalizeReason(reason);
    Instant now = Instant.now();
    return repository.inTransaction(tx -> {
      SecurityDefinitionState definition = tx.securityDefinition(marketId);
      SecurityAuditRecord duplicate = requireNoDuplicate(tx, requestId, ISSUE_ACTION,
          issuePayload(marketId, targetAccount, quantity));
      if (duplicate != null) {
        return replayed(tx, marketId, ISSUE_ACTION, duplicate);
      }
      requireIssuable(definition, quantity);
      long issued = Math.addExact(definition.issuedSupply(), quantity);
      tx.creditAvailableSecurity(targetAccount, marketId, quantity);
      SecurityDefinitionState updated = new SecurityDefinitionState(
          definition.marketId(), definition.symbol(), definition.name(),
          definition.description(), definition.currencyId(), definition.basePrice(),
          definition.totalSupply(), issued, definition.minimumUnit(), definition.status(),
          definition.recoveryAccount(), definition.createdAt(), now, definition.version());
      tx.updateSecurityDefinition(updated, definition.version());
      tx.appendSecurityLedger(new SecurityLedgerEntry(
          UUID.randomUUID(), requestId.toString(), marketId, targetAccount, "ISSUE",
          quantity, quantity, 0, "ADMIN", requestId.toString(), actorId,
          normalizedReason, now));
      appendAudit(tx, actorId, requestId, marketId, ISSUE_ACTION,
          issuePayload(marketId, targetAccount, quantity), "SUCCESS", now);
      return new SecurityMutationResult(
          marketId, definition.symbol(), ISSUE_ACTION, definition.status(),
          issuePayload(marketId, targetAccount, quantity), false);
    });
  }

  public SecurityMutationResult transfer(
      UUID actorId, UUID requestId, String marketId, UUID fromAccount, UUID toAccount,
      long quantity, String reason) throws SQLException {
    Objects.requireNonNull(actorId, "actorId");
    Objects.requireNonNull(requestId, "requestId");
    Objects.requireNonNull(fromAccount, "fromAccount");
    Objects.requireNonNull(toAccount, "toAccount");
    requireText(marketId, "market id");
    requirePositive(quantity, "quantity");
    if (fromAccount.equals(toAccount)) {
      throw new IllegalArgumentException("transfer source and target must differ");
    }
    String normalizedReason = normalizeReason(reason);
    Instant now = Instant.now();
    String payload = transferPayload(marketId, fromAccount, toAccount, quantity);
    return repository.inTransaction(tx -> {
      SecurityDefinitionState definition = tx.securityDefinition(marketId);
      SecurityAuditRecord duplicate = requireNoDuplicate(tx, requestId, TRANSFER_ACTION, payload);
      if (duplicate != null) {
        return replayed(tx, marketId, TRANSFER_ACTION, duplicate);
      }
      requireTransferable(definition, quantity);
      SecurityBalance source = tx.securityBalance(fromAccount, marketId);
      requireSourceBalance(source, quantity);
      tx.freezeSecurity(fromAccount, marketId, quantity);
      tx.consumeFrozenSecurity(fromAccount, marketId, quantity);
      tx.creditAvailableSecurity(toAccount, marketId, quantity);
      tx.appendSecurityLedger(new SecurityLedgerEntry(
          UUID.randomUUID(), requestId.toString(), marketId, fromAccount, "TRANSFER",
          -quantity, -quantity, 0, "ADMIN", requestId.toString(), actorId,
          normalizedReason, now));
      tx.appendSecurityLedger(new SecurityLedgerEntry(
          UUID.randomUUID(), requestId + ":to", marketId, toAccount, "TRANSFER",
          quantity, quantity, 0, "ADMIN", requestId.toString(), actorId,
          normalizedReason, now));
      appendAudit(tx, actorId, requestId, marketId, TRANSFER_ACTION, payload,
          "SUCCESS", now);
      return new SecurityMutationResult(
          marketId, definition.symbol(), TRANSFER_ACTION, definition.status(),
          payload, false);
    });
  }

  public SecurityMutationResult pause(
      UUID actorId, UUID requestId, String marketId, String reason) throws SQLException {
    return changeStatus(actorId, requestId, marketId, reason,
        PAUSE_ACTION, SecurityStatus.PAUSED, SecurityStatus.OPEN, SecurityStatus.HALTED);
  }

  public SecurityMutationResult resume(
      UUID actorId, UUID requestId, String marketId, String reason) throws SQLException {
    return changeStatus(actorId, requestId, marketId, reason,
        RESUME_ACTION, SecurityStatus.OPEN, SecurityStatus.PAUSED, SecurityStatus.OPEN);
  }

  public SecurityMutationResult close(
      UUID actorId, UUID requestId, String marketId, UUID recoveryAccount, String reason)
      throws SQLException {
    Objects.requireNonNull(actorId, "actorId");
    Objects.requireNonNull(requestId, "requestId");
    Objects.requireNonNull(recoveryAccount, "recoveryAccount");
    requireText(marketId, "market id");
    String normalizedReason = normalizeReason(reason);
    Instant now = Instant.now();
    String payload = closePayload(marketId, recoveryAccount);
    return repository.inTransaction(tx -> {
      SecurityAuditRecord duplicate = requireNoDuplicate(tx, requestId, CLOSE_ACTION, payload);
      if (duplicate != null) {
        return replayed(tx, marketId, CLOSE_ACTION, duplicate);
      }
      SecurityDefinitionState definition = tx.securityDefinition(marketId);
      if (definition.status().equals(SecurityStatus.CLOSED.name())) {
        throw new IllegalStateException("security is already closed");
      }
      if (!tx.openOrders(marketId).isEmpty()) {
        throw new IllegalStateException("security close requires no open orders");
      }
      MarketState state = tx.marketState(marketId);
      recoverOutstanding(tx, definition, recoveryAccount, requestId, actorId,
          normalizedReason, now);
      tx.updateMarketState(new MarketState(marketId, MarketStatus.CLOSED,
          state.prioritySequence(), state.matchSequence(), state.referencePrice(),
          state.lastPrice(), state.haltedUntil(), state.discoveryQuantity(),
          state.circuitBreakerLevel(), state.version() + 1), state.version());
      SecurityDefinitionState closed = new SecurityDefinitionState(
          definition.marketId(), definition.symbol(), definition.name(),
          definition.description(), definition.currencyId(), definition.basePrice(),
          definition.totalSupply(), definition.issuedSupply(), definition.minimumUnit(),
          SecurityStatus.CLOSED.name(), recoveryAccount, definition.createdAt(), now,
          definition.version());
      tx.updateSecurityDefinition(closed, definition.version());
      appendAudit(tx, actorId, requestId, marketId, CLOSE_ACTION, payload, "SUCCESS", now);
      return new SecurityMutationResult(
          marketId, definition.symbol(), CLOSE_ACTION, SecurityStatus.CLOSED.name(),
          payload, false);
    });
  }

  private SecurityMutationResult changeStatus(
      UUID actorId, UUID requestId, String marketId, String reason, String action,
      SecurityStatus target, SecurityStatus... allowedFrom) throws SQLException {
    Objects.requireNonNull(actorId, "actorId");
    Objects.requireNonNull(requestId, "requestId");
    requireText(marketId, "market id");
    String normalizedReason = normalizeReason(reason);
    Instant now = Instant.now();
    return repository.inTransaction(tx -> {
      SecurityAuditRecord duplicate = requireNoDuplicate(tx, requestId, action, marketId);
      if (duplicate != null) {
        return replayed(tx, marketId, action, duplicate);
      }
      SecurityDefinitionState definition = tx.securityDefinition(marketId);
      requireStatusTransition(definition.status(), target, allowedFrom);
      MarketState state = tx.marketState(marketId);
      if (target == SecurityStatus.PAUSED) {
        tx.updateMarketState(new MarketState(marketId, MarketStatus.PAUSED,
            state.prioritySequence(), state.matchSequence(), state.referencePrice(),
            state.lastPrice(), state.haltedUntil(), state.discoveryQuantity(),
            state.circuitBreakerLevel(), state.version() + 1), state.version());
      } else if (target == SecurityStatus.OPEN
          && (state.status() == MarketStatus.PAUSED || state.status() == MarketStatus.CLOSED)) {
        tx.updateMarketState(new MarketState(marketId, MarketStatus.OPEN,
            state.prioritySequence(), state.matchSequence(), state.referencePrice(),
            state.lastPrice(), state.haltedUntil(), state.discoveryQuantity(),
            state.circuitBreakerLevel(), state.version() + 1), state.version());
      }
      SecurityDefinitionState updated = new SecurityDefinitionState(
          definition.marketId(), definition.symbol(), definition.name(),
          definition.description(), definition.currencyId(), definition.basePrice(),
          definition.totalSupply(), definition.issuedSupply(), definition.minimumUnit(),
          target.name(), definition.recoveryAccount(), definition.createdAt(), now,
          definition.version());
      tx.updateSecurityDefinition(updated, definition.version());
      appendAudit(tx, actorId, requestId, marketId, action, marketId,
          "status=" + target.name() + ";reason=" + normalizedReason, now);
      return new SecurityMutationResult(
          marketId, definition.symbol(), action, target.name(), marketId, false);
    });
  }

  private static void recoverOutstanding(
      ExchangeTransaction tx, SecurityDefinitionState definition, UUID recoveryAccount,
      UUID requestId, UUID actorId, String reason, Instant now) throws SQLException {
    long recovered = 0;
    for (SecurityBalance balance : tx.securityBalances(definition.marketId())) {
      long outstanding = Math.addExact(balance.availableQuantity(), balance.frozenQuantity());
      if (outstanding == 0) {
        continue;
      }
      if (balance.frozenQuantity() > 0) {
        tx.releaseSecurity(balance.accountId(), definition.marketId(), balance.frozenQuantity());
      }
      tx.freezeSecurity(balance.accountId(), definition.marketId(), outstanding);
      tx.consumeFrozenSecurity(balance.accountId(), definition.marketId(), outstanding);
      tx.creditAvailableSecurity(recoveryAccount, definition.marketId(), outstanding);
      tx.appendSecurityLedger(new SecurityLedgerEntry(
          UUID.randomUUID(), "close:" + balance.accountId() + ":" + requestId,
          definition.marketId(), balance.accountId(), "RECOVERY", -outstanding,
          -balance.availableQuantity(), -balance.frozenQuantity(),
          "CLOSE", requestId.toString(), actorId, reason, now));
      recovered = Math.addExact(recovered, outstanding);
    }
    if (recovered > 0) {
      tx.appendSecurityLedger(new SecurityLedgerEntry(
          UUID.randomUUID(), "close:recovery:" + requestId,
          definition.marketId(), recoveryAccount, "RECOVERY", recovered,
          recovered, 0, "CLOSE", requestId.toString(), actorId, reason, now));
    }
  }

  private static void requireTransferable(SecurityDefinitionState definition, long quantity) {
    if (!definition.status().equals(SecurityStatus.OPEN.name())
        && !definition.status().equals(SecurityStatus.PAUSED.name())) {
      throw new IllegalStateException(
          "securities can only be transferred while OPEN or PAUSED: " + definition.status());
    }
    if (quantity % definition.minimumUnit() != 0) {
      throw new IllegalArgumentException(
          "quantity must be a multiple of minimum unit " + definition.minimumUnit());
    }
  }

  private static void requireSourceBalance(SecurityBalance source, long quantity) {
    if (source.availableQuantity() < quantity) {
      throw new IllegalArgumentException(
          "insufficient available balance: " + source.availableQuantity() + " available");
    }
  }

  private static void requireIssuable(SecurityDefinitionState definition, long quantity) {
    if (!definition.status().equals(SecurityStatus.OPEN.name())
        && !definition.status().equals(SecurityStatus.PAUSED.name())) {
      throw new IllegalStateException(
          "securities can only be issued while OPEN or PAUSED: " + definition.status());
    }
    if (quantity % definition.minimumUnit() != 0) {
      throw new IllegalArgumentException(
          "quantity must be a multiple of minimum unit " + definition.minimumUnit());
    }
    long remaining = definition.totalSupply() - definition.issuedSupply();
    if (quantity > remaining) {
      throw new IllegalArgumentException(
          "insufficient unissued supply: " + remaining + " remaining");
    }
  }

  private static void requireStatusTransition(
      String current, SecurityStatus target, SecurityStatus... allowedFrom) {
    for (SecurityStatus allowed : allowedFrom) {
      if (allowed.name().equals(current)) {
        return;
      }
    }
    throw new IllegalStateException(
        "cannot transition security from " + current + " to " + target);
  }

  private static SecurityAuditRecord requireNoDuplicate(
      ExchangeTransaction tx, UUID requestId, String action, String payload)
      throws SQLException {
    SecurityAuditRecord existing = tx.securityAudit(requestId.toString()).orElse(null);
    if (existing == null) {
      return null;
    }
    if (!existing.action().equals(action) || !existing.payload().equals(payload)) {
      throw new IllegalStateException("request id belongs to another operation");
    }
    return existing;
  }

  private static SecurityMutationResult replayed(
      ExchangeTransaction tx, String marketId, String action, SecurityAuditRecord duplicate)
      throws SQLException {
    SecurityDefinitionState definition = tx.existingSecurityDefinition(marketId).orElse(null);
    String symbol = definition == null ? "-" : definition.symbol();
    String status = definition == null ? "-" : definition.status();
    return new SecurityMutationResult(
        marketId, symbol, action, status, duplicate.payload(), true);
  }

  private static void appendAudit(
      ExchangeTransaction tx, UUID actorId, UUID requestId, String marketId, String action,
      String payload, String outcome, Instant at) throws SQLException {
    tx.appendSecurityAudit(new SecurityAuditRecord(
        UUID.randomUUID(), requestId.toString(), marketId, action, actorId, payload,
        outcome, at));
  }

  private static String createPayload(
      String marketId, String symbol, String name, String currencyId, BigDecimal basePrice,
      long totalSupply, long minimumUnit) {
    return "market=" + marketId + ";symbol=" + symbol + ";name=" + name
        + ";currency=" + currencyId + ";basePrice=" + basePrice.toPlainString()
        + ";totalSupply=" + totalSupply + ";minimumUnit=" + minimumUnit;
  }

  private static String issuePayload(String marketId, UUID targetAccount, long quantity) {
    return "market=" + marketId + ";target=" + targetAccount + ";quantity=" + quantity;
  }

  private static String transferPayload(
      String marketId, UUID fromAccount, UUID toAccount, long quantity) {
    return "market=" + marketId + ";from=" + fromAccount + ";to=" + toAccount
        + ";quantity=" + quantity;
  }

  private static String closePayload(String marketId, UUID recoveryAccount) {
    return "market=" + marketId + ";recovery=" + recoveryAccount;
  }

  private static String normalizeReason(String reason) {
    if (reason == null || reason.trim().length() < 8) {
      throw new IllegalArgumentException(
          "administrator reason must contain at least 8 characters");
    }
    return reason.trim();
  }

  private static void requireText(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " is required");
    }
  }

  private static void requirePositive(BigDecimal value, String name) {
    if (value == null || value.signum() <= 0) {
      throw new IllegalArgumentException(name + " must be positive");
    }
  }

  private static void requirePositive(long value, String name) {
    if (value <= 0) {
      throw new IllegalArgumentException(name + " must be positive");
    }
  }

  /**
   * Builds the runtime market definition for a newly created virtual security. The market is
   * created in the CLOSED state; the admin later issues supply and resumes trading.
   */
  public static MarketDefinition buildMarketDefinition(
      String marketId, String symbol, String name, String description, String currencyId,
      BigDecimal basePrice, long totalSupply, long minimumUnit) {
    BigDecimal one = BigDecimal.ONE;
    long discovery = Math.min(totalSupply, Math.max(totalSupply / 100, minimumUnit * 10));
    MarketDefinition.StructuralRules structural = new MarketDefinition.StructuralRules(
        currencyId, basePrice, one, basePrice.multiply(new BigDecimal("1000")),
        one.movePointLeft(2), 2, 2, minimumUnit, totalSupply, discovery);
    MarketDefinition.RiskRules risk = new MarketDefinition.RiskRules(
        new BigDecimal("0.001"), new BigDecimal("0.002"),
        new BigDecimal("0.20"), new BigDecimal("0.05"),
        new BigDecimal("0.20"), new BigDecimal("0.10"), 120,
        new BigDecimal("0.20"), 600, 100_000,
        new BigDecimal("10000000.00"), 100, 5, 60);
    com.ghostchu.quickshop.addon.exchange.config.SecurityDefinition security =
        new com.ghostchu.quickshop.addon.exchange.config.SecurityDefinition(
        symbol, name, description, currencyId, basePrice, totalSupply, minimumUnit);
    return new MarketDefinition(marketId, name, false, null, structural, risk, false,
        AssetType.VIRTUAL_SECURITY, security);
  }
}
