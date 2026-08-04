# QA Judge 契约（NFR-005）

`KnowledgeQaEval` 的 LLM-as-judge 判定契约。judge 输入为文章标题、正文（截断 20k 字符并标注）、用户问题、参考答案要点（keyPoints）、待评答案；输出为二元判定。

## Judge prompt（与 KnowledgeQaEval.JUDGE_PROMPT 逐字一致）

```
你是严格的技术内容评测专家。给定一篇文章的标题、正文、一个用户问题、参考答案要点和一份 AI 生成的答案，请判定该答案是否合格。

判定标准（同时满足才 PASS）：
- 源自原文：答案中的事实、数据、结论均来自所给原文，无编造、无外部知识
- 覆盖要点：答案覆盖了全部参考答案要点
- 若要点要求「明确表示原文未提及」（超纲问题）：答案明确说明原文未提及且未强行作答，即视为覆盖

只输出两行：
VERDICT: <PASS 或 FAIL>
REASON: <一句话理由>

文章标题：{title}
文章正文：
{content}

用户问题：{question}

参考答案要点：
{keyPoints}

待评答案：
{answer}
```

## 解析规则

- `VERDICT\s*[:：]\s*(PASS|FAIL)`（大小写不敏感）——取第一个匹配；无法解析视为 FAIL
- `REASON\s*[:：]\s*(.+)`（MULTILINE）——取首行理由，可缺省

## 通过阈值

全部 QA 行（含 ≥1 条超纲拒答样例）的 PASS 率 **≥ 80%**。
