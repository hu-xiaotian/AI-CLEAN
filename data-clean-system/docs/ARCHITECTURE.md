# AI Clean 数据清洗系统 · 整体架构方案


---

## 1. 系统概览

系统实现目标：将杂乱的 Excel 源数据导入后，通过**全描述解析 → 分类匹配 → 数据清洗 → 质量评分 → 人工审核 → 导出**的标准流水线，把原始数据映射为符合 `主数据` 标准分类体系的干净数据。

系统在**不依赖大模型也能运行**的前提下，提供了可插拔的 AI 能力：
- **AI 属性提取**：从自由文本描述中按标准字段抽取结构化属性；
- **AI 识别分类 / 辅助分类检测**：用大模型把系统分类与标准库比对，给出评分、一致性判定与建议编码；
- **AI 对话**：看板分析对话 + 基于标准分类库的 RAG-lite 问答。

---

## 2. 技术栈

| 层 | 技术 |
| --- | --- |
| 后端框架 | Spring Boot 2.7.18、Spring MVC、Spring AOP、Spring Cache、Spring WebSocket |
| 持久层 | MyBatis Plus 3.5.5（MySQL 8 / 达梦 8 双数据源） |
| 工具 | Apache POI 5.2.5（Excel）、Hutool、FastJSON 2 |
| AI 接入 | 通用 `RestTemplate` 调用 OpenAI / DeepSeek / 通义千问兼容的 Chat Completions 接口 |
| 前端 | 原生 HTML/JS（`static/` 下 `index.html` + `app.js`） |
| 文档/鉴权 | springdoc-openapi 3.0、JWT（jjwt） |

---

## 3. 整体分层架构

```
┌──────────────────────────────────────────────────────────────┐
│                    前端 (static/index.html + app.js)          │
│   导入 · 全描述解析 · 智能分类 · AI提取 · 审核 · 导出 · 看板对话    │
└───────────────┬──────────────────────────────────────────────┘
                │  HTTP / WebSocket (/topic/...)
┌───────────────▼──────────────────────────────────────────────┐
│  Controller 层                                               │
│  DataImportController / DataCleaningController /             │
│  AiChatController / CategoryController / ExportController ...│
└───────────────┬──────────────────────────────────────────────┘
                │
┌───────────────▼──────────────────────────────────────────────┐
│  Service 层 (业务编排)                                         │
│  DataCleaningServiceImpl（清洗主流程 + AI识别/辅助识别）          │
│  CategoryStandardLibrary（标准库内存索引 + 候选召回 + 规则校验）   │
│  CategoryAiService（标准分类 RAG-lite 问答）                    │
│  CategoryService / ExportService / ReviewService ...         │
└───────┬───────────────────┬───────────────────┬──────────────┘
        │                   │                   │
┌───────▼──────┐   ┌─────────▼─────────┐   ┌─────▼──────────┐
│ match 包     │   │ ai 包              │   │ mapper 层       │
│ 分类匹配算法   │   │ AiClientService    │  │ (MyBatis Plus)  │
│ (与业务解耦)   │   │ 通用大模型客户端    │   │                 │
└──────────────┘   └───────────────────┘   └─────────────────┘
                │
        ┌───────▼────────┐
        │ 标准分类库表      │  main_data_category / category_synonym
        │ 业务数据表        │  temp_data / cleaned_data / extra_data ...
        └────────────────┘
```

**关键设计原则**：分类匹配算法（`match` 包）与具体业务（temp_data / extra_data）完全解耦，只消费 `CategoryMatchContext` 这种纯数据上下文。要替换或远程化算法，只需提供一个新的 `CategoryMatcher` 实现并声明为 Spring Bean 即可，无需改动业务代码。

---

## 4. 数据清洗主流程

