# qssuite-exchange

QuickShop-Hikari 交易所附加组件（Exchange Addon）的独立源码镜像。

上游为 [QuickShop-Hikari](https://github.com/alright-qwq/QuickShop-Hikari) 的
`codex/exchange-order-book` 分支。本仓仅收录该附加组件的源码、配置资源与文档，便于单独分发、
审阅和归档；实际构建请在上游主仓对应分支中进行。

## 功能

- 中心限价订单簿（CLOB）：限价单（GTC）与保护市价单（IOC）、价格时间优先撮合、maker/taker 手续费
- 托管与划转：货币（经济插件）与物品（Folia 实体调度器）的存入/取出，可恢复的划转状态机
- 虚拟概念股：纯账本证券，发行/转让/暂停/关闭，与市场状态联动
- 风控：价格笼、滑点保护、两级熔断、账户持仓/挂单/操作频率上限、可疑交易检测
- 审计与运维：全操作审计日志、对账自动暂停、转账人工审核、CSV 导出与保留
- GUI：市场列表/详情（K 线图周期切换）、订单、资产、历史、确认页

## 文档

- [玩家指南](docs/player-guide.md)
- [运营文档](docs/exchange-operations.md)
- [虚拟概念股](docs/virtual-concept-stocks.md)
- [概念股示例配置](docs/concept-stocks.example.yml)

## 目录结构

- `addon/exchange-src/code/main/java` —— 对应上游 `addon/exchange/src/main/java`
- `addon/exchange-src/code/test/java` —— 对应上游 `addon/exchange/src/test/java`
- `addon/exchange-src/code/main/resources` —— `config.yml` / `plugin.yml` / `messages.yml` / `markets.yml`
- `addon/exchange-src/pom.xml` —— 模块构建描述（依赖上游 parent POM 与 QuickShop API 构件）
- `docs/` —— 面向玩家与运营的中文文档

## 构建

本仓是源码镜像，不携带上游 `quickshop-hikari` parent POM 与 `quickshop-api` 等构件，因此
不能脱离上游独立打包。构建步骤：

```bash
git clone --branch codex/exchange-order-book https://github.com/alright-qwq/QuickShop-Hikari.git
cd QuickShop-Hikari
mvn -pl addon/exchange package -Dspotless.check.skip=true
```

产物位于 `addon/exchange/target/Addon-Exchange-*.jar`。

## 同步约定

- 主仓的源码/资源/文档变更后，同步到本仓对应路径并保持提交信息一致。
- 本仓 CI 只做镜像结构校验（必需文件齐全、源码非空），不做编译，因为编译依赖上游构件。

## 许可证

与上游 QuickShop-Hikari 一致：GPLv3 / AGPLv3 双许可，见 [LICENSE](LICENSE)。
