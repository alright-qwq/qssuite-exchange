# 交易所运营文档

> 本文件最初为英文，中文翻译可能存在滞后。

## 灰度发布（Rollout）

1. 安装新构建之前，先备份 QuickShop 数据库和 `plugins/qssuite-exchange`。
2. 先用 `enabled: false` 启动一次，确认 `config.yml` 和 `markets.yml` 已生成；该模式不会构建交易所运行时，也不会接受订单。
3. 在测试服务器上启用一种货币和一小批常用物品，并在 Paper 和 Folia 上分别验证充值、取现、一笔撮合成交、一次重启和一次撤单。
4. 在生产服务器上启用玩家白名单，并设置保守的持仓上限、冻结货币上限和挂单上限。在提高上限之前，先观察一个完整的经济循环。
5. 每天对托管（custody）进行对账。在重新打开被暂停的市场之前，调查 `REVIEW_REQUIRED` 转账、熔断停牌、写入锁失败、SQL 延迟，以及任何托管差异。
6. 按市场逐步扩大白名单和上限。启用 `block-container-shops` 会阻止该市场的物品今后再创建容器商店，但不会迁移或取消已有商店。

## 配置热重载

修改 `config.yml` 或 `markets.yml` 后，执行 `/qse reload`（需要 `quickshop.exchange.admin.reload`），无需重启服务器。重载会热应用：

- `market-data.gui-refresh-ms`、`market-data.candle-retention-days`、`operations.reconciliation-interval-minutes`、`operations.audit-export-retention-days`
- 各市场的风险参数（价格笼、滑点、停牌阈值、持仓/挂单上限、操作频率）与 maker/taker 费率
- 市场显示名与虚拟证券元数据（新名称立即反映到已打开的界面）

费率/风险变更通过重载落库为新的版本，重启后依然生效。重载会校验整个配置；如果某个市场的结构性字段（币种、价格区间、tick、数量档、资产类型等）发生变化，会明确列出是哪个市场、哪个字段，并提示先暂停该市场、取消未平仓订单再重试。配置文件本身非法时，会提示修正文件后重试，上一份设置仍然生效。不要在未执行重载的情况下直接改 `markets.yml` 的费率后重启：数据库中的活跃费率与文件不一致会导致启动安全失败。

`enabled: false` 只影响市场/证券的初始创建状态；运行时启用或停用市场请使用 `/qse admin market pause|resume`，不要依赖修改 `enabled` 后重载。

## 数据库与恢复

`database.mode: quickshop` 需要共享的 MySQL 数据库。插件持有一把专用 MySQL 咨询写入锁，名为 `<dbPrefix>exchange_writer`；第二个匹配器实例必须启动失败。锁断开时立即施加本地写入围栏（write fence）。由于此时所有权已不可信，旧实例不再执行任何数据库变更，包括尝试把市场标记为 `RECOVERING`。持久化的恢复状态只能由之后合法取得写入所有权的启动流程建立；不要让被围栏隔离的实例自动重试或重新夺锁。

`database.mode: sqlite` 仅适用于插件数据目录内的普通文件。插件持有该文件旁边的操作系统文件锁。不要把这个数据库放在共享网络文件系统上。

非正常关闭后，保留未成交订单。重启时运行时先获取写入锁，然后在写入围栏内依次执行迁移、市场注册、订单簿恢复和资金划转恢复，之后才接受写入。玩家登录时的物品划转恢复也通过运行时写入围栏提交。数据库故障或运维干预应让受影响的市场保持 `PAUSED` 或 `RECOVERING`；绝不要手工编辑订单、成交、账本或划转表。

## 玩家下单

`/qse` 和 `/quickshop exchange` 打开的是同一个交易所菜单。玩家必须在灰度白名单内，并持有 `quickshop.exchange.use` 以及所选操作的对应权限。市场只在 `OPEN` 状态接受新订单。

市场详情页以以下格式开始聊天输入：

- 限价单：`<数量> <价格>`；订单为 `GTC`。
- 保护市价单：`<数量> <绝对保护价>`；订单为 `IOC`。

市价单的第二个值是绝对的最差可接受价格，不是百分比。它在确认过程中保持不变，因此延迟确认不会扩大玩家的保护范围。无效输入会让聊天提示保持激活。确认页会重新检查玩家身份、灰度白名单成员资格、`quickshop.exchange.use` 和操作专属权限，且一个请求只能被认领一次。因此，把玩家移出白名单会使已打开的确认界面失效。

## 受审计的管理操作

支持的带权限命令：

```text
/qse admin order cancel <orderId> <reason>
/qse admin market pause <marketId> <reason>
/qse admin market resume <marketId> <reason>
/qse admin audit status
/qse admin audit ack <alertId>
/qse admin audit reconcile
/qse admin audit export <from> <to>
/qse admin transfer review list
/qse admin transfer review show <transferId>
/qse admin transfer review cleanup <transferId>
/qse admin transfer review resolve <transferId> <success|failure> <evidence>
```