```
Excel 上传
   │ importExcel (DataImportController → service)
   ▼
temp_data / temp_data_title（原始数据 + 表头映射）
   │
   ├─ 全描述解析：ParseRule.parse(全描述列) → 键值对（物资名称/规格/牌号…）
   │
   ├─ AI 属性提取（可选）：startAiExtract → 按分类编码找标准字段 → 大模型拆分 → extra_data
   │
   ├─ 分类匹配：matchCategory → HierarchicalCategoryMatcher.match(ctx)
   │     产出：命中的三级 CategoryEntity + 来源(source) + 置信度(confidence)
   │
   ├─ 字段填充：materialCode/materialName/specification/... 写入 CleanedDataEntity
   │
   ├─ 质量评分：computeQualityScore（AI 模式 / 规则模式二选一）
   │
   ├─ 状态判定：qualityScore < 60 → NEEDS_REVIEW；≥80 → EXPORT_READY；否则 APPROVED
   │     （未命中三级分类一律置 NEEDS_REVIEW，进入无效数据页）
   │
   └─ 审核任务：评分过低自动建 ReviewTask
        │
        ▼
   cleaned_data（清洗结果）→ 人工审核 → 按分类导出（Excel/CSV/JSON/PDF）
```

清洗任务（`startCleaning`）与 AI 辅助分类检测（`aiClassifyCheckAsync`）均通过 `@Async` + `TransactionTemplate` 异步执行，并通过 **WebSocket**（`/topic/cleaning/{titleId}`、`/topic/ai-classify-check/{titleId}`、`/topic/ai-extract/{titleId}`）实时推送进度，避免前端阻塞。

---

## 5. 分类算法（规则 / 匹配核心）

分类算法的目标是：把原始数据中的「分类名称 + 分类编码 + 附加属性」映射到 `main_data_category` 的**三级节点**。最终必须落到三级节点；未命中三级时不赋一/二级编码，交由无效数据页统计。

### 5.1 标准分类库内存索引 —— `CategoryStandardLibrary`

将整张 `main_data_category`（含旧编码 1~5、旧名称 1~5、同义词表 `category_synonym`）在启动（`@PostConstruct`）时全量加载进内存，建立多套哈希索引：

| 索引 | 结构 | 用途 |
| --- | --- | --- |
| `codeIndex` | 归一化编码 → 标准分类 | 编码精确/前缀匹配（含旧编码） |
| `nameIndex` | 归一化名称 → 标准分类列表 | 名称精确匹配（含旧名称、同义词） |
| `tokenIndex` | 分词 → 标准分类列表 | 模糊/分词重叠匹配 |

- **归一化**：名称转小写、去空格与括号标点；编码去空格/连字符/下划线/斜杠，用于容错比较。
- **分词**：按 `[^a-zA-Z0-9\u4e00-\u9fa5]+` 切分（中文按字符级处理）。
- 提供 `getAncestors`（祖先链）、`getSubtree`（子树）、`getByLevel`、`searchByKeyword` 等检索能力，以及 `reload()` 供标准库更新后热重载。

> 整表**不会**逐条丢给大模型；AI 模式只把「召回的候选子集」发给模型，以控制 token 与成本。

### 5.2 分层分类匹配器 —— `HierarchicalCategoryMatcher`

匹配策略（满足「先定位一/二级大类，再在三级上匹配，最终必须落到三级节点」）：

1. **同义词优先（SYNONYM，置信度 0.95）**：人工维护的别名表命中即返回，置信度最高。
2. **定位一/二级祖先（大类）**：仅作为「加分」信号，而**不再硬局限**三级候选集（避免错误祖先把搜索锁死在错误子树）。
   - `scoreAncestor`：名称全词=1.0 / 包含=0.8 / 语义≥0.7 → 0.6+0.3·sim；编码精确=1.0 / 前缀=0.7。
   - 命中阈值 `ANCESTOR_MIN_SCORE = 0.7`。
