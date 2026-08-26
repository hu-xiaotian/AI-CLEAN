# 数据清洗系统 V1 — API 详细设计规格

> 版本: v1.3.1 | 日期: 2026-08-21 | 状态: 已实施
>
> 变更履历：
> - v1.3.1（2026-08-21）：知识库设计调整——**移除版本号**：
    >   同名文件直接覆盖，向量库同步删除重建；`source_doc` + `version` 复合主键改为 `relative_path` 单主键。
    >   **URL 模式**：`POST /files` 的 JSON 请求改为 `relative_path`（相对于 `KB_FILE_DIR`），直接读文件不做下载；
    >   **文件操作外部化**：本系统只做向量增删，文件读写由外部系统管理。
    >   `POST /knowledge/ingest` 移除 `version` 字段，`reset=true` 改为全量重建。
> - v1.3（2026-08-21）：新增知识库引用（citations）——分类/提取结果携带标准文档依据引用，
    >   审核可溯源。新增文件管理 REST 接口：
    >   `GET /knowledge/files`（文档列表）、`GET /knowledge/files/{doc}`（查文档）、
    >   `POST /knowledge/files`（上传入库）、`DELETE /knowledge/files/{doc}`（删除文档）；
    >   `POST /knowledge/ingest` 保留为本地运维批量入库。
    >   **多格式支持**：上传/入库支持 .docx/.doc/.txt/.md/.xlsx/.xls/.pdf，配置项 `KB_ALLOWED_FILE_TYPES`。
    >   **元数据库**：支持达梦 DM8（`KB_METADATA_DB_TYPE=dameng`），DDL 见 `docs/ddl/kb_documents_dameng.sql`；
    >   也支持容器内 SQLite（默认）；达梦连接失败时自动降级。
    >   **外部文件存储**：`KB_FILE_DIR` 指定容器外挂载目录，Docker 重建不丢原始文件。
> - v1.2（2026-08-12）：新增任务恢复/续跑能力。新增 `paused` 状态、`pause`/`resume` 端点、
    >   `resume_count` 字段、`TASK_CANNOT_PAUSE`/`TASK_CANNOT_RESUME`/`TASK_NO_SOURCE` 错误码；
    >   `cancel` 明确为终态（含暂停中可取消）；Redis 新增 `:pause` 键与 `resume_count` 字段；
    >   `recover_orphaned` 启动自动续跑；Redis 开启 AOF。
> - v1.1（2026-07-28）：异步接口取消 ≤100 条限制补充说明（实际无上限）。
> - v1.0（2026-07-26）：初始设计。

---

## 1. 通用约定

### 1.1 内容类型与编码

- 所有请求/响应使用 `application/json`
- 字符编码 UTF-8
- 请求头 `Content-Type: application/json`

### 1.2 鉴权

所有 `/api/v1/*` 端点（除 health）需携带 API Key：

```
Authorization: Bearer {api_key}
```

