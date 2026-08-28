# 虚拟概念股

> 本文件最初为英文，中文翻译可能存在滞后。

虚拟概念股是纯账本证券：它们没有 Minecraft 物品，没有物品存入或取出，也无法被合成、丢弃或通过交易所库存通道移动。所有余额和变动都保存在交易所数据库中（`exchange_securities`、`exchange_security_balances`、`exchange_security_ledger`、`exchange_security_audit`）。

本文档说明如何配置、运营和游玩虚拟概念股。

## 概念股是什么

概念股是一个 `asset-type` 为 `VIRTUAL_SECURITY` 的已配置市场。它有一个代码（symbol）、显示名称、基础价格、总供应量、最小单位和生命周期状态。玩家在资产页持有余额，像其他交易所市场一样提交买卖订单，并能在市场列表/详情页看到股票的代码、总供应量、资产类型和证券状态。

与实物物品市场的区别：

- 市场配置中没有 `item:` 段；必须有 `security:` 段。
- 证券余额永远不会显示 `quickshop.exchange.deposit`/`withdraw` 或 GUI 存取款操作。概念股无法进出玩家背包。
- 托管适配器（`SecurityAssetCustody`）从不构造 `ItemStack`，也从不调用物品划转服务。订单在同一个数据库事务内通过在账户之间移动证券余额来完成结算。

## 配置

示例禁用股票市场（随 `markets.yml` 一起提供）：

```yaml
  concept_alpha:
    enabled: false
    display-name: Concept Alpha
    security:
      symbol: ALPHA
      name: Alpha Holdings
      description: Pure ledger concept stock
      base-price: '10.00'
      total-supply: 1000
      minimum-unit: 1
    currency: default
    base-price: '10.00'
    min-price: '1.00'
    max-price: '100.00'
    tick-size: '0.01'
    price-scale: 2
    currency-scale: 2
    min-quantity: 1
    max-quantity: 1000
    discovery-quantity: 100
    maker-fee-rate: '0.001'
    taker-fee-rate: '0.002'
    max-account-holding: 100000
    max-frozen-currency: '10000000.00'
    max-open-orders: 100
    block-container-shops: false
```

规则：

- 虚拟证券市场不得定义 `item:` 段。
- `security.base-price`、`security.currency` 必须与市场 `base-price`/`currency` 一致。
- `security.total-supply` 必须是 `security.minimum-unit` 的正整数倍。
- `security.symbol` 只能是大写字母、数字和下划线（最多 16 个字符）。
- `enabled: false` 会让市场以 `CLOSED` 状态创建，证券定义也以 `CLOSED` 状态创建。随附禁用的示例是安全的；在运维人员启用市场并改变证券状态之前，没有任何东西可以交易。

## 生命周期与管理命令

变更证券状态需要 `quickshop.exchange.admin.stock`。每个操作都会用幂等请求 ID 记录审计。

```text
/qse admin stock create <symbol> <name> <currency> <basePrice> <totalSupply> [minimumUnit] [description...]
/qse admin stock issue <marketId|symbol> <playerUUID> <quantity> <reason...>
/qse admin stock transfer <marketId|symbol> <fromUUID> <toUUID> <quantity> <reason...>
/qse admin stock pause <marketId|symbol> <reason...>
/qse admin stock resume <marketId|symbol> <reason...>
/qse admin stock close <marketId|symbol> <recoveryAccountUUID> <reason...>
```

所有生命周期命令都接受市场 ID 或证券代码（不区分大小写，通过与 `/qse stock` 相同的注册表解析）。

生命周期状态值：`OPEN`、`PAUSED`、`HALTED`、`CLOSED`。

- `OPEN`：接受新订单。
- `PAUSED`：仍然可以发行，但由于撮合市场状态在同一事务中被暂停，下单被停止。证券状态和市场状态会自动保持同步。
- `transfer`：把证券余额从一个账户转移到另一个账户（仅限 `OPEN` 或 `PAUSED`，数量必须是最小单位的整数倍），写入审计记录。
- `HALTED`：保留给熔断式停牌（尚未接入自动市场停牌）。
- `CLOSED`：不能再发行；`close` 要求零未成交订单，会把所有未结余额转移到恢复账户，然后把定义标记为关闭。

### 推荐操作顺序

1. 直接执行 `/qse admin stock create`。新证券会立即写入 `exchange_markets`、`exchange_market_state`、`exchange_securities`，并自动把市场条目写回 `markets.yml`（`enabled: false`）。
2. 新市场无需重启即可出现在 `/qse stocks` 列表和 `/qse stock <symbol>` 详情页，但初始为 `CLOSED`，不能下单。
3. 用 `/qse admin stock issue` 发行初始供应量。
4. 用 `/qse admin stock resume <marketId|symbol> ...` 打开交易。恢复证券也会打开因 `stock pause` 而被暂停的市场状态；因其他原因（例如对账）停牌的市场会保持停牌。
5. 确认资产页显示证券余额，市场详情页显示 `Asset: VIRTUAL_SECURITY`、`Symbol: <symbol>`、`Total supply: <total>`。

如果热插失败（例如数据库暂时不可用），`create` 会提示“证券已创建但运行时未挂载”。此时先确认 `markets.yml` 已写入新市场条目（正常会写入）；若已写入，执行 `/qse reload` 即可恢复；若未写入，手动补上该市场条目后再 `/qse reload`，无需重启服务器。

关闭股票前，先撤掉或让所有未成交订单结束，然后：
`/qse admin stock close <marketId> <recoveryAccount> <reason>`。

## 玩家体验

