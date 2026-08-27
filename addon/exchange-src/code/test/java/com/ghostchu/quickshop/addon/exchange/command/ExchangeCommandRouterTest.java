package com.ghostchu.quickshop.addon.exchange.command;

import com.ghostchu.quickshop.addon.exchange.core.model.OrderSide;
import com.ghostchu.quickshop.addon.exchange.core.model.OrderType;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ExchangeCommandRouterTest {
  @Test
  void showsHelpWithUsePermissionAndRejectsWithoutIt() {
    Actor allowed = new Actor("quickshop.exchange.use");
    new ExchangeCommandRouter(UUID::randomUUID).execute(allowed, new String[] {"help"});
    assertThat(allowed.message).isEqualTo("command-help");

    Actor denied = new Actor("");
    new ExchangeCommandRouter(UUID::randomUUID).execute(denied, new String[] {"help"});
    assertThat(denied.message).isEqualTo("permission-denied");
  }

  @Test
  void deniesMarketOrderWithoutDedicatedPermission() {
    Actor actor = new Actor("quickshop.exchange.use");
    new ExchangeCommandRouter(UUID::randomUUID).execute(actor,
        new String[] {"order", "market", "buy", "diamond-usd", "5"});
    assertThat(actor.message).isEqualTo("permission-denied");
  }

  @Test
  void requiresAnExplicitSlippageBoundaryForMarketOrders() {
    Actor actor = new Actor("quickshop.exchange.use", "quickshop.exchange.order.market");

    new ExchangeCommandRouter(UUID::randomUUID).execute(actor,
        new String[] {"order", "market", "buy", "diamond-usd", "5"});

    assertThat(actor.message).isEqualTo("command-invalid");
    assertThat(actor.opened).isNull();
  }

  @Test
  void generatesOneRequestIdPerConfirmedAction() {
    Actor actor = new Actor("quickshop.exchange.use", "quickshop.exchange.order.limit");
    UUID request = UUID.randomUUID();
    new ExchangeCommandRouter(() -> request).execute(actor,
        new String[] {"order", "limit", "buy", "diamond-usd", "100.00", "5"});
    assertThat(actor.message).isEqualTo("request-ready:" + request);
  }

  @Test
  void preservesMarketIdInsteadOfOpeningAnArbitraryMenu() {
    Actor actor = new Actor("quickshop.exchange.use");
    new ExchangeCommandRouter(UUID::randomUUID).execute(actor,
        new String[] {"market", "diamond-usd"});
    assertThat(actor.opened).isNotNull();
    assertThat(actor.opened.marketId()).isEqualTo("diamond-usd");
    assertThat(actor.opened.menuName()).isEqualTo("market-detail");
  }

  @Test
  void resolvesStockSymbolToItsMarketId() {
    Actor actor = new Actor("quickshop.exchange.use");
    ExchangeCommandRouter router = new ExchangeCommandRouter(
        UUID::randomUUID, null, RolloutPolicy.DISABLED,
        symbol -> "alpha".equalsIgnoreCase(symbol) ? "concept_alpha" : null);

    router.execute(actor, new String[] {"stock", "ALPHA"});

    assertThat(actor.opened).isNotNull();
    assertThat(actor.opened.marketId()).isEqualTo("concept_alpha");
    assertThat(actor.opened.menuName()).isEqualTo("market-detail");
  }

  @Test
  void rejectsStockSymbolWithNoMatchingMarket() {
    Actor actor = new Actor("quickshop.exchange.use");
    ExchangeCommandRouter router = new ExchangeCommandRouter(
        UUID::randomUUID, null, RolloutPolicy.DISABLED, symbol -> null);

    router.execute(actor, new String[] {"stock", "ALPHA"});

    assertThat(actor.message).isEqualTo("command-invalid");
    assertThat(actor.opened).isNull();
  }

  @Test
  void tabCompletesStockSymbolsFromTheRegistry() {
    ExchangeCommandRouter router = new ExchangeCommandRouter(
        UUID::randomUUID, null, RolloutPolicy.DISABLED, Function.identity(),
        () -> java.util.List.of("ALPHA", "BETA"));

    assertThat(router.tabComplete(new Actor("quickshop.exchange.use"),
        new String[] {"stock", "al"})).containsExactly("ALPHA");
    assertThat(router.tabComplete(new Actor("quickshop.exchange.use"),
        new String[] {"stock", ""})).containsExactlyInAnyOrder("ALPHA", "BETA");
  }

  @Test
  void rejectsUnknownCommandWithoutOpeningAUserControlledMenu() {
    Actor actor = new Actor("quickshop.exchange.use");
    new ExchangeCommandRouter(UUID::randomUUID).execute(actor,
        new String[] {"not-a-menu"});
    assertThat(actor.message).isEqualTo("command-invalid");
    assertThat(actor.opened).isNull();
  }

  @Test
  void parsesLimitOrderIntoAContextWithTheGeneratedRequestId() {
    UUID request = UUID.randomUUID();
    Actor actor = new Actor("quickshop.exchange.use", "quickshop.exchange.order.limit");
    new ExchangeCommandRouter(() -> request).execute(actor,
        new String[] {"order", "limit", "sell", "diamond-usd", "100.00", "5"});
    assertThat(actor.opened.order().requestId()).isEqualTo(request);
    assertThat(actor.opened.order().side()).isEqualTo(OrderSide.SELL);
    assertThat(actor.opened.order().type()).isEqualTo(OrderType.LIMIT);
  }

  @Test
  void parsesMoneyDepositIntoAContext() {
    UUID request = UUID.randomUUID();
    Actor actor = new Actor("quickshop.exchange.use", "quickshop.exchange.deposit");
    new ExchangeCommandRouter(() -> request).execute(actor,
        new String[] {"deposit", "money", "default", "12.50"});
    assertThat(actor.opened.transfer().requestId()).isEqualTo(request);
    assertThat(actor.opened.transfer().kind())
        .isEqualTo(ExchangeMenuRequest.TransferKind.MONEY_DEPOSIT);
    assertThat(actor.opened.transfer().amount()).isEqualByComparingTo("12.50");
  }

  @Test
  void deniesOrderWithDedicatedPermissionButWithoutUsePermission() {
    Actor actor = new Actor("quickshop.exchange.order.limit");

    new ExchangeCommandRouter(UUID::randomUUID).execute(actor,
        new String[] {"order", "limit", "buy", "diamond-usd", "100.00", "5"});

    assertThat(actor.message).isEqualTo("permission-denied");
    assertThat(actor.opened).isNull();
  }

  @Test
  void deniesTransferWithDedicatedPermissionButWithoutUsePermission() {
    Actor actor = new Actor("quickshop.exchange.deposit");

    new ExchangeCommandRouter(UUID::randomUUID).execute(actor,
        new String[] {"deposit", "money", "default", "12.50"});

    assertThat(actor.message).isEqualTo("permission-denied");
    assertThat(actor.opened).isNull();
  }

  @Test
  void deniesEveryPlayerEntryPointOutsideTheRolloutWhitelist() {
    Actor actor = new Actor("quickshop.exchange.use");
    ExchangeCommandRouter router = new ExchangeCommandRouter(
        UUID::randomUUID, null, new RolloutPolicy(true, Set.of(UUID.randomUUID())));

    router.execute(actor, new String[] {"assets"});

    assertThat(actor.message).isEqualTo("rollout-not-allowed");
    assertThat(actor.opened).isNull();
  }

  @Test
  void allowsWhitelistedPlayersAndDoesNotApplyPlayerRolloutToAdministration() {
    Actor player = new Actor("quickshop.exchange.use");
    ExchangeCommandRouter router = new ExchangeCommandRouter(
        UUID::randomUUID, null, new RolloutPolicy(true, Set.of(player.accountId())));
    router.execute(player, new String[] {"assets"});
    assertThat(player.opened.menuName()).isEqualTo("assets");

    Actor administrator = new Actor("quickshop.exchange.admin.audit");
    new ExchangeCommandRouter(UUID::randomUUID, null, new RolloutPolicy(true, Set.of()))
        .execute(administrator, new String[] {"admin"});
    assertThat(administrator.opened.menuName()).isEqualTo("admin");
  }

  @Test
  void requiresAdminReloadPermissionForReloadCommand() {
    Actor denied = new Actor("quickshop.exchange.use");
    new ExchangeCommandRouter(UUID::randomUUID).execute(denied, new String[] {"reload"});
    assertThat(denied.message).isEqualTo("permission-denied");
    assertThat(denied.reloaded).isFalse();

    Actor allowed = new Actor("quickshop.exchange.admin.reload");
    new ExchangeCommandRouter(UUID::randomUUID).execute(allowed, new String[] {"reload"});
    assertThat(allowed.reloaded).isTrue();
  }

  @Test
  void opensAdminPageForAnActorWithAnyAdminPermission() {
    Actor actor = new Actor("quickshop.exchange.admin.audit");

    new ExchangeCommandRouter(UUID::randomUUID).execute(actor, new String[] {"admin"});

    assertThat(actor.opened.menuName()).isEqualTo("admin");
  }

  @Test
  void opensAdminPageForAStockOnlyAdministrator() {
    Actor actor = new Actor("quickshop.exchange.admin.stock");

    new ExchangeCommandRouter(UUID::randomUUID).execute(actor, new String[] {"admin"});

    assertThat(actor.opened.menuName()).isEqualTo("admin");
  }

  @Test
  void deniesAdminPageWithoutAnyAdminPermission() {
    Actor actor = new Actor("quickshop.exchange.use");

    new ExchangeCommandRouter(UUID::randomUUID).execute(actor, new String[] {"admin"});

    assertThat(actor.message).isEqualTo("permission-denied");
    assertThat(actor.opened).isNull();
  }

  @Test
  void tabCompletesAuditSubcommandsUnderAdmin() {
    ExchangeCommandRouter router = new ExchangeCommandRouter(UUID::randomUUID);

    assertThat(router.tabComplete(new Actor("quickshop.exchange.admin.audit"),
        new String[] {"admin", "audit", "a"})).containsExactlyInAnyOrder(
            "status", "ack", "reconcile", "export");
    assertThat(router.tabComplete(new Actor("quickshop.exchange.admin.audit"),
        new String[] {"admin", "audit", ""})).containsExactlyInAnyOrder(
            "status", "ack", "reconcile", "export");
    assertThat(router.tabComplete(new Actor("quickshop.exchange.admin.audit"),
        new String[] {"admin", "audit", "ack", ""})).containsExactly("<alertId>");
  }

  @Test
  void tabCompletesStockActionsAndSymbolsUnderAdmin() {
    ExchangeCommandRouter router = new ExchangeCommandRouter(
        UUID::randomUUID, null, RolloutPolicy.DISABLED, Function.identity(),
        () -> java.util.List.of("ALPHA", "BETA"));
    Actor actor = new Actor("quickshop.exchange.admin.stock");

    assertThat(router.tabComplete(actor, new String[] {"admin", "stock", ""}))
        .containsExactlyInAnyOrder("create", "issue", "transfer", "pause", "resume", "close");
    assertThat(router.tabComplete(actor, new String[] {"admin", "stock", "issue", "al"}))
        .containsExactly("ALPHA");
    assertThat(router.tabComplete(actor, new String[] {"admin", "stock", "pause", ""}))
        .containsExactlyInAnyOrder("ALPHA", "BETA");
  }

  private static final class Actor implements CommandActor {
    private final Set<String> permissions = new HashSet<>();
    private final UUID accountId = UUID.randomUUID();
    private String message;
    private ExchangeMenuRequest opened;
    private boolean reloaded;
    private Actor(String... permission) { permissions.addAll(java.util.Arrays.asList(permission)); }
    public UUID accountId() { return accountId; }
    public boolean hasPermission(String permission) { return permissions.contains(permission); }
    public void message(String key, Object... arguments) {
      message = key + (arguments.length == 0 ? "" : ":" + arguments[0]);
    }
    public void openMenu(String menuName, int page) { }
    public void openMenu(ExchangeMenuRequest request) { opened = request; }
    public void reloadRequested() { reloaded = true; }
  }
}