multi-tenant Key 设计见 [第 7 节](#7-鉴权设计)。

### 1.3 通用错误格式

```json
{
  "error": {
    "code": "VALIDATION_ERROR",
    "message": "请求参数校验失败",
    "details": [
      { "field": "rows", "reason": "同步接口最多支持 10 条数据" }
    ]
  }
}
```

### 1.4 错误码全集

| 错误码 | HTTP 状态 | 说明 |
|--------|-----------|------|
| `UNAUTHORIZED` | 401 | API Key 缺失或无效 |
| `FORBIDDEN` | 403 | Key 角色权限不足 |
| `VALIDATION_ERROR` | 422 | 请求参数校验失败 |
| `SYNC_LIMIT_EXCEEDED` | 422 | 同步接口数据量超限 |
| `EMPTY_ROWS` | 422 | 数据行为空 |
| `MISSING_CALLBACK_URL` | 422 | 异步接口缺少回调地址 |
| `TASK_NOT_FOUND` | 404 | 任务不存在或已过期 |
| `TASK_CANNOT_CANCEL` | 409 | 任务状态不允许取消 |
| `TASK_CANNOT_PAUSE` | 409 | 任务状态不允许暂停（仅 queued/processing 可暂停） |
| `TASK_CANNOT_RESUME` | 409 | 任务状态不允许恢复（仅 paused/failed 可恢复） |
| `TASK_NO_SOURCE` | 409 | 任务源数据缺失/为空，无法续跑 |
| `KB_UNAVAILABLE` | 503 | 知识库不可用（未启用/空库/损坏），不影响清洗核心功能 |
| `FILE_NOT_FOUND` | 404 | 知识库文档不存在或已删除 |
| `FILE_ALREADY_EXISTS` | 409 | 文档已存在，请用 replace=true 或更换版本号 |
| `RATE_LIMITED` | 429 | 请求频率超限 |
| `LLM_UNAVAILABLE` | 503 | LLM 服务不可用 |
| `EMBEDDING_UNAVAILABLE` | 503 | 嵌入模型不可用 |
| `INTERNAL_ERROR` | 500 | 服务内部错误 |

### 1.5 HTTP 状态码速查

| 状态码 | 含义 |
|--------|------|
| 200 | 请求成功 |
| 202 | 异步任务已接受 |
| 400 | 请求格式错误 |
| 401 | 未认证 |
| 403 | 无权限 |
| 404 | 资源不存在 |
| 409 | 资源状态冲突 |
| 422 | 参数校验失败 |
| 429 | 频率限制 |
| 500 | 内部错误 |
| 503 | 依赖服务不可用 |

---

## 2. 数据模型

### 2.1 RawRow — 原始数据行

```json
{
  "index": 1,
  "columns": {
    "物资名称": "冷轧钢板",
    "规格型号": "1.5×1250×2500mm",
    "材质": "Q235B"
  }
}
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `index` | integer | ✓ | 行号，从 1 开始，对应源文件行号 |
| `columns` | Map\<string, string\> | ✓ | 列名→列值，键来自源 Excel 表头 |

### 2.2 CleanOptions — 清洗选项

```json
{
  "threshold": 0.7,
  "max_candidates": 10,
  "model": "default"
}
```

| 字段 | 类型 | 必填 | 默认值 | 范围 | 说明 |
|------|------|------|--------|------|------|
| `threshold` | float | ✗ | 0.7 | 0~1 | 置信度阈值，低于此值标记需人工复核 |
| `max_candidates` | integer | ✗ | 10 | 1~50 | 向量检索返回给 LLM 的候选分类数 |
| `model` | string | ✗ | null | — | 模型别名；null 表示走环境变量/默认路由 |

### 2.3 CleanRequest — 清洗请求

```json
{
  "task_id": "task-20260724-001",
  "callback_url": "http://java-service:8080/api/internal/tasks/task-20260724-001/result",
  "rows": [
    {
      "index": 1,
      "columns": { "物资名称": "冷轧钢板", "材质": "Q235B" }
    }
  ],
  "options": {
    "threshold": 0.7,
    "max_candidates": 10,
    "model": "default"
  }
}
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `task_id` | string | ✓ | 任务唯一标识，由 Java 服务生成，格式 `task-{YYYYMMDD}-{seq}` |
| `callback_url` | string | 条件 | **异步模式必填**。清洗完成后回调的 Java 服务地址 |
| `rows` | RawRow[] | ✓ | 待清洗数据行。同步 ≤10 条，异步无上限 |
| `options` | CleanOptions | ✗ | 清洗参数，不传使用默认值 |

### 2.4 Citation — 标准依据引用（v1.3 新增）

```json
{
  "role": "extract",
  "field": "牌号",
  "source_doc": "GB/T 706-2016 热轧型钢",
  "source_id": "GBT706-2016-0020",
  "section": "5.1",
  "matched_text": "牌号由 Q+屈服强度值+质量等级组成，如 Q235B 表示……",
  "score": 0.923
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `role` | string | `"classify"`（分类依据）或 `"extract"`（属性依据） |
| `field` | string \| null | 关联属性字段名；`role=classify` 时为 null |
| `source_doc` | string | 来源标准文件名，如 `"GB/T 706-2016 热轧型钢"` |
| `source_id` | string | 知识库文档 ID（可唯一定位到具体条款块） |
| `section` | string | 章节/条款号，如 `"5.1"`、`"表4"` |
| `matched_text` | string | 命中的标准原文片段（最长 200 字截断） |
| `score` | float | 检索相似度 ∈ [0, 1]（由检索命中直接搬运，不经 LLM 生成） |

> **设计要点**：`Citation` 由检索命中 dict 直接映射为纯数据对象，`category_codes` 等内部字段
> 不进入 API 响应（`classify` 角色的 `field` 固定为 null）。LLM 输出 `reason` 为展示用自由文本，
> 不做标准号校验。

### 2.5 CleanResult — 单条清洗结果（v1.3 变更）

```json
{
  "index": 1,
  "category_code": "100101",
  "category_name": "冷轧板材",
  "category_path": "/10/1001/100101",
  "confidence": 0.85,
  "category_confidence": 0.92,
  "extracted_attrs": {
    "物资名称": "冷轧钢板",
    "牌号": "Q235B",
    "规格": "1.5×1250×2500mm",
    "长度": null,
    "技术标准号": null
  },
  "missing_attrs": ["技术标准号"],
  "needs_review": true,
  "review_reason": "缺失必填字段: 技术标准号",
  "category_citations": [
    {
      "role": "classify",
      "field": null,
      "source_doc": "GB/T 708-2019 冷轧钢板和钢带",
      "source_id": "GBT708-2019-0012",
      "section": "3.1",
      "matched_text": "冷轧钢板和钢带……表面质量……",
      "score": 0.891
    }
  ],
  "attr_citations": {
    "牌号": [
      {
        "role": "extract",
        "field": "牌号",
        "source_doc": "GB/T 700-2006 碳素结构钢",
        "source_id": "GBT700-2006-0005",
        "section": "4.1",
        "matched_text": "牌号由屈服强度字母 Q + 屈服强度数值……",
        "score": 0.912
      }
    ]
  }
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `index` | integer | 对应原始行号 |
| `category_code` | string | 匹配的 3 级叶子分类编码，如 "100101" |
| `category_name` | string | 分类中文名，如 "冷轧板材" |
| `category_path` | string | 分类完整路径，如 "/10/1001/100101" |
| `confidence` | float | 综合置信度 0~1（分类 40% + 字段完成度 40% + 格式 20%） |
| `category_confidence` | float | 分类环节 LLM 返回的置信度 0~1 |
| `extracted_attrs` | Map\<string, string\|null\> | 提取的属性键值对，未提取到的为 null |
| `missing_attrs` | string[] | 缺失的必填属性字段名列表 |
| `needs_review` | boolean | 是否需要人工复核 |
| `review_reason` | string\|null | 复核原因，needs_review 为 true 时有值 |
| `category_citations` | Citation[] | **v1.3 新增**，分类依据引用（默认空数组） |
| `attr_citations` | dict\<string, Citation[]\> | **v1.3 新增**，按字段分组的属性依据引用（默认空字典） |

> **向后兼容**：`category_citations`/`attr_citations` 均有默认值（`[]` / `{}`），旧客户端忽略
> 新字段无感知。

**置信度算法**（`validator.py` 实现）：

```
confidence = 0.4 × cat_conf + 0.4 × fill_rate + 0.2 × format_rate
```

- `cat_conf`: LLM 分类返回的置信度
- `fill_rate`: **模板字段完成度**（已填字段数 / 模板总字段数，v1.3 变更为此基准）
- `format_rate`: 已填字段格式校验通过比例

**复核判断**：
```
needs_review = (confidence < threshold) OR (len(missing_attrs) > 0) OR 检索命中低于阈值且分类已映射标准
```

### 2.6 TaskStats — 任务统计（v1.3 微调）

```json
{
  "total_rows": 200,
  "classified_rows": 200,
  "processed_rows": 150,
  "high_confidence": 130,
  "medium_confidence": 15,
  "low_confidence": 5,
  "confidence_sum": 152.3
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `total_rows` | integer | 任务总行数 |
| `classified_rows` | integer | 分类阶段已处理行数（两阶段：先分类后提取） |
| `processed_rows` | integer | 已完成行数（异步模式进度跟踪，同步模式 = total_rows） |
| `high_confidence` | integer | 置信度 ≥ 0.9 的行数 |
| `medium_confidence` | integer | 0.7 ≤ 置信度 < 0.9 的行数 |
| `low_confidence` | integer | 置信度 < 0.7 的行数 |
| `confidence_sum` | float | 所有已处理行的 confidence 之和（用于计算平均置信度） |

> `estimated_accuracy` 已改为 `computed_field`：值为 `confidence_sum / processed_rows`（平均置信度），
> v1.2 的 `high / (high+medium+low)` 估算方式不再使用。

### 2.7 CleanResponse — 清洗响应

```json
{
  "task_id": "task-20260724-001",
  "status": "processing",
  "stats": {
    "total_rows": 200,
    "classified_rows": 200,
    "processed_rows": 0,
    "high_confidence": 0,
    "medium_confidence": 0,
    "low_confidence": 0,
    "confidence_sum": 0.0
  },
  "results": null,
  "error": null
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `task_id` | string | 任务 ID |
| `status` | string | `pending` \| `queued` \| `processing` \| `paused` \| `completed` \| `failed` \| `cancelled` |
| `stats` | TaskStats | 实时统计数据（含 classified_rows 与 confidence_sum） |
| `results` | CleanResult[] \| null | 清洗结果，同步完成时返回；异步/运行中为 null |
| `error` | string \| null | 失败时的错误信息 |
| `resume_count` | int | 续跑次数（崩溃自动续跑或手动 resume 累计，首次正常提交为 0） |

### 2.8 CategoryInfo — 分类信息

```json
{
  "category_code": "100101",
  "category_name": "冷轧板材",
  "full_path": "/10/1001/100101",
  "parent_name": "板材",
  "level": 3,
  "unit": "千克"
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `category_code` | string | 分类编码 |
| `category_name` | string | 分类中文名 |
| `full_path` | string | 完整路径，如 "/10/1001/100101" |
| `parent_name` | string | 父分类名称 |
| `level` | integer | 层级：1—一级，2—二级，3—三级（叶子） |
| `unit` | string | 标准单位，如 "千克" |

### 2.9 HealthCheckResult — 健康检查结果

```json
{
  "status": "healthy",
  "components": {
    "category_index": "ok",
    "template_index": "ok",
    "embedding_model": "ok"
  },
  "uptime_seconds": 3600,
  "version": "1.3.0"
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `status` | string | `healthy` \| `degraded` \| `unhealthy` |
| `components` | Map\<string, string\> | 各组件状态：`ok` \| `unavailable` \| `loading` |
| `uptime_seconds` | integer | 服务运行时长（秒） |
| `version` | string | 服务版本号 |

**组件列表（v1.3）**：

| 组件 | 说明 |
|------|------|
| `category_index` | 分类数据（CSV + 向量索引）是否已加载 |
| `template_index` | 属性字段模板（达梦同步 + 本地缓存）是否已加载 |
| `embedding_model` | bge-small 嵌入模型是否已加载 |

> v1.2 中 `llm` 组件已从健康检查移除（LLM 按需调用，不影响启动就绪状态）。

**状态判断**：

| status | 条件 |
|--------|------|
| `healthy` | 所有组件 `ok` |
| `degraded` | 任意核心组件状态非 `ok` 但非全 `loading` |
| `unhealthy` | `category_index` 为 `loading` 且无任何其他核心组件就绪 |

---

## 3. API 端点

### 3.1 POST /api/v1/clean — 同步清洗

≤10 条数据，单次请求内完成并返回。适用于快速验证和小批量处理。

**请求**：
```
POST /api/v1/clean
Authorization: Bearer {api_key}
Content-Type: application/json
```

```json
{
  "task_id": "task-20260724-001",
  "rows": [
    {
      "index": 1,
      "columns": { "物资名称": "冷轧钢板", "规格型号": "1.5×1250×2500mm", "材质": "Q235B" }
    }
  ],
  "options": { "threshold": 0.7 }
}
```

**成功响应 (200)**：
```json
{
  "task_id": "task-20260724-001",
  "status": "completed",
  "stats": {
    "total_rows": 5,
    "classified_rows": 5,
    "processed_rows": 5,
    "high_confidence": 4,
    "medium_confidence": 1,
    "low_confidence": 0,
    "confidence_sum": 4.35
  },
  "results": [
    {
      "index": 1,
      "category_code": "100101",
      "category_name": "冷轧板材",
      "category_path": "/10/1001/100101",
      "confidence": 0.85,
      "category_confidence": 0.92,
      "extracted_attrs": {
        "物资名称": "冷轧钢板",
        "牌号": "Q235B",
        "规格": "1.5×1250×2500mm"
      },
      "missing_attrs": ["技术标准号"],
      "needs_review": true,
      "review_reason": "缺失必填字段: 技术标准号",
      "category_citations": [
        {
          "role": "classify",
          "field": null,
          "source_doc": "GB/T 708-2019 冷轧钢板和钢带",
          "source_id": "GBT708-2019-0012",
          "section": "3.1",
          "matched_text": "冷轧钢板和钢带……表面质量……",
          "score": 0.891
        }
      ],
      "attr_citations": {
        "牌号": [
          {
            "role": "extract",
            "field": "牌号",
            "source_doc": "GB/T 700-2006 碳素结构钢",
            "source_id": "GBT700-2006-0005",
            "section": "4.1",
            "matched_text": "牌号由屈服强度字母 Q + 屈服强度数值组成……",
            "score": 0.912
          }
        ]
      }
    }
  ],
  "error": null
}
```

**校验规则**：

| 条件 | 错误码 | HTTP |
|------|--------|------|
| `rows.length == 0` | `EMPTY_ROWS` | 422 |
| `rows.length > 10` | `SYNC_LIMIT_EXCEEDED` | 422 |
| `threshold` ∉ [0, 1] | `VALIDATION_ERROR` | 422 |
| `task_id` 为空 | `VALIDATION_ERROR` | 422 |

**超时**：120 秒（Nginx + uvicorn 两侧均需配置）。

**所需角色**：`clean`

---

### 3.2 POST /api/v1/clean/async — 异步清洗

无条数限制。接受请求后立即返回 202，后台处理完后回调 Java 服务。

**请求**：
```
POST /api/v1/clean/async
Authorization: Bearer {api_key}
Content-Type: application/json
```

```json
{
  "task_id": "task-20260724-002",
  "callback_url": "http://java-service:8080/api/internal/tasks/task-20260724-002/result",
  "rows": [
    {
      "index": 1,
      "columns": { "物资名称": "冷轧钢板", "材质": "Q235B" }
    }
  ],
  "options": { "threshold": 0.7, "model": "default" }
}
```

**成功响应 (202)**：
```json
{
  "task_id": "task-20260724-002",
  "status": "processing",
  "stats": {
    "total_rows": 500,
    "classified_rows": 0,
    "processed_rows": 0,
    "high_confidence": 0,
    "medium_confidence": 0,
    "low_confidence": 0,
    "confidence_sum": 0.0
  },
  "results": null,
  "error": null
}
```

**校验规则**：

| 条件 | 错误码 | HTTP |
|------|--------|------|
| `callback_url` 为空 | `MISSING_CALLBACK_URL` | 422 |
| `rows.length == 0` | `EMPTY_ROWS` | 422 |
| `threshold` ∉ [0, 1] | `VALIDATION_ERROR` | 422 |
| `task_id` 为空 | `VALIDATION_ERROR` | 422 |

**所需角色**：`clean`

---

### 3.3 GET /api/v1/clean/{task_id} — 任务状态查询

查询异步任务的实时进度和结果。适用于 Java 轮询和前端进度展示。

**请求**：
```
GET /api/v1/clean/task-20260724-002
Authorization: Bearer {api_key}
```

**处理中 (200)**：
```json
{
  "task_id": "task-20260724-002",
  "status": "processing",
  "stats": {
    "total_rows": 500,
    "classified_rows": 500,
    "processed_rows": 320,
    "high_confidence": 260,
    "medium_confidence": 45,
    "low_confidence": 15,
    "confidence_sum": 278.4
  },
  "results": null,
  "error": null
}
```

**已完成 (200)**：
```json
{
  "task_id": "task-20260724-002",
  "status": "completed",
  "stats": {
    "total_rows": 500,
    "classified_rows": 500,
    "processed_rows": 500,
    "high_confidence": 420,
    "medium_confidence": 60,
    "low_confidence": 20,
    "confidence_sum": 435.0
  },
  "results": [
    { "index": 1, "category_code": "100101", "category_citations": [], "attr_citations": {}, ... }
  ],
  "error": null
}
```

**未找到 (404)**：
```json
{
  "error": {
    "code": "TASK_NOT_FOUND",
    "message": "任务 task-20260724-xxx 不存在或已过期（24h后自动清理）",
    "details": []
  }
}
```

**所需角色**：`clean`

---

### 3.4 POST /api/v1/clean/{task_id}/cancel — 取消任务

立即中断当前正在执行的 LLM 调用，已处理的行保留，未处理的行标记跳过。

**请求**：
```
POST /api/v1/clean/task-20260724-002/cancel
Authorization: Bearer {api_key}
```

**成功 (200)**：
```json
{
  "task_id": "task-20260724-002",
  "status": "cancelled",
  "stats": {
    "total_rows": 500,
    "classified_rows": 500,
    "processed_rows": 320,
    "high_confidence": 260,
    "medium_confidence": 45,
    "low_confidence": 15,
    "confidence_sum": 278.4
  },
  "results": null,
  "error": null
}
```

**校验规则**：

| 条件 | 错误码 | HTTP |
|------|--------|------|
| 任务不存在 | `TASK_NOT_FOUND` | 404 |
| 状态为 completed/failed/cancelled | `TASK_CANNOT_CANCEL` | 409 |

**所需角色**：`clean`

**取消语义**：
- 调用 `asyncio.Event.set()` 触发取消信号
- 当前正在执行的 LLM 调用通过 `asyncio.wait_for` 强制中断
- `processed_rows` 保留已完成的计数
- 取消后触发回调（`status: cancelled`）
- **取消为终态**：包括 queued/processing/paused 任务均可取消；取消后不可恢复，重做需 Java 层重提新任务
- 暂停中任务被取消时，先唤醒 pause 信号使 worker 在边界退出，再收尾为 cancelled 终态

---

### 3.4.1 POST /api/v1/clean/{task_id}/pause — 暂停任务

主动暂停进行中的任务，worker 在分块边界安全停下。

**请求**：

```
POST /api/v1/clean/task-20260724-001/pause
Authorization: Bearer {api_key}
```

**响应** `200`：

```json
{
  "task_id": "task-20260724-001",
  "status": "paused",
  "stats": {
    "total_rows": 200,
    "classified_rows": 200,
    "processed_rows": 120,
    "high_confidence": 90,
    "medium_confidence": 20,
    "low_confidence": 10,
    "confidence_sum": 102.0
  },
  "results": null,
  "error": null
}
```

**校验规则**：

| 条件 | 错误码 | HTTP |
|------|--------|------|
| 任务不存在 | `TASK_NOT_FOUND` | 404 |
| 状态为 paused/completed/failed/cancelled | `TASK_CANNOT_PAUSE` | 409 |

**所需角色**：`clean`

**暂停语义**：
- 仅 `queued`/`processing` 状态可暂停；触发 `pause_events[task_id]` 的 `asyncio.Event`
- worker 在下一分块边界检测到 pause 信号，置 `paused` 并 `PERSIST` 去掉 TTL（长期保留，不限时）
- 暂停不丢失已处理进度（`:rows` 结果列表保留）；恢复时从断点续跑
- 启动自动续跑创建的任务若进程内无 pause_event，则直接置 paused 兜底

### 3.4.2 POST /api/v1/clean/{task_id}/resume — 恢复任务

恢复 `paused`/`failed` 任务，从已处理断点续跑。

**请求**：

```
POST /api/v1/clean/task-20260724-001/resume
Authorization: Bearer {api_key}
```

**响应** `200`：

```json
{
  "task_id": "task-20260724-001",
  "status": "queued",
  "stats": {
    "total_rows": 200,
    "classified_rows": 200,
    "processed_rows": 120,
    "high_confidence": 90,
    "medium_confidence": 20,
    "low_confidence": 10,
    "confidence_sum": 102.0
  },
  "results": null,
  "error": null
}
```

**校验规则**：

| 条件 | 错误码 | HTTP |
|------|--------|------|
| 任务不存在 | `TASK_NOT_FOUND` | 404 |
| 状态非 paused/failed | `TASK_CANNOT_RESUME` | 409 |
| 源数据缺失/为空（无法续跑） | `TASK_NO_SOURCE` | 409 |

**所需角色**：`clean`

**恢复语义**：
- 读 `data/raw_inputs/{task_id}.json` 源数据；无则标 failed 并返回 `TASK_NO_SOURCE`
- `resume_count += 1`；清历史 `error`；置 `queued` 重新进入调度
- 断点 = `store.get_processed_indices(task_id)`（从 `:rows` 结果列表反推已处理 index，权威记录）
- 后台启动 worker，注入 `resume_from=processed_indices`，命中已处理行则跳过（不重复调 LLM）
- `failed` 任务恢复后从断点续跑，未处理行重新执行
- 启动自动续跑（`recover_orphaned` + `resume_orphaned_tasks`）同理：有源数据从断点续跑，无源数据标 failed

---

### 3.5 GET /api/v1/clean/tasks — 任务/队列监控

查看全部任务状态分布与正在处理的任务列表。

**请求**：

```
GET /api/v1/clean/tasks?limit=50
Authorization: Bearer {api_key}
```

| 参数 | 类型 | 必填 | 默认值 | 范围 | 说明 |
|------|------|------|--------|------|------|
| `limit` | integer | ✗ | 50 | 1~200 | 返回任务数量上限（按创建时间倒序） |

**响应** `200`：

```json
{
  "total": 12,
  "by_status": {
    "processing": 1,
    "queued": 3,
    "completed": 5,
    "failed": 2,
    "paused": 1
  },
  "running_count": 1,
  "running": [
    {
      "task_id": "task-20260724-002",
      "status": "processing",
      "total_rows": 500,
      "processed_rows": 320,
      "high_confidence": 260,
      "medium_confidence": 45,
      "low_confidence": 15,
      "created_at": "2026-07-24T10:30:00Z",
      "running": true
    }
  ],
  "tasks": [
    {
      "task_id": "task-20260724-002",
      "status": "processing",
      "total_rows": 500,
      "processed_rows": 320,
      "high_confidence": 260,
      "medium_confidence": 45,
      "low_confidence": 15,
      "created_at": "2026-07-24T10:30:00Z",
      "running": true
    }
  ]
}
```

**所需角色**：`clean`

---

### 3.6 GET /api/v1/categories/search — 分类搜索

按关键词搜索 3 级叶子分类（1,219 条），供前端手动修正时使用。

**请求**：
```
GET /api/v1/categories/search?q=冷轧&limit=10
Authorization: Bearer {api_key}
```

| 参数 | 类型 | 必填 | 默认值 | 范围 | 说明 |
|------|------|------|--------|------|------|
| `q` | string | ✓ | — | ≥1 字符 | 搜索关键词，匹配 `category_name` 和旧编码/旧名称 |
| `limit` | integer | ✗ | 20 | 1~100 | 返回数量上限 |

**成功 (200)**：
```json
{
  "results": [
    {
      "category_code": "100101",
      "category_name": "冷轧板材",
      "full_path": "/10/1001/100101",
      "parent_name": "板材",
      "level": 3,
      "unit": "千克"
    },
    {
      "category_code": "100109",
      "category_name": "冷轧钢带",
      "full_path": "/10/1001/100109",
      "parent_name": "板材",
      "level": 3,
      "unit": "千克"
    }
  ]
}
```

**搜索行为**：
- 只搜索 3 级叶子分类（level=3）
- 匹配字段：`category_name`（中文名）、`old_name_1`~`old_name_5`（旧名称/别名）
- 匹配方式：MySQL `LIKE` 或 numpy 向量相似度（按匹配度排序）
- `q` 长度 < 1 → 422

**所需角色**：`categories`

---

### 3.7 GET /api/v1/health — 健康检查

供 Docker healthcheck、Nginx upstream、Java 服务检测 Python 服务是否就绪。
**此端点不校验 API Key。**

**请求**：
```
GET /api/v1/health
```

**就绪 (200)**：
```json
{
  "status": "healthy",
  "components": {
    "category_index": "ok",
    "template_index": "ok",
    "embedding_model": "ok"
  },
  "uptime_seconds": 3600,
  "version": "1.3.0"
}
```

**降级 (200)** — 部分组件不可用但核心可用：
```json
{
  "status": "degraded",
  "components": {
    "category_index": "ok",
    "template_index": "ok",
    "embedding_model": "unavailable"
  },
  "uptime_seconds": 3600,
  "version": "1.3.0"
}
```

**不可用 (503)**：
```json
{
  "status": "unhealthy",
  "components": {
    "category_index": "loading",
    "template_index": "loading",
    "embedding_model": "loading"
  },
  "uptime_seconds": 1200,
  "version": "1.3.0"
}
```

**状态判断**：

| status | 条件 |
|--------|------|
| `healthy` | 所有组件 `ok` |
| `degraded` | 任意组件状态非 `ok` 但非全 `loading` |
| `unhealthy` | `category_index` 为 `loading` 且无任何其他核心组件就绪 |

**所需角色**：`health`（不校验也允许访问，但带 Key 的调用方仍需有 `health` 角色）

---

## 4. 知识库接口（v1.3 新增）

知识库提供标准文档的向量化检索能力，为分类和属性提取提供可溯源的权威依据。
所有知识库端点走现有 auth 中间件，所需角色为 `knowledge`。

### 4.1 GET /api/v1/knowledge/search — 知识检索

在标准文档知识库中检索相关条款，供调试和审核溯源使用。

**请求**：
```
GET /api/v1/knowledge/search?q=冷轧钢板材质定义&category_code=100101&field=牌号&top_k=5
Authorization: Bearer {api_key}
```

| 参数 | 类型 | 必填 | 默认值 | 说明 |
|------|------|------|--------|------|
| `q` | string | ✓ | — | 检索查询文本（用于向量编码） |
| `category_code` | string | ✗ | "" | 限定命中的分类编码范围（zvec 倒排过滤） |
| `field` | string | ✗ | "" | 进一步限定匹配的字段名 |
| `top_k` | integer | ✗ | 5 | 返回命中条数上限 |

**响应 (200)**：
```json
{
  "query": "冷轧钢板材质定义",
  "hits": [
    {
      "source_id": "GBT708-2019-0012",
      "source_doc": "GB/T 708-2019 冷轧钢板和钢带",
      "section": "3.1",
      "heading": "定义与分类",
      "field": "",
      "matched_text": "冷轧钢板和钢带……表面质量……",
      "category_codes": ["100101", "100109"],
      "score": 0.891
    }
  ]
}
```

**错误**：

| 情况 | HTTP | 错误码 |
|------|------|--------|
| 知识库未启用/空库/损坏 | 503 | `KB_UNAVAILABLE` |
| `q` 参数缺失 | 400 | `BAD_REQUEST` |

**所需角色**：`knowledge`

---

### 4.2 GET /api/v1/knowledge/stats — 知识库统计

返回知识库当前覆盖情况，用于监控和运维。

**请求**：
```
GET /api/v1/knowledge/stats
Authorization: Bearer {api_key}
```

**知识库就绪 (200)**：
```json
{
  "enabled": true,
  "num_chunks": 1523,
  "num_docs": 41,
  "documents": {
    "category/property/GB 708-2019 冷轧钢板和钢带.docx": 36,
    "category/property/GB/T 706-2016 热轧型钢.docx": 42
  }
}
```

**知识库未就绪 (200)**：
```json
{
  "enabled": false,
  "error": "unavailable"
}
```

> `error` 字段值含义：
> - `"unavailable"`：知识库未启用或初始化失败
> - `"corrupted"`：库目录存在但文件损坏（需停服重建入库）

**所需角色**：`knowledge`

---

### 4.3 POST /api/v1/knowledge/ingest — 文档入库

将标准文档解析、分块、向量化后入库。入库与运行中检索共用同一 zvec store 句柄
（单写者限制：入库操作在服务进程内执行；离线批量重建须停服后操作）。

**请求**：
```
POST /api/v1/knowledge/ingest
Authorization: Bearer {api_key}
Content-Type: application/json
```

```json
{
  "doc_dir": "kb/",
  "reset": false
}
```

| 字段 | 类型 | 必填 | 默认值 | 说明 |
|------|------|------|--------|------|
| `doc_dir` | string | ✓ | — | 标准文档所在目录（支持 `.docx`、`.txt` 等格式） |
| `reset` | boolean | ✗ | false | true = 清空旧库后重建；false = 增量追加（按相对路径去重） |

**响应 (200)**：
```json
{
  "ingested": 1523
}
```

> `ingested` 为入库 chunk 总数（每块 ~200~500 字）。

**错误**：

| 情况 | HTTP | 错误码 |
|------|------|--------|
| 知识库未启用/不可用 | 503 | `KB_UNAVAILABLE` |
| `doc_dir` 缺失或为空 | 400 | `BAD_REQUEST` |

**所需角色**：`knowledge`

**注意事项**：
- `reset=true` 会清空旧库后重建，运行中调用会导致检索临时空窗，**请在低峰期或停服维护时操作**
- `standard_no` 字段由入库管道从文件名正则解析；解析失败时支持在 `POST /api/v1/knowledge/ingest`
  请求体中通过额外字段覆盖指定（见 `knowledge/doc_ingest.py`）
- 离线批量入库（CLI 模式）：`PYTHONPATH=python_cleaner python -m scripts.doc_ingest kb/`

---

### 4.4 文件管理接口（外部系统 REST 访问）

面向外部系统的文件级 CRUD 接口。

**设计原则**：
- 无版本号：同名相对路径直接覆盖，向量库同步删除重建
- URL 模式：`relative_path` 指向 `KB_FILE_DIR` 下的文件，本系统只做向量增删
- Multipart 模式：文件写入 `KB_FILE_DIR`（若已挂载）
- 文件操作外部化：文件读写由外部系统管理，本系统不读写文件内容

#### 4.4.1 GET /api/v1/knowledge/files — 文档列表

列出知识库中所有活跃文档（含基本信息，不含已删除）。

**请求**：
```
GET /api/v1/knowledge/files
Authorization: Bearer {api_key}
```

| 参数 | 类型 | 必填 | 默认值 | 说明 |
|------|------|------|--------|------|
| `include_deleted` | boolean | ✗ | false | 是否包含已删除记录 |

**响应 (200)**：
```json
{
  "total": 3,
  "files": [
    {
      "relative_path": "category/property/GB 708-2019 冷轧钢板和钢带.docx",
      "status": "active",
      "chunk_count": 36,
      "file_size": 204800,
      "uploaded_at": "2026-08-20T10:30:00+00:00",
      "uploaded_by": "api",
      "file_hash": "a3f2c1b8d9..."
    }
  ]
}
```

**错误**：

| 情况 | HTTP | 错误码 |
|------|------|--------|
| 知识库不可用 | 503 | `KB_UNAVAILABLE` |

**所需角色**：`knowledge`

---

#### 4.4.2 GET /api/v1/knowledge/files/{relative_path} —查文档

查指定文档信息。

**请求**：
```
GET /api/v1/knowledge/files/category/property/GB%20708-2019%20%E5%86%B7%E8%BD%AE%E9%92%A2%E6%9D%BF%E5%92%8C%E9%92%A2%E5%B8%A6.docx
Authorization: Bearer {api_key}
```

**响应 (200)**：
```json
{
  "relative_path": "category/property/GB 708-2019 冷轮钢板和钢带.docx",
  "status": "active",
  "chunk_count": 36,
  "file_size": 204800,
  "uploaded_at": "2026-08-19T10:30:00+00:00",
  "uploaded_by": "api",
  "file_hash": "a3f2c1b8d9..."
}
```

**错误**：

|情况|协讯|错误码|
|------|------|--------|
|文档不存在|404|`FILE_NOT_FOUND`|

**所需角色**：`knowledge`

---

#### 4.4.3 DELETE /api/v1/knowledge/files/{relative_path} —删除文档

删除指定文档（软删关数据 + 删除向量 chunk）。文件本身不操作。

**请求**：
```
DELETE /api/v1/knowledge/files/category/property/GB%20708-2019%20%E5%86%B7%E8%BD%AE%E9%92%A2%E6%9D%BF%E5%92%8C%E9%92%A2%E5%B8%A6.docx
Authorization: Bearer {api_key}
```

**响应 (200)**：
```json
{
  "message": "删除成功",
  "relative_path": "category/property/GB 708-2019 冷轮钢板和钢带.docx",
  "chunks_removed": 36
}
```

**错误**：

|情况|协讯|错误码|
|------|------|--------|
|知识库不可用|503|`KB_UNAVAILABLE`|
|文档不存在|404|`FILE_NOT_FOUND`|

**所需角色**：`knowledge`

---

#### 4.4.4 POST /api/v1/knowledge/files —上传/开向

上传标准文档并入库，支持 multipart 直接上传或 JSON 相对跟跟径两种方式。

同名文件直接覆相：先删向量 chunk，再入库新数据。

**两种请求方式**（自动路由）：

##### A. multipart/form-data（直接上传）

```
POST /api/v1/knowledge/files
Authorization: Bearer {api_key}
Content-Type: multipart/form-data
```

|表单字段|类型|必填|默认值|说明|
|---------|------|------|--------|------|
|`file`|binary|✓|—|标准文档，支持 .docx/.doc/.txt/.md/.xlsx/.xls/.pdf|
|`relative_path`|string|✓|文件名|相对于 KB_FILE_DIR 的路径，如 `category/property/a.docx`。|

##### B. application/json（相对路径）

相对路径指向 KB_FILE_DIR 下的文件，本系统只读文件做入库，不做文件下载，文件操作由外部系统管理。

```
POST /api/v1/knowledge/files
Authorization: Bearer {api_key}
Content-Type: application/json
Accept: application/json
```

```json
{
  "relative_path": "category/property/GB 708-2019 冷轧钢板和钢带.docx"
}
```

|单字段|类型|必填|说明|
|----------|------|------|------|
|`relative_path`|string|✓|相对于 KB_FILE_DIR 的路径，如 `category/property/a.docx`，本系统读取该文件入库|

**成功响应 (200)**：
```json
{
  "message": "入库成功",
  "relative_path": "category/property/GB 708-2019 冷轧钢板和钢带.docx",
  "status": "active",
  "chunk_count": 36,
  "file_size": 204800,
  "uploaded_at": "2026-08-21T14:00:00+00:00",
  "uploaded_by": "api",
  "file_hash": "a3f2c1b8d9..."
}
```

**错误**：

|情况|HTTP|错误码|
|------|------|--------|
|文件类型不支持|400|`BAD_REQUEST`|
|`KB_FILE_DIR` 未挂载或无读权限|500|`INTERNAL_ERROR`|
|相对路径下文件不存在|404|`FILE_NOT_FOUND`|
|知识库不可用|503|`KB_UNAVAILABLE`|

**所需角色**：`knowledge`

---

#### 4.4.5 POST /api/v1/knowledge/ingest — 本地批量入库

元数据库类型由 `KB_METADATA_DB_TYPE` 配置（默认 `sqlite`，可设为 `dameng`）：

| DB 类型 | 连接参数 | 说明 |
|---------|---------|------|
| sqlite | `KB_DIR/kb_store/metadata.db` | 容器内单文件，Docker 挂载卷持久化 |
| dameng | `DM_HOST/PORT/USER/PASSWORD/SCHEMA` | 直连达梦 DM8，与清洗服务共用同一实例 |

**达梦 DDL**：`docs/ddl/kb_documents_dameng.sql`

| 表 | 说明 |
|----|------|
| `kb_documents` | 相对路径 / 状态 / chunk 数 / 文件大小 / hash / 上传时间 |

**状态枚举**：`active`（活跃）｜`deleted`（软删）。无版本号，同名直接覆盖。

**文件存储**：`KB_FILE_DIR`（容器外目录，Docker 挂载宿主目录）。文件操作由外部系统管理，

---

## 5. 任务生命周期与 Redis 存储

### 5.1 状态流转

```
                    ┌──────────┐
                    │ pending  │  Java 发起 POST /async
                    └────┬─────┘
                         │ 开始处理（asyncio.create_task）
                         ▼
                    ┌──────────┐
              ┌─────│processing│─────┐
              │     └────┬─────┘     │
              │          │           │
         cancel      全部完成     LLM异常
         (立即中断)      │           │
              │          ▼           ▼
              ▼     ┌──────────┐ ┌────────┐
        ┌──────────┐│completed │ │ failed │
        │cancelled │└────┬─────┘ └───┬────┘
        └────┬─────┘     │           │
             │           │           │
             └───────────┴───────────┘
                         │
                    回调 Java 服务
                    (最多重试 3 次)
                         │
                         ▼
                   Redis TTL 24h
                   到期自动清理
```

### 5.2 Redis Key 设计

```
clean:task:{task_id}          → Hash  (status, stats_json, created_at, expires_at, resume_count)
clean:task:{task_id}:rows     → List  (每完成一条 RPUSH CleanResult JSON)
clean:task:{task_id}:cancel   → String (取消标记, SETEX TTL=24h, value="1")
clean:task:{task_id}:pause    → String (暂停标记, 进程内 asyncio.Event 镜像; paused 时任务 PERSIST 去 TTL)
```

> `resume_count` 记录续跑次数（崩溃自动续跑 + 手动 resume 累计）。Redis 已开启 AOF 持久化
>（`appendonly yes --appendfsync everysec`），硬崩溃最多丢失 ≤1s 进度，支撑启动自动续跑与
> paused 长期保留。

### 5.3 存储分层

| 存储 | 位置 | 用途 | 生命周期 |
|------|------|------|----------|
| **Redis** | Python 侧 | 运行时任务状态、实时进度、取消信号、中间结果 | TTL 24h |
| **达梦 DM8** | Java 侧 | 最终清洗结果（含 citations JSON 列）、人工修正记录、质量统计 | 持久化 |

### 5.4 过期清理

后台协程每 5 分钟扫描 Redis key，清理 TTL 到期的 `clean:task:*` 键。

---

## 6. Python → Java 回调协议

### 6.1 回调请求

清洗完成、失败或取消后，Python 向 `callback_url` 发送 POST：

```
POST {callback_url}
Content-Type: application/json
```

```json
{
  "task_id": "task-20260724-002",
  "status": "completed",
  "stats": {
    "total_rows": 500,
    "classified_rows": 500,
    "processed_rows": 500,
    "high_confidence": 420,
    "medium_confidence": 60,
    "low_confidence": 20,
    "confidence_sum": 435.0
  },
  "results": [
    {
      "index": 1,
      "category_code": "100101",
      "category_name": "冷轧板材",
      "category_path": "/10/1001/100101",
      "confidence": 0.85,
      "category_confidence": 0.92,
      "extracted_attrs": { ... },
      "missing_attrs": ["技术标准号"],
      "needs_review": true,
      "review_reason": "缺失必填字段: 技术标准号",
      "category_citations": [...],
      "attr_citations": {...}
    }
  ],
  "error": null
}
```

> **v1.3 回调变更**：每条 `CleanResult` 增加 `category_citations`/`attr_citations` 字段，
> Java 端需要 `ALTER TABLE clean_result ADD COLUMN (category_citations JSON, attr_citations JSON)`。
> 历史数据列值为 NULL，旧客户端兼容；新增列后 Python 回调正常写入。

### 6.2 重试策略

- 最多重试 **3 次**，间隔 1s / 3s / 5s
- 3 次全部失败后停止重试，任务保留在 Redis（TTL 24h）
- Java 通过轮询 GET `/api/v1/clean/{task_id}` 兜底拉取结果

### 6.3 回调体大小预估

| 数据量 | 预估大小（无 citations） | 预估大小（含 citations） |
|--------|------------------------|------------------------|
| 100 条 | ~500 KB | ~600 KB |
| 500 条 | ~2.5 MB | ~3.2 MB |
| 1,000 条 | ~5 MB | ~6.5 MB |
| 2,000 条 | ~10 MB | ~13 MB |

> 含 citations 时每条额外 ~500~1500 字节（引用条数 × 平均 200 字片段），大多数场景仍在 10MB 以内。
> 超过 10MB 时，回调改为分页：`callback_url` + `?page=1&size=1000`，Java 需要支持分页接收。

---

## 7. 鉴权设计

### 7.1 方案

**API Key + 角色权限**，内网服务间共享密钥认证。

```
Authorization: Bearer {api_key}
```

### 7.2 配置格式

通过环境变量 `API_KEYS_CONFIG` 注入 JSON：

```json
[
  {
    "key": "py-cleaner-key-001",
    "client": "java-service",
    "roles": ["clean", "categories", "knowledge", "health"]
  },
  {
    "key": "py-cleaner-key-002",
    "client": "admin-tool",
    "roles": ["categories", "knowledge", "health"]
  }
]
```

### 7.3 端点 → 角色映射

| 端点 | 所需角色 |
|------|----------|
| `POST /api/v1/clean` | `clean` |
| `POST /api/v1/clean/async` | `clean` |
| `GET /api/v1/clean/{task_id}` | `clean` |
| `POST /api/v1/clean/{task_id}/cancel` | `clean` |
| `POST /api/v1/clean/{task_id}/pause` | `clean` |
| `POST /api/v1/clean/{task_id}/resume` | `clean` |
| `GET /api/v1/clean/tasks` | `clean` |
| `GET /api/v1/categories/search` | `categories` |
| `GET /api/v1/knowledge/search` | `knowledge` |
| `GET /api/v1/knowledge/stats` | `knowledge` |
| `GET /api/v1/knowledge/files` | `knowledge` |
| `GET /api/v1/knowledge/files/{relative_path}` | `knowledge` |
| `POST /api/v1/knowledge/files` | `knowledge` |
| `DELETE /api/v1/knowledge/files/{relative_path}` | `knowledge` |
| `POST /api/v1/knowledge/ingest` | `knowledge` |

### 7.4 鉴权中间件行为

```
请求到达 → 提取 Authorization Header
  ├─ 无 Header → 路由是 /health? → 放行 : 返回 401
  ├─ 有 Header → 查找 Key 配置
  │   ├─ 未找到 → 401 UNAUTHORIZED
  │   ├─ 找到 → 检查 roles
  │   │   ├─ 满足 → 放行，注入 client 到 request.state
  │   │   └─ 不满足 → 403 FORBIDDEN
  └─ 异常 → 500
```

### 7.5 错误响应

**401 — Key 无效**：
```json
{
  "error": {
    "code": "UNAUTHORIZED",
    "message": "无效的 API Key",
    "details": []
  }
}
```

**403 — 权限不足**：
```json
{
  "error": {
    "code": "FORBIDDEN",
    "message": "权限不足，当前 Key 缺少角色: knowledge",
    "details": []
  }
}
```

### 7.6 运维约定

- Key 增删修改后**重启服务生效**（初期不引入热更新）
- 每次请求在日志中记录 `client` 标识，用于审计
- 无 Key 配置时服务仍可启动（health 端点不受影响）

---

## 8. 知识库配置

通过环境变量配置知识库行为，支持渐进式 rollout（KB 未就绪时自动降级为 v1.2 行为）。

### 8.1 环境变量

| 变量 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `KB_ENABLED` | bool | `true` | 知识库总开关；`false` 时完全退化为 v1.2 行为，citations 为空 |
| `KB_DIR` | string | `"kb_store"` | zvec collection 目录（运行时产物，Docker 挂载卷） |
| `KB_FILE_DIR` | string | `""` | 文档文件存储目录（`KB_FILE_DIR` 下相对路径入库；为空则仅内存操作） |
| `KB_ALLOWED_FILE_TYPES` | string | `".docx,.doc,.txt,.md,.xlsx,.xls,.pdf"` | 允许上传的文件类型（逗号分隔） |
| `KB_MAP_CSV` | string | `"docs/category_standard_map.csv"` | 分类↔标准文档映射表路径 |
| `KB_SCORE_THRESHOLD` | float | `0.5` | 检索相似度阈值（相似度 = 1 - cosine_distance） |
| `KB_METADATA_DB_TYPE` | string | `"sqlite"` | 元数据库类型：`sqlite`（默认）或 `dameng` |

> **达梦连接**：KB 元数据库达梦连接**复用清洗服务的 `DM_HOST/PORT/USER/PASSWORD/SCHEMA`**（`.env` 中已配置），无需单独指定。

### 8.2 降级矩阵

| 情况 | 行为 | 审核影响 |
|------|------|----------|
| `KB_ENABLED=false` 或库目录不存在 | 走 v1.2 路径，citations 为空 | info 日志，属正常配置态 |
| 库目录存在但文件损坏（zvec open 抛异常） | 走 v1.2 路径，citations 为空，warning 日志 | `/knowledge/stats` 返回 `{"enabled": false, "error": "corrupted"}`，供监控告警 |
| 分类未映射标准（渐进 rollout 常态） | 静默兜底走 v1.2 路径 | **不标复核**，避免未覆盖分类批量淹没审核队列 |
| 分类已映射标准但检索相似度低于阈值 | 不注入 prompt，`needs_review=True`，`review_reason="无标准依据"` | 触发复核 |
| 文本点名标准号但该标准未入库 | 不注入该标准，走分类映射/field_hints 兜底，info 日志 | **不标复核**，避免标准号场景批量淹没审核队列 |

> 知识库检索全在 CPU（embedding 2ms + zvec 过滤检索 ~4ms@20万条），不占用 NPU 资源。
> 提取 prompt 中标准依据段总预算封顶 ~1500 字（字段数 >8 时每字段降为 1 条）。

---

## 9. 端点速查表

| 端点 | 方法 | 角色 | 说明 |
|------|------|------|------|
| `/api/v1/clean` | POST | `clean` | 同步清洗 ≤10 条，120s 超时 |
| `/api/v1/clean/async` | POST | `clean` | 异步清洗，202 响应，Redis 管理 |
| `/api/v1/clean/{task_id}` | GET | `clean` | 查询进度/结果，读 Redis |
| `/api/v1/clean/{task_id}/cancel` | POST | `clean` | 取消，立即中断 LLM 调用 |
| `/api/v1/clean/{task_id}/pause` | POST | `clean` | 暂停，在分块边界安全停下 |
| `/api/v1/clean/{task_id}/resume` | POST | `clean` | 恢复 paused/failed 任务，从断点续跑 |
| `/api/v1/clean/tasks` | GET | `clean` | 任务/队列监控，含状态分布统计 |
| `/api/v1/categories/search` | GET | `categories` | 搜索 3 级叶子分类 |
| `/api/v1/knowledge/search` | GET | `knowledge` | 知识库向量检索（调试/溯源） |
| `/api/v1/knowledge/stats` | GET | `knowledge` | 知识库统计（覆盖情况/就绪状态） |
| `/api/v1/knowledge/files` | GET | `knowledge` | 文档列表 |
| `/api/v1/knowledge/files/{relative_path}` | GET | `knowledge` | 查文档信息 |
| `/api/v1/knowledge/files` | POST | `knowledge` | 上传/入库（multipart 或 relative_path） |
| `/api/v1/knowledge/files/{relative_path}` | DELETE | `knowledge` | 删除文档（向量 + 元数据） |
| `/api/v1/knowledge/ingest` | POST | `knowledge` | 本地批量入库（运维用） |
| `/api/v1/health` | GET | 无 | 健康检查，不强制鉴权 |

---

## 10. 变更摘要

### 10.1 相对于 V1.2 的变更（v1.3）

| 项目 | V1.2 | v1.3 |
|------|------|------|
| 分类/提取依据 | CSV 分类目录 + field_hints | 标准文档知识库（向量检索 + 精确匹配） |
| 结果可溯源性 | 无 | `category_citations`/`attr_citations` 字段，携带标准文档引用 |
| 知识检索接口 | 无 | `POST /knowledge/ingest`、`GET /knowledge/search`、`GET /knowledge/stats` + 文件管理 REST 接口 |
| 评分基准 | `_field_in_source` 智能检测 | **模板字段完成度**（已填/模板总字段数） |
| 任务统计 | `high/medium/low` 估算准确率 | `confidence_sum / processed_rows`（平均置信度）+ `classified_rows` |
| 健康检查组件 | 含 `llm` | 移除 `llm`，保留 `category_index`/`template_index`/`embedding_model` |
| 服务版本 | 1.0.0 | 1.3.0 |
| 错误码 | 12 个 | **15 个**（新增 `KB_UNAVAILABLE`/`FILE_NOT_FOUND`/`FILE_ALREADY_EXISTS`） |
| 配置项 | — | `KB_ENABLED`/`KB_DIR`/`KB_METADATA_DB_TYPE`/`KB_FILE_DIR`/`KB_ALLOWED_FILE_TYPES` |

### 10.2 相对于 V1.3 的变更（v1.3.1）

| 项目 | V1.3 | v1.3.1 |
|------|-------|---------|
| 版本管理 | `source_doc` + `version` 复合主键 | `relative_path` 单主键，无版本号 |
| 同名文件 | 多版本递增（v1→v2→v3） | 直接覆盖，向量库同步删除重建 |
| 文件操作 | 内部管理（下载/保存/删除） | 外部化：只做向量增删，文件由外部系统管理 |
| URL/JSON 上传 | `url` + `filename` 下载文件 | `relative_path` 读 `KB_FILE_DIR` 下文件 |
| 端点数量 | 9 个（GET×4, POST×2, DELETE×3） | **4 个**（GET×2, POST×1, DELETE×1） |
| 达梦 DDL | `source_doc` + `version` + `file_path` | `relative_path` 单列，无版本 |
| 服务版本 | 1.3.0 | 1.3.1 |

### 10.3 向后兼容性

- `category_citations` 默认 `[]`，`attr_citations` 默认 `{}`——旧客户端忽略新字段无感知
- `KB_ENABLED=false` 时完整退化为 v1.2 行为，Java/前端无需修改即可工作
- 回调体中 `category_citations`/`attr_citations` 为新增字段，达梦 ALTER TABLE 后历史数据为 NULL
- **达梦降级**：`KB_METADATA_DB_TYPE=dameng` 时若达梦连接失败，自动降级为 SQLite，无需重启服务
- v1.3.1 破坏性变更：达梦 `KB_DOCUMENTS` 表须重建（`source_doc`→`relative_path`，删除 `version` 列）