- `/qse help` 打印命令概览，涵盖市场列表/详情、下单、撤单、我的订单、资产、历史、概念股查询和存取款。
- `/qse stocks` 打开市场列表（包含所有虚拟和实物市场）。列表分页（每页 36 个市场），市场较多时有上一页/下一页控件。
- `/qse stock <symbol>` 或点击列表中的市场打开市场详情页。
- 市场列表支持排序（按 24 小时成交额/名义额、24 小时涨跌或最新价；没有成交的市场排在最后而不是报错）和筛选（全部 / 虚拟证券 / 实物物品），通过控制图标操作。虚拟证券使用绿宝石图标，实物物品市场使用箱子图标，非 OPEN 市场使用路障图标；24 小时涨跌用绿/红/黄着色。
- 市场详情图表可在 1 分钟、15 分钟、1 小时和 4 小时 K 线之间切换，并显示 24 小时最高/最低、24 小时名义成交额、24 小时波动率，以及虚拟证券的已发行/总供应量比例。每个 K 线图标还显示变动额和变动百分比，每个深度行显示该档位的价值（价格 × 数量）。
- 资产页（`/qse assets`）把每只证券显示为绿宝石图标，并带有明确文字“虚拟证券（仅账本，不可存取）”，以及代码、可用和冻结数量、估算市值和总持仓价值。点击证券行会打开其市场详情页。证券行没有左右两侧的存取款操作。
- 资产页用三个固定区块显示货币/物品余额、虚拟证券和最近划转。划转分页（每页 12 条），带上一页/下一页控件和页码指示；如果货币资产或证券数量超出页面，最后一行显示“+N more”提示，指向历史页或市场列表。
- 资产页和我的订单页在任何市场发生成交时自动刷新，因此菜单打开时余额、持仓和剩余订单数量保持最新。
- 订单确认页显示当前最优报价，告诉你限价单会立即成交还是挂在簿上，显示适用的费率、预估手续费，以及买单提交时会冻结多少货币（包含最坏情况手续费，与实际预留一致）。卖出确认还会显示手续费后的预估净收入。页面还列出市场的数量区间、价格区间和 tick size，提交前可以核对。
- 撤单确认页显示市场、方向、剩余数量，以及撤单时将要释放的精确冻结货币/数量。如果订单已不可撤（已成交或已撤），页面会直接说明而不是等待。
- 确认提交成功后，你会回到对应页面：下单回到市场详情、撤单回到我的订单、存取款回到资产页。失败/被拒绝的请求停留在确认页。
- 市场详情页显示“可执行深度”摘要：整个订单簿当前可执行（价格笼内）的买一和卖一总量，一眼就能看到有多少流动性立即可用。
- 市场详情页还显示你在该市场的可用和冻结余额：实物物品市场是货币，虚拟股票是证券持仓（物品市场还有物品持仓），下单前就知道自己买得起什么。
- 市场详情页还会估算你按最新价能买多少（可用货币 ÷ 价格，再计入最坏情况费率，向下取整到最小数量）和能卖多少（你的可用持仓），不用切换页面就能确定订单规模。
- 市场详情页显示你当前在该市场的挂单数量（例如 `3 / 100`），避免不小心超出每市场挂单上限。
- 虚拟证券市场行和详情页显示流通市值（已发行供应量 × 最新价），方便按体量比较概念股。
- 虚拟证券市场的下单按钮会显示提醒：证券以账本余额结算，不是 Minecraft 物品。
- 账户历史页除双方合计手续费外，还显示“我的手续费”——每笔成交实际向你收取的精确 maker 或 taker 手续费。
- 市场列表/详情行在正常的价格/成交量/状态 lore 之外，显示 `Asset: VIRTUAL_SECURITY`、`Symbol`、`Total supply` 和 `Security status`（从证券定义实时读取）。
- `/qse stock <symbol>` 把代码解析为市场 ID（不区分大小写）并打开该市场的详情页；未知代码会被拒绝，而不是打开一个坏掉的页面。
- 当市场状态不是 `OPEN` 时，下单按钮仍然可见，但点击会返回“该市场当前仅接受查询和撤单”。同一防护适用于暂停/关闭的虚拟市场。

## 运营审计状态

持有 `quickshop.exchange.admin.audit` 时：

```text
/qse admin audit status
```

打印紧凑的运行健康视图：每个市场的撮合延迟百分位和队列长度、最近的可疑交易告警（同一两个账户之间的对倒式高频交易，或异常高的撤/挂单比例），以及有多少划转等待审核。告警写入交易所审计告警表，从不修改账户、余额或订单。要把已审核的告警标记为已确认：

```text
/qse admin audit ack <alertId>
```

状态输出包含每个告警的 ID，运维人员可以直接确认发现。

## 对账与恢复

对账包含虚拟证券：已发行供应量视为托管，玩家证券余额（可用 + 冻结）视为负债。因此，被篡改或丢失的证券余额会以该市场的 `custodyDifferences` 条目浮现，并触发与其他交易所资产相同的自动暂停/告警保护。

启动时的 fail-closed 检查验证每个已配置市场存在于数据库、数据库资产类型与配置一致，且每个虚拟市场都有证券定义。不匹配会停止启动，而不是静默出错。

证券余额和账本操作是追加式且幂等的。发行、冻结、释放、消耗和恢复会在同一事务中同时写入余额表和不可变的证券账本行，因此重启重放不会重复计数。

## 权限汇总

- 玩家：`quickshop.exchange.use`、`quickshop.exchange.order.market`、`quickshop.exchange.order.limit`、`quickshop.exchange.order.cancel`、`quickshop.exchange.deposit`、`quickshop.exchange.withdraw`
- 股票管理：`quickshop.exchange.admin.stock`
- 对账/审计：`quickshop.exchange.admin.audit`
- 市场暂停/恢复：`quickshop.exchange.admin.market`