审计导出的时间接受 epoch 秒或 ISO-8601 时刻。配置的导出目录必须是相对于插件数据目录的路径。每次导出后会自动清理超过 `operations.audit-export-retention-days`（默认 90 天，0 表示永不清理）的本插件旧导出文件，只匹配插件自己生成的 `audit-*.csv`，不会触碰目录里的其他文件。变更型管理操作和对账与玩家结算一样，都运行在同一把写入围栏之后。

`/qse admin audit status` 显示每个市场的队列长度与 p50/p95 撮合延迟、最近 20 条告警、待审核转账数，以及**全部**未确认告警数（`open-alerts`，不受最近 20 条窗口限制）。有未确认告警时 `open-alerts` 会带红色 ⚠ 前缀。`/qse admin audit ack <alertId>` 确认单条告警（幂等，写入审计记录）。

对账差异会在对账事务中立即保护受影响的市场。物品/市场差异会暂停该市场；货币差异会暂停所有使用该货币的已配置市场。储备不足的订单或无法安全映射的差异会暂停所有已配置市场。每个受影响的市场都会收到 HIGH 级别 `RECONCILIATION_DIFFERENCE` 告警；`OPEN` 或 `HALTED` 市场会通过 CAS 转换到 `PAUSED`，并写入一条追加式 `RECONCILIATION_AUTO_PAUSE` 审计记录。在托管、账本和储备证据被查清、且随后一次对账平衡之前，不要恢复市场。

## 应急处理

强制撤单、市场状态变更、对账和转账审核请走受审计的管理路径。每次 `REVIEW_REQUIRED` 决议都要记录外部经济或库存证据。调查一笔转账时不要重复执行外部操作：持久化的划转记录才是事实来源。当持久化库存标记可能仍需要清理时，物品充值失败和物品取现成功都不能定稿。

物品类审核在定稿前需要先清理玩家背包中的持久化标记：执行 `/qse admin transfer review cleanup <transferId>`（要求玩家在线，操作幂等且写入审计记录），清理成功后再用 `resolve` 定稿。

应急关停时，先停止接受新的交易所请求，让 GUI 提交器、登录恢复围栏执行器、划转恢复执行器和玩家托管执行器在写入所有权释放前排空。最后一根 K 线的落盘严格在写入围栏内执行。如果任何排空或最终落盘失败，应将关停视为失败并保留写入锁。在 Folia 上，Exchange 库存通过每个玩家的实体调度器关闭；如果平台拒绝关停任务，插件不会回退到跨线程访问库存。备份时要把数据库和插件数据目录放在一起，保证 SQLite 锁和配置状态可审计。

## 人工验收

在 Paper 和 Folia 上，用两个白名单测试账号充值货币和物品，提交交叉限价单和保护市价单，验证 maker 价成交、IOC 剩余部分自动撤单、绝对保护边界和手续费、带部分成交订单重启、撤单，以及用可用库存和满载库存两种方式取现。验证全部五个玩家视图：市场、订单、资产、成交/划转/账本历史，以及确认反馈。

Folia 验证必须包括不同区域（region）的玩家、登录恢复、托管操作进行中禁用插件，以及区域线程所有权错误检查。分别用 SQLite 和 MySQL 各跑一轮完整流程。MySQL 要启动第二个插件实例并验证咨询锁启动失败；SQLite 要验证相邻文件锁并拒绝网络共享部署。

在大范围灰度之前，运行 `/qse admin audit reconcile`，验证注入的测试差异会暂停预期市场并产生 HIGH 告警，导出审计范围，并确认不存在负余额、重复外部操作、未解决的托管差异或 `REVIEW_REQUIRED` 转账。

## 虚拟概念股

配置、生命周期和玩家操作参见 [docs/virtual-concept-stocks.md](virtual-concept-stocks.md)。

需要记住的运营差异：

- 虚拟证券纯账本运作。它们从不构造物品模板，也从不走物品存取路径。如果日志行在物品划转语境中提到虚拟市场，请视为 bug。
- 对账把已发行供应量视为托管，把玩家证券余额视为负债。虚拟市场出现托管差异时，会像其他资产差异一样暂停该市场。
- 启动时验证配置的资产类型与数据库一致，且虚拟市场拥有其证券定义。修复配置或数据库状态后重启；不要手工编辑 securities、security balances 或 security ledger 表。
- `/qse stocks` 打开市场列表；`/qse stock <symbol>` 打开该市场的详情页。
- `stock pause/resume/close` 让证券定义与市场状态保持同步：暂停会停止下单，恢复会重新打开被暂停的市场，关闭会关闭市场。
- `/qse` 和管理员股票命令支持证券代码 tab 补全，股票生命周期管理命令接受市场 ID 或代码。
- 市场列表可按资产类型排序和筛选，市场详情图表可切换时间周期（1m/15m/1h/4h）。资产页显示估算价值，并把证券链接到其市场详情页。