3. **全局三级打分（bestMatchL3）**：在**全部**三级节点上统一多策略打分，一次定胜负：
   - **文本信号（优先）**：名称全词 `NAME_EXACT=1.0` → 模糊 `NAME_FUZZY=0.85` → 语义 `SEMANTIC=0.6+0.3·sim`；额外属性值 `EXTRA_NAME`（0.8~0.9）。
   - **编码信号（兜底）**：人工分类编码可能错误，仅在文本缺失时使用 `CODE_EXACT=1.0` / `CODE_PREFIX=0.6`。
   - **名称优先带（NAME_PRIORITY=0.5）**：文本命中的排序分整体高于编码命中，确保「名称与编码冲突时按名称匹配」。
   - **祖先加分（ANCESTOR_BOOST_FACTOR=0.2）**：命中祖先子树的三级在全局比较中占优，但不改变来源(source)、也不排除候选。
4. **阈值判定**：全局最高分 `< MIN_CONFIDENCE(0.5)` → 返回 `category=null`（来源 `UNMATCHED`）。

匹配产物 `CategoryMatchOutcome`：

| 字段 | 说明 |
| --- | --- |
| `category` | 命中的三级节点；未命中为 `null` |
| `source` | `SYNONYM / NAME_EXACT / NAME_FUZZY / CODE_EXACT / CODE_PREFIX / EXTRA_NAME / SEMANTIC / UNMATCHED` |
| `confidence` | 0~1 置信度 |

### 5.3 相似度策略 —— `SimilarityStrategy` / `DefaultSimilarityStrategy`

默认实现为**字符二元组（bigram）Jaccard 相似度**：

```
sim(a,b) = |bigrams(a) ∩ bigrams(b)| / |bigrams(a) ∪ bigrams(b)|
```

作为「语义相似度」的轻量占位实现，无需外部依赖；后续可替换为词向量/语义模型——只要实现 `SimilarityStrategy` 接口并声明为 Spring Bean，即被 `HierarchicalCategoryMatcher` 自动使用。

### 5.4 候选召回 —— `retrieveCandidates`

从全表候选池中，按物料的「分类编码/物料编码/分类名称/物料名称/规格/牌号」对标准库做哈希索引打分（`scoreCode` / `scoreText`），权重不同（编码 1.0、分类名称 0.9、物料名称 0.85、规格 0.6、牌号 0.5），取 top-K（默认 10）。复杂度 O(文本段数)，可安全用于批量，是 AI 识别分类的「检索前置」步骤。

### 5.5 规则校验评分 —— `ruleCheck`（确定性，无需 AI）

把系统分类与标准库确定性比对，给出 0~100 评分与一致性判定：

| 维度 | 权重 | 说明 |
| --- | --- | --- |
| 编码合法性 | 35 | 系统编码在标准库 `codeIndex` 中存在 |
| 名称一致性 | 30 | 系统/物料名称与标准名称相等、包含或分词重叠 |
| 层级合法 | 15/8/3 | 命中三级 > 二级 > 一级 |
| 单位一致 | 10 | 双方单位相等（一方为空不矛盾） |
| 描述关键词 | 10 | 物料字段命中标准分类 `description` |

`consistent = 编码一致 && 名称一致`。该结果是 AI 不可用时的质量评分来源，也是 AI 失败时的回退方案。

---

## 6. AI 识别分类（AI 辅助分类检测）

AI 识别分类用于在系统已有分类结果后，借助大模型**独立复核**分类是否正确，并给出评分、一致性判定与建议编码。核心入口：`aiClassifyCheck` / `aiClassifyCheckAsync`（文件级批量）、`classifyText`（单段文本识别）、`detectSingle`（单条检测）。

### 6.1 检测流程 `aiDetect`

对每条清洗数据：

1. **候选召回**：`stdLib.retrieveCandidates(cd, topK)` 取标准库候选子集（含编码/名称/路径/单位/说明）。
2. **构造提示词**：`classification-detect-prompt` 注入物料信息、系统分类、候选列表，并要求只返回 JSON：
   ```json
   {"score": <0-100>, "matched": <bool>, "bestMatchCode": "<最合理标准编码>", "reason": "<说明>"}
   ```
