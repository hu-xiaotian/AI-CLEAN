# AI Clean 数据清洗系统

基于Java Spring Boot的数据清洗系统，支持Excel导入、层级分类管理、人工审核工作流和数据导出功能。

## 功能特性

- ✅ Excel文件上传和解析
- ✅ 层级分类管理（支持10/1001/100101格式编码）
- ✅ 多条件数据查询和搜索
- ✅ 人工审核工作流
- ✅ 数据质量评分和验证
- ✅ 多格式数据导出（Excel、CSV、JSON、PDF）
- ✅ Web界面用于数据审核和编辑
- ✅ 数据状态管理和分类导出

## 技术栈

- **后端**: Spring Boot 3.2.5 + MyBatis Plus
- **数据库**: MySQL 8.0 (开发) / 达梦数据库 8 (生产)
- **前端**: Vue 3 (计划中)
- **工具**: Apache POI (Excel处理), Hutool, FastJSON
- **文档**: Swagger/OpenAPI 3.0

## 快速开始

### 1. 环境要求

- Java 17+
- Maven 3.6+
- MySQL 8.0+ 或 达梦数据库 8+

### 2. 数据库设置

#### 选项A: 使用MySQL (推荐用于开发)

```sql
-- 创建数据库
CREATE DATABASE aiclean CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

-- 创建用户 (可选)
CREATE USER 'aiclean_user'@'localhost' IDENTIFIED BY 'Aiclean123!';
GRANT ALL PRIVILEGES ON aiclean.* TO 'aiclean_user'@'localhost';
FLUSH PRIVILEGES;
```

#### 选项B: 使用达梦数据库

1. 下载达梦数据库JDBC驱动: [达梦官网](https://www.dameng.com/)
2. 安装驱动到本地Maven仓库:
```bash
mvn install:install-file \
  -Dfile=DmJdbcDriver18.jar \
  -DgroupId=com.dameng \
  -DartifactId=DmJdbcDriver \
  -Dversion=8.1.3.62 \
  -Dpackaging=jar
```

3. 修改 `pom.xml` 和 `application.yml` 使用达梦配置

### 3. 配置修改

编辑 `src/main/resources/application.yml`:

```yaml
spring:
  datasource:
    # MySQL配置
    driver-class-name: com.mysql.cj.jdbc.Driver
    url: jdbc:mysql://localhost:3306/aiclean?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false
    username: your_username
    password: your_password
    
    # 或达梦配置
    # driver-class-name: dm.jdbc.driver.DmDriver
    # url: jdbc:dm://localhost:5236/aiclean?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai
    # username: AICLEAN_USER
    # password: AICLEAN_PASSWORD123
```

### 4. 构建和运行

```bash
# 构建项目
mvn clean package

# 运行应用
java -jar target/data-clean-system-1.0.0.jar

# 或使用Maven直接运行
mvn spring-boot:run
```

### 5. 访问应用

- 应用主页: http://localhost:8080
- Swagger API文档: http://localhost:8080/swagger-ui.html
- 健康检查: http://localhost:8080/api/system/health

## API接口

### 数据导入模块 (`/api/import/**`)
- `POST /api/import/upload` - 上传Excel文件
- `GET /api/import/temp-data` - 获取临时数据列表
- `POST /api/import/clean/{batchId}` - 执行数据清洗

### 分类管理模块 (`/api/categories/**`)
- `POST /api/categories` - 创建分类
- `GET /api/categories/tree` - 获取分类树
- `PUT /api/categories/{id}/move` - 移动分类

### 清洗数据模块 (`/api/cleaned-data/**`)
- `POST /api/cleaned-data/query` - 多条件查询数据
- `PUT /api/cleaned-data/{id}/status` - 更新数据状态
- `GET /api/cleaned-data/statistics` - 获取数据统计

### 审核任务模块 (`/api/review/**`)
- `POST /api/review/tasks` - 创建审核任务
- `PUT /api/review/tasks/{id}/claim` - 领取审核任务
- `PUT /api/review/tasks/{id}/submit` - 提交审核结果

### 数据导出模块 (`/api/export/**`)
- `POST /api/export/by-categories` - 按分类导出数据
- `GET /api/export/batches` - 获取导出批次列表
- `GET /api/export/download/{batchId}` - 下载导出文件

### 系统管理模块 (`/api/system/**`)
- `GET /api/system/health` - 健康检查
- `GET /api/system/statistics` - 系统统计
- `GET /api/system/overview` - 数据概览

## 数据库表结构

系统包含以下核心表：

1. **temp_data** - 临时数据表
2. **temp_data_title** - 临时数据表头映射
3. **standard_field** - 标准字段定义
4. **main_data_category** - 主数据分类表（层级结构）
5. **cleaned_data** - 清洗后数据表
6. **field_mapping_audit** - 字段映射审核表
7. **review_task** - 审核任务表
8. **export_batch** - 导出批次表
9. **data_search_index** - 数据搜索索引表

## 开发指南

### 项目结构

```
src/main/java/com/aiclean/
├── controller/        # API控制器
├── service/          # 业务服务
│   └── impl/         # 服务实现
├── mapper/           # 数据访问层
├── entity/           # 数据实体
│   └── enums/        # 枚举类型
├── vo/               # 视图对象
├── common/           # 公共类
└── config/           # 配置类
```

### 添加新功能

1. 创建实体类在 `entity/` 目录
2. 创建Mapper接口在 `mapper/` 目录
3. 创建Service接口和实现
4. 创建Controller类
5. 创建VO类（如果需要）
6. 更新Swagger文档注解

### 测试

```bash
# 运行单元测试
mvn test

# 运行特定测试类
mvn test -Dtest=DataCleaningServiceTest
```

## 部署指南

### 生产环境配置

1. 修改 `application.yml` 中的数据库配置
2. 配置合适的连接池参数
3. 设置文件上传大小限制
4. 配置日志级别和路径
5. 启用安全认证（如JWT）

### Docker部署

```dockerfile
FROM openjdk:17-jdk-slim
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
   - 检查数据库服务是否运行
   - 验证用户名和密码
   - 检查网络连接

2. **文件上传失败**
   - 检查文件大小限制（默认10MB）
   - 验证文件格式（支持.xlsx, .xls）
   - 检查磁盘空间

3. **内存不足**
   - 增加JVM堆内存：`-Xmx2g -Xms1g`
   - 调整数据库连接池大小

4. **达梦驱动问题**
   - 确认驱动版本与数据库版本匹配
   - 检查驱动JAR文件路径
   - 验证Maven本地仓库安装

### 日志查看

```bash
# 查看应用日志
tail -f logs/ai-clean.log

# 查看启动日志
java -jar target/data-clean-system-1.0.0.jar --debug
```

## 贡献指南

1. Fork项目
2. 创建功能分支 (`git checkout -b feature/AmazingFeature`)
3. 提交更改 (`git commit -m 'Add some AmazingFeature'`)
4. 推送到分支 (`git push origin feature/AmazingFeature`)
5. 开启Pull Request

## 许可证

Apache License 2.0

## 支持

- 文档: [项目Wiki](https://github.com/your-repo/wiki)
- 问题: [Issue Tracker](https://github.com/your-repo/issues)
- 邮箱: support@aiclean.com