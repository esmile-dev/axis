---
status: draft          # draft → approved → verified
feature: knowledge-create-patterns
created: 2026-08-17
---

# 精简规格：KnowledgeService 多 create 通道——是否引入工厂+策略模式（XS 级·设计决策记录）

## 1. 问题

`KnowledgeService` 现有 4 个 create 入口（L67-L137），问：能否/应否用**工厂模式 + 策略模式**收敛？

## 2. 现状分析

4 个入口实为 **3 个真实创建通道 + 1 个编排器**：

| 入口 | 通道 | 内容来源 | 附加字段 | 特有依赖 |
|------|------|----------|----------|----------|
| `create(req)` | 手动录入 | DTO 直给 | — | — |
| `createFromUrl(url)` | URL 抓取 | fetch → extract | sourceUrl | WebPageFetcher / ArticleExtractor |
| `createFromImport(file,…)` | 文件导入 | parse → store | filePath | ImportedFileParser / KnowledgeFileStorage |
| `createFromInbox(id)` | **编排器**：路由到上面三者，再标 inbox 已读 | — | — | InboxItemRepository / InboxService |

真正的重复只有尾部 3 行（×3 处）：

```java
KnowledgeItem saved = itemRepository.saveAndFlush(item);
eventPublisher.publishEvent(new KnowledgeItemCreatedEvent(saved.getId()));
return KnowledgeItemDetailView.from(saved, List.of());
```

## 3. 方案对比

| 方案 | 做法 | 成本 | 收益 |
|------|------|------|------|
| A. 工厂+策略 | `KnowledgeItemCreator` 接口（`supports()` + `create()`）+ 统一 Command 信封 + Spring `List<Creator>` 注入分发 | +1 接口、+1 enum、+1 Command、3-4 实现类 ≈ 100+ 行；controller 跟着包信封；编排器游离体系外（或策略互调）；事务边界从显式注解变隐式 | 消掉 9 行尾部重复；新通道"只加一个类" |
| B. 私有方法收敛 | 抽 `persistAndPublish(item)` 私有方法 | +1 方法约 5 行 | 消掉同样的 9 行重复 |
| C. 维持现状 | 不动 | 0 | 保留显式直白：controller 一一对应、事务注解可见、调试栈浅 |

方案 A 的硬伤口在**入参异质**：`String` / DTO / `MultipartFile` 无法自然统一成一个 Command——硬统一只是把复杂度从 service 挪到 controller 和信封类型上。且 `createFromInbox` 是编排器不是平级通道，塞不进同一接口。

## 4. 决策

**选 C（维持现状），不引入工厂+策略模式。** 如想收敛尾部重复，做 B 即可（可选，非必须）。

理由：

1. 模式的价值在"消除重复"和"开放扩展"——但重复只有 9 行（B 就能解决），扩展需求不存在（roadmap 无第 5 个创建通道）。
2. 违反 Simplicity First：100+ 行间接层换 9 行去重，调试栈变深，`@Transactional` 语义变隐式，收益与成本严重不匹配。
3. 项目阶段允许直接重构，但"可以改"不等于"值得改"。

**重新评估触发条件**（满足任一条再回看本决策）：

- 创建通道 ≥ 6 个（浏览器插件 / 邮件 / RSS 直采 / 分享面板等陆续出现）
- 通道需要运行时注册（插件化）
- 创建尾部公共逻辑膨胀且各通道有变体（如统一审核/打标管线）

## 5. 总结

设计模式的价值取决于它消除的复杂度是否大于它引入的间接层。当前 3 通道 + 1 编排器、9 行重复，工厂+策略的间接层成本远超收益。记录此决策避免未来重复讨论；触发条件满足时再议。