3. **反向校验提示（关键防幻觉机制）**：
   - 若系统编码**不在**标准库 → 明确告知「系统分类必定有误，请从候选选出正确编码」，强制 `matched=false`；
   - 若系统编码**在**标准库 → 仍须依据物料内容独立判断，不得仅因存在就认定正确。
4. **调用大模型**：`aiClientService.chat(...)`，`temperature=0.2`，解析返回的 JSON（`parseAiDetect` 兼容 Markdown 代码块包裹）。

### 6.2 确定性兜底判定（不盲信模型）

`detectSingle` 中**不**直接采用模型返回的 `matched` 布尔值，而是做确定性比对：

- 系统编码不在标准库 → `matched=false`；
- 模型给出可识别的 `bestMatchCode` → 与系统编码按 `id` 比对得出 `matched`；
- 模型未给出可识别编码 → 退而信任其 `matched` 判断；
- 反向兜底：系统编码无效且无建议编码时，从候选 top-1 给出建议。

> 这样即使大模型「误确认」，也能被标准库的确定性校验纠正。

### 6.3 质量评分的双模式（`computeQualityScore`）

| 模式 | 实现 | 触发条件 |
| --- | --- | --- |
| **AI 模式** | `aiDetect`（大模型比对候选子集） | `useAi=true` 且 `aiClientService.isEnabled()` |
| **规则模式** | `stdLib.ruleBasedAccuracy`（确定性规则校验） | 否则 / AI 调用异常时回退 |

评分写入 `qualityScore`，`accuracyScore = qualityScore × 0.8`，并据此判定数据状态（见 §4）。阈值：`threshold-review=60`、`threshold-export=80`。

### 6.4 文本分类识别 —— `classifyText`

把用户任意一段物料描述文字构造成「只有物料名称/规格、无系统分类编码」的临时 `CleanedDataEntity`，复用 `detectSingle` 逻辑：
- **AI 开启**：由大模型在候选标准分类中选出最合理编码，返回 `recommendedCode / recommendedName / reason / score`；
- **AI 关闭**：退化为关键词召回的 top 候选作为推荐（无评分）。

### 6.5 应用分类修正 —— `applyClassifyFix` / `applyClassifyFixBatch`

将清洗数据的分类字段替换为推荐的标准分类（按编码从标准库查找并填充 id/code/name/level/fullPath），替换后按规则重新评分并保存。支持单条与批量，是「AI 给出建议 → 人工/自动采纳」的闭环。

---

## 7. AI 属性提取（结构化抽取）

`startAiExtract` / `doStartAiExtract`：

1. 逐行读取原始数据，确定该行**分类编码**（优先用已清洗数据编码，否则用「分类列」匹配）；
2. 用分类编码查 `standard_title` 得到**标准字段**（目标字段列表）；
3. 取「属性拆分列」（全描述列）作为待拆分文本；
4. 调用大模型 `chat(buildAiSystemPrompt(), buildAiUserPrompt(fields, fullDesc))`，要求**只返回 JSON 键值对**；
5. `parseAiJson` 解析（兼容 Markdown 代码块、前后多余文字、键名模糊匹配到标准字段）；
6. 汇总所有键生成 `extra_data_title` 列，并批量写入 `extra_data`。

提示词与模型参数均可在 `application.yml` 的 `app.ai.*` 中配置；调用在事务外执行，避免长事务占用连接。

---

## 8. AI 对话

### 8.1 看板分析对话 —— `AiChatController.chat`

通用多轮对话接口，默认系统提示词为「数据清洗分析助手」，用于解读统计看板指标、失败原因与分类匹配情况。`/api/ai/chat-enabled` 用于前端探测 AI 是否可用。

### 8.2 标准分类问答（RAG-lite）—— `CategoryAiService` + `AiChatController.categoryChat`

为避免 text-to-SQL 风险，采用**检索增强生成（RAG-lite）**：

