# AI Clean 数据清洗系统

基于 Java Spring Boot 的数据清洗工作台，将杂乱的 Excel 源数据通过 **全描述解析 → 分类匹配 → 数据清洗 → 质量评分 → 人工审核 → 导出** 的标准流水线，映射为符合「主数据标准分类体系」的干净数据。

系统在**不依赖大模型也能运行**的前提下，提供了可插拔的 AI 能力：AI 属性提取、AI 辅助分类检测、以及基于标准分类库的 RAG-lite 问答与看板对话。

## 功能特性

- ✅ Excel 文件上传与解析（支持自定义「分类列」「全描述列」）
- ✅ 全描述解析（`ParseRule`）：从自由文本描述中拆分出物资名称/规格/牌号等键值对
- ✅ 层级分类管理（支持 `10` / `1001` / `100101` 三级编码）
- ✅ 标准分类库内存索引 + 分层分类匹配算法（同义词 / 名称 / 编码 / 语义多策略打分）
- ✅ AI 属性提取：按分类编码查找标准字段，由大模型把描述文本拆成结构化属性
- ✅ AI 辅助分类检测：用大模型把系统分类与标准库比对，给出评分、一致性判定与建议编码
- ✅ 数据质量评分（AI 模式 / 规则模式双模式，可自动降级）
- ✅ 多条件数据查询、检索与质量报告
- ✅ 人工审核工作流（任务领取 / 处理 / 完成 / 超时）
- ✅ 多格式数据导出（Excel、CSV、JSON、PDF）与导出批次管理
- ✅ AI 对话（数据看板分析对话 + 标准分类库 RAG-lite 问答）
- ✅ WebSocket 实时进度推送（清洗 / AI 提取 / AI 分类检测 / 结果填充）
- ✅ JWT 鉴权与用户 / 角色管理
- ✅ Web 工作台界面（数据看板、分步清洗、标准库管理）

## 技术栈

| 层 | 技术 |
| --- | --- |
| 后端框架 | Spring Boot 2.7.18、Spring MVC、Spring AOP、Spring Cache、Spring WebSocket |
| 持久层 | MyBatis Plus 3.5.5（MySQL 8 / 达梦 8 双数据源） |
| 工具 | Apache POI 5.2.5（Excel）、Hutool 5.8.26、FastJSON 2.0.47 |
| 安全 | JWT（jjwt 0.12.5）、Spring Validation |
| AI 接入 | 通用 `RestTemplate` 调用 OpenAI / DeepSeek / 通义千问兼容的 Chat Completions 接口 |
| 前端 | 原生 HTML/JS（`src/main/resources/static/` 下 `index.html` + `app.js` + `css/`） |
| 文档 | springdoc-openapi 1.6.15（Swagger/OpenAPI 3.0） |
| 数据库 | MySQL 8.0（开发）/ 达梦数据库 8（生产） |

> 环境要求：**Java 8+**、Maven 3.6+。

## 快速开始

### 1. 环境要求

- Java 8+
- Maven 3.6+
- MySQL 8.0+ 或 达梦数据库 8+

### 2. 数据库设置

执行 `sql/` 目录下的初始化脚本：

- `sql/setup-mysql.sql` — MySQL 完整表结构
- `sql/setup-dameng.sql` — 达梦数据库完整表结构
- `sql/setup-simple.sql` — 精简版（仅核心表，便于快速体验）
- `sql/auth-module.sql` — 用户与登录日志表（鉴权模块）
- `sql/init-standard-title.sql` — 标准表头初始化
- `sql/upgrade-category-match.sql` — 分类同义词表升级

```sql
-- MySQL 示例
CREATE DATABASE aiclean CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
-- 然后执行 sql/setup-mysql.sql 与 sql/auth-module.sql
```

#### 达梦数据库驱动

达梦驱动默认已在 `pom.xml` 中引用（`com.dameng:DmJdbcDriver18:8.1.1.193`）。若需手动安装本地 JAR：

```bash
mvn install:install-file \
  -Dfile=DmJdbcDriver18.jar \
  -DgroupId=com.dameng \
  -DartifactId=DmJdbcDriver18 \
  -Dversion=8.1.1.193 \
  -Dpackaging=jar
```

