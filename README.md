# mixu-spoiler

知识边界过滤器（anti-spoiler firewall）：决定「此刻能不能给读者看」。

它不是提示词，也不是模型行为约束。它是一个**确定性函数**：给定读者已解锁的
`knowledge_horizon`，要么放行，要么拒绝，没有中间态。

Apache-2.0。开源它是为了让外部能审查这条线到底怎么画的——只要有一处过滤写错，
整套产品叙事就没了。

## 为什么单独一个库

- 过滤要同时作用于：图投影、全文搜索、自动补全、证据预览、节点计数、Copilot 检索上下文。
  散落在各模块里就一定会漏。
- 写错了是**静默**错误：界面看起来正常，只是提前剧透了。
- 它值得被别家复用：任何有"剧情推进"概念的阅读器、视觉小说、剧情游戏都需要同一套东西。

## 策略对象（示意）

```kotlin
data class Horizon(val ordinal: RevealOrdinal, val source: HorizonSource)

sealed interface Decision {
    data class Allow<T>(val value: T) : Decision
    data class Deny(val reason: DenyReason) : Decision   // reason 不含未来内容
}

interface SpoilerFilter {
    fun <T : RevealScoped> T.gate(horizon: Horizon): Decision
    fun filterLabels(names: Sequence<RevealedName>, horizon: Horizon): Sequence<RevealedName>
    fun filterHits(hits: Sequence<SearchHit>, horizon: Horizon): Sequence<SearchHit>
    fun filterContext(chunks: Sequence<RetrievalChunk>, horizon: Horizon): Sequence<RetrievalChunk>
}
```

返回值只有两种：允许的**已裁剪**对象，或拒绝。不允许"返回全量、让调用方自己隐藏"。

## 三条硬规则

1. **fail-closed。** 边界缺失、ordinal 无法解析、edition 不匹配 → 拒绝，而不是放行。
2. **拒绝理由不能泄漏。** `Deny` 的 reason 只写内部代码（如 `BEYOND_HORIZON`），
   不回传实体名、章节名或任何能被反推的信息。
3. **同一策略对象。** 图、搜索、检索上下文共用一份过滤实现与一份测试矩阵。

## 不解决什么（必须写明）

- **模型先验知识。** 如果模型训练时读过这本名著，把上下文裁到第 3 章也挡不住它"知道"
  结局。这是产品级风险（R-01），靠对抗评估 + "根据你已读的内容我无法确认"的保守回退来处理，
  不能宣称已被这个库解决。
- **恶意客户端。** 客户端过滤可以被打补丁绕过；服务端在返回任何检索结果或图数据前必须
  **独立复核** horizon，客户端声明的 ordinal 只是声明。
- **版权与授权。** 能不能给，是权利问题；这个库只管"读者读没读到"。

## 质量门禁

```sh
just check
```

测试以 [mixu-fixtures](https://github.com/mixu-app/mixu-fixtures) 的 horizon 标注为准：
每个 `forbidden` 里的标识符，在任何一条代码路径上都不得出现。矩阵见
[`docs/design.md`](docs/design.md)。

## 许可证

[Apache-2.0](LICENSE)，含专利授权。名称与 Logo 不在授权范围内。