1. **检索（retrieve）**：从用户问题中识别并召回标准分类记录，策略覆盖：
   - 编码精确（取子树 + 祖先链）/ 编码前缀匹配；
   - 层级识别（阿拉伯数字「第1级」「层级1」与中文数字「一级/二级/三级」）；
   - 关键词检索（去掉数字编码后的文本，复用 `library.searchByKeyword`，top-K=15）；
   - 列举型问题（「有哪些/全部」）未命中层级时返回一级或全部；
   - 兜底：分词子串模糊匹配，保证 AI 总有上下文。
2. **构建上下文提示词（buildSystemPrompt）**：把命中的标准分类记录（编码/名称/层级/路径/单位/说明/旧编码旧名称）注入系统提示词，约束模型「只能依据该上下文作答，不得编造」。
3. **多轮对话**：`aiClientService.chatWithHistory(systemPrompt, messages)`；
4. **返回**：`reply`（AI 回复）+ `sources`（命中的标准分类来源记录，供前端展示）。

上下文条数上限 `category-max-context=40`，控制 token 与成本。

---

## 9. 通用 AI 客户端 —— `AiClientService`

- 兼容 OpenAI / DeepSeek / 通义千问（兼容模式）的 Chat Completions 接口；
- `chat(systemPrompt, userPrompt)`：单轮；`chatWithHistory(systemPrompt, messages)`：多轮（传入完整对话历史）；
- 通过 `app.ai.{base-url, api-key, model, temperature, max-tokens}` 配置；`isEnabled()` 在缺配置时返回 false，全系统自动降级到规则模式；
- 连接超时 15s、读取超时 120s（AI 调用较慢）；`api-key` 为空时不发送 `Authorization` 头（支持本地免鉴权部署）。

---

## 10. 关键配置（application.yml · `app` 段）

| 配置项 | 默认 | 说明 |
| --- | --- | --- |
| `app.ai.enabled` | true | 是否启用 AI |
| `app.ai.base-url` / `api-key` / `model` | — | 大模型接入点 |
| `app.ai.temperature` / `max-tokens` | 0.2 / 2048 | 采样参数 |
| `app.ai.category-top-k` / `category-max-context` | 15 / 40 | 问答检索与上下文上限 |
| `app.data-cleaning.standard-library.candidate-top-k` | 10 | AI 检测候选召回数 |
| `app.data-cleaning.quality-score.threshold-review` / `threshold-export` | 60 / 80 | 数据状态阈值 |

---

## 11. 可扩展点

1. **分类算法替换**：实现 `CategoryMatcher` 接口并声明为 Spring Bean，即可替换分层匹配器（如接入语义向量检索）。
2. **相似度升级**：实现 `SimilarityStrategy` 接口，将 bigram-Jaccard 替换为词向量/语义模型，无需改动匹配器。
3. **大模型切换**：修改 `app.ai` 配置即可在 OpenAI/DeepSeek/通义千问等兼容端点间切换。
4. **标准库热更新**：调用 `CategoryStandardLibrary.reload()` 即可重建内存索引，无需重启。
5. **AI 流程解耦**：AI 调用全部集中在 `AiClientService` 与 `DataCleaningServiceImpl` 的 `aiDetect` / `doStartAiExtract` 中，关闭 `app.ai.enabled` 后系统完全以规则模式运行，保证可用性。

---

## 12. 核心类速查

| 职责 | 类 |
| --- | --- |
| 通用大模型客户端 | `ai.AiClientService` |
| 标准库内存索引 / 候选召回 / 规则校验 | `service.CategoryStandardLibrary` |
| 分层分类匹配器 | `match.HierarchicalCategoryMatcher` |
| 相似度策略 | `match.SimilarityStrategy` / `match.DefaultSimilarityStrategy` |
| 匹配上下文/结果 | `match.CategoryMatchContext` / `match.CategoryMatchOutcome` |
| 清洗主流程 + AI识别/辅助识别 | `service.impl.DataCleaningServiceImpl` |
| 标准分类 RAG-lite 问答 | `service.CategoryAiService` |
| AI 对话 / 标准分类问答接口 | `controller.AiChatController` |
| 全描述解析规则 | `model.ParseRule` |