### 3. 配置修改

编辑 `src/main/resources/application.yml`：

```yaml
spring:
  datasource:
    # MySQL 配置（默认）
    driver-class-name: com.mysql.cj.jdbc.Driver
    url: jdbc:mysql://localhost:3306/aiclean?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false
    username: your_username
    password: your_password
    # 达梦配置（取消注释并替换）
    # driver-class-name: dm.jdbc.driver.DmDriver
    # url: jdbc:dm://localhost:5236?schema=AI_CLEAN&charset=UTF-8&timezone=Asia/Shanghai&compatibleMode=oracle
    # username: SYSDBA
    # password: your_password
```

AI 接入（`app.ai` 段）示例：

```yaml
app:
  ai:
    enabled: true
    base-url: https://api.deepseek.com/v1   # 或通义千问等兼容端点
    api-key: "your-api-key"
    model: deepseek-chat
    temperature: 0.2
    max-tokens: 2048
```

> 不配置 AI 时 `app.ai.enabled=false`，系统自动以**规则模式**运行，所有功能仍可用。

### 4. 构建和运行

```bash
# 构建项目
mvn clean package

# 运行应用
java -jar target/data-clean-system-1.0.0.jar

# 或使用 Maven 直接运行
mvn spring-boot:run

# 或使用项目提供的启动脚本
./start.sh      # Linux / macOS
start.bat       # Windows
```

### 5. 访问应用

- 应用主页: http://localhost:8080
- Swagger API 文档: http://localhost:8080/swagger-ui.html
- API JSON: http://localhost:8080/v3/api-docs
- 健康检查: http://localhost:8080/api/system/health

工作台页面（无需登录即可浏览，关键操作需 JWT）：

- 数据看板 / 数据导入 / 一键清洗 / 数据检索
- 分步数据清洗：智能分类、属性提取、属性补全、清洗结果、无效数据
- 解析规则、标准分类库管理、用户管理（admin）

## API 接口

完整接口以 Swagger 文档为准，主要模块如下：

### 认证与用户 (`/api/auth/**`, `/api/users/**`)
- `POST /api/auth/login` — 登录获取 JWT
- `POST /api/auth/logout` — 登出
- `GET  /api/auth/current` — 当前用户
- `POST /api/auth/change-password` — 修改密码
- `GET  /api/users` — 用户列表
- `PUT  /api/users/{id}` / `DELETE /api/users/{id}` — 用户维护
- `POST /api/users/{id}/reset-password` — 重置密码

### 数据导入 (`/api/import/**`)
- `POST /api/import/upload` — 上传 Excel
- `GET  /api/import/titles` — 导入任务列表
- `PUT  /api/import/title/{id}/category-col` — 设置分类列
- `PUT  /api/import/title/{id}/full-desc-col` — 设置全描述列
- `DELETE /api/import/title/{id}` — 删除导入

### 数据清洗 (`/api/cleaning/**`)
- `POST /api/cleaning/start` — 启动批量清洗（`useAi` 控制是否启用 AI）
- `GET  /api/cleaning/progress/{titleId}` — 清洗进度
- `POST /api/cleaning/stop/{titleId}` — 停止清洗
- `POST /api/cleaning/reclean/{cleanedDataId}` — 重新清洗
- `POST /api/cleaning/match` — 单条分类匹配与清洗
- `POST /api/cleaning/parse-rule` — 创建全描述解析规则
- `GET  /api/cleaning/parse-rules/active` — 启用中的解析规则
- `POST /api/cleaning/extract-extra-ai` — AI 属性提取（异步）
- `GET  /api/cleaning/ai-extract-progress/{titleId}` — AI 提取进度
- `POST /api/cleaning/ai-classify-check` — AI 辅助分类检测（同步）
- `POST /api/cleaning/ai-classify-check-async` — AI 辅助分类检测（异步 + WebSocket）
- `POST /api/cleaning/classify-text` — 文本分类识别
- `POST /api/cleaning/apply-classify-fix` — 应用单条分类修正
- `POST /api/cleaning/apply-classify-fix-batch` — 批量应用分类修正
- `POST /api/cleaning/auto-map-fields` — 自动映射字段
- `POST /api/cleaning/fill-result/start` — 异步填充结果数据

### 分类管理 (`/api/categories/**`)
- `GET  /api/categories/tree` — 分类树
- `GET  /api/categories/{id}` — 分类详情
- `GET  /api/categories/{parentId}/children` — 子分类
- `PUT  /api/categories/{id}/move` — 移动分类
- `GET  /api/categories/search` — 分类检索
- `GET  /api/categories/validate-code` — 编码校验
- `GET  /api/categories/export` — 导出分类

### 清洗数据 (`/api/cleaned-data/**`)
- `POST /api/cleaned-data/search` — 多条件查询
- `GET  /api/cleaned-data/statistics` — 数据统计
- `GET  /api/cleaned-data/quality-report` — 质量报告

### 审核任务 (`/api/review/**`)
- `POST /api/review/tasks` — 创建审核任务
- `POST /api/review/tasks/batch` — 批量创建
- `GET  /api/review/tasks/my-pending` — 我的待办
- `PUT  /api/review/tasks/{id}/assign` — 分配
- `PUT  /api/review/tasks/{id}/start` — 开始处理
- `PUT  /api/review/tasks/{id}/complete` — 完成
- `PUT  /api/review/tasks/{id}/cancel` — 取消
- `GET  /api/review/statistics` — 审核统计
- `GET  /api/review/tasks/overdue` — 超时任务

### 数据导出 (`/api/export/**`)
- `POST /api/export/by-categories` — 按分类创建导出批次
- `POST /api/export/execute/{batchId}` — 执行导出
- `POST /api/export/execute-async/{batchId}` — 异步执行导出
- `GET  /api/export/download/{batchId}` — 下载导出文件
- `GET  /api/export/batches/{batchId}` — 批次详情
- `GET  /api/export/progress/{batchId}` — 导出进度
- `GET  /api/export/statistics` — 导出统计

### AI 对话 (`/api/ai/**`)
- `POST /api/ai/chat` — 看板分析对话
- `GET  /api/ai/chat-enabled` — 探测 AI 是否可用
- `POST /api/ai/category-chat` — 标准分类库 RAG-lite 问答

### 系统管理 (`/api/system/**`)
- `GET /api/system/health` — 健康检查
- `GET /api/system/time` — 服务器时间
- `GET /api/system/check-update` — 更新检测
- `GET /api/system/help` — 帮助信息

> WebSocket 主题：`/topic/cleaning/{titleId}`、`/topic/ai-classify-check/{titleId}`、`/topic/ai-extract/{titleId}`、`/topic/fill-result/{titleId}`，用于实时推送任务进度。

## 数据库表结构

核心表（详见 `sql/`）：

| 表 | 说明 |
| --- | --- |
| `temp_data_title` | 导入任务 / 表头映射 |
| `temp_data` | 原始数据（导入的 Excel 行） |
| `parse_rule` | 全描述解析规则 |
| `extra_data_title` / `extra_data` | 扩展数据表头 / AI 提取的属性值 |
| `main_data_category` | 主数据分类表（三级层级结构） |
| `category_synonym` | 分类同义词表 |
| `standard_field_definition` | 标准字段定义 |
| `standard_title` | 标准表头 |
| `title_standard_title` | 导入任务与标准表头关联 |
| `result_data` / `failed_result_data` | 结果数据 / 失败结果数据 |
| `cleaned_data` | 清洗后数据 |
| `field_mapping_audit` | 字段映射审核 |
| `review_task` | 审核任务 |
| `export_batch` | 导出批次 |
| `sys_user` / `sys_login_log` | 系统用户 / 登录日志 |

## 开发指南

### 项目结构

```
src/main/java/com/aiclean/
├── controller/        # API 控制器（11 个）
├── service/           # 业务服务接口
│   └── impl/          # 服务实现（清洗主流程、AI 识别/辅助识别）
├── mapper/            # MyBatis Plus 数据访问层
├── entity/            # 数据实体
├── dto/ / vo/         # 数据传输对象 / 视图对象
├── model/            # 解析规则等模型（ParseRule）
├── match/            # 分类匹配算法（与业务解耦，可插拔）
├── ai/               # 通用大模型客户端（AiClientService）
├── repository/       # 仓储
├── handler/          # 自定义 TypeHandler（达梦兼容）
├── config/           # 配置类（WebSocket、JWT、MyBatis 等）
├── common/           # 公共类（统一返回 R、异常等）
└── utils/            # 工具类
```

### 数据清洗主流程

```
Excel 上传 → temp_data / temp_data_title
   ├─ 全描述解析：ParseRule.parse(全描述列) → 键值对
   ├─ AI 属性提取（可选）：按分类编码找标准字段 → 大模型拆分 → extra_data
   ├─ 分类匹配：HierarchicalCategoryMatcher.match(ctx) → 三级 CategoryEntity + 来源 + 置信度
   ├─ 字段填充：materialCode / materialName / specification ... → CleanedDataEntity
   ├─ 质量评分：computeQualityScore（AI 模式 / 规则模式二选一）
   ├─ 状态判定：qualityScore < 60 → NEEDS_REVIEW；≥80 → EXPORT_READY；否则 APPROVED
   └─ 审核任务：评分过低自动建 ReviewTask → 人工审核 → 按分类导出
```

清洗任务与 AI 检测均通过 `@Async` + `TransactionTemplate` 异步执行，并通过 WebSocket 实时推送进度。

### 可扩展点

1. **分类算法替换**：实现 `match.CategoryMatcher` 接口并声明为 Spring Bean 即可替换。
2. **相似度升级**：实现 `match.SimilarityStrategy` 接口（默认 bigram-Jaccard），无需改动匹配器。
3. **大模型切换**：修改 `app.ai` 配置即可在 OpenAI / DeepSeek / 通义千问等兼容端点间切换。
4. **标准库热更新**：调用 `CategoryStandardLibrary.reload()` 即可重建内存索引，无需重启。
5. **AI 流程解耦**：关闭 `app.ai.enabled` 后系统完全以规则模式运行，保证可用性。

### 测试

```bash
mvn test
mvn test -Dtest=DataCleaningServiceTest
```

## 部署指南

### 生产环境配置

1. 修改 `application.yml` 切换到达梦数据源
2. 配置 HikariCP 连接池参数（`spring.datasource.hikari.*`）
3. 设置文件上传大小限制（`spring.servlet.multipart.max-file-size`）
4. 配置日志路径与级别（`logging.file.name`）
5. 替换 JWT 签名密钥（`app.security.jwt.secret`，长度 ≥ 32 字节）
6. 关闭 `debug: true` 与 `management` 调试端点

### Docker 部署

```dockerfile
FROM openjdk:8-jdk-slim
COPY target/data-clean-system-1.0.0.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
```

```bash
docker build -t ai-clean-system .
docker run -p 8080:8080 ai-clean-system
```

## 故障排除

### 常见问题

1. **数据库连接失败**
   - 检查数据库服务是否运行、用户名密码是否正确、网络是否可达
2. **文件上传失败**
   - 检查文件大小限制（默认 10MB）、文件格式（`.xlsx` / `.xls`）、磁盘空间
3. **AI 不可用 / 调用超时**
   - 确认 `app.ai.enabled`、`base-url`、`api-key`、`model` 配置正确
   - 连接超时 15s / 读取超时 120s；缺配置时系统自动降级到规则模式
4. **内存不足**
   - 增加 JVM 堆内存：`-Xmx2g -Xms1g`
   - 调整 HikariCP 连接池大小
5. **达梦驱动问题**
   - 确认驱动版本与数据库版本匹配、JAR 路径正确、Maven 本地仓库已安装

### 日志查看

```bash
tail -f logs/ai-clean.log
java -jar target/data-clean-system-1.0.0.jar --debug
```

## 贡献指南

1. Fork 项目
2. 创建功能分支 (`git checkout -b feature/AmazingFeature`)
3. 提交更改 (`git commit -m 'Add some AmazingFeature'`)
4. 推送到分支 (`git push origin feature/AmazingFeature`)
5. 开启 Pull Request

## 许可证

Apache License 2.0

## 支持

- 架构方案: `docs/ARCHITECTURE.md`
- 问题: [Issue Tracker](https://github.com/your-repo/issues)
- 邮箱: support@aiclean.com
