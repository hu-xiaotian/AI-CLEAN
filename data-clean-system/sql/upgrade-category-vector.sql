-- ============================================================
-- 升级脚本：新增标准分类语义向量表 (category_vector)
-- 用途：把 main_data_category 每个标准分类的 Embedding 语义向量持久化到库，
--       供语义知识库（SemanticCategoryLibrary）按余弦相似度召回语义最接近的备选分类，
--       避免每次启动都重新调用 Embedding 接口向量化全表。
-- 适用：已部署的存量数据库（新库可在 setup-mysql.sql / setup-dameng.sql 中直接建表）。
-- 注意：需按实际数据库类型选择对应语句执行。
-- ============================================================

-- ========== 达梦数据库版本 ==========
-- CREATE TABLE category_vector (
--     id BIGINT IDENTITY(1,1) PRIMARY KEY,
--     category_id BIGINT NOT NULL,
--     category_code VARCHAR2(50),
--     vector_source CLOB,
--     embedding_model VARCHAR2(100),
--     dimension INT,
--     vector_text CLOB,
--     created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
--     updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
--     created_by VARCHAR2(50) DEFAULT 'system',
--     updated_by VARCHAR2(50) DEFAULT 'system'
-- );
-- CREATE UNIQUE INDEX uk_cv_category_id ON category_vector(category_id);

-- ========== MySQL 数据库版本 ==========
CREATE TABLE IF NOT EXISTS category_vector (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    category_id BIGINT NOT NULL COMMENT '关联标准分类ID(main_data_category.id)',
    category_code VARCHAR(50) DEFAULT NULL COMMENT '关联标准分类编码',
    vector_source TEXT COMMENT '向量化原始文本(分类名称+路径+说明+旧名称)',
    embedding_model VARCHAR(100) DEFAULT NULL COMMENT 'Embedding模型名称',
    dimension INT DEFAULT NULL COMMENT '向量维度',
    vector_text LONGTEXT COMMENT '语义向量(JSON数组字符串)',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    created_by VARCHAR(50) DEFAULT 'system' COMMENT '创建人',
    updated_by VARCHAR(50) DEFAULT 'system' COMMENT '更新人',
    UNIQUE KEY uk_cv_category_id (category_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='标准分类语义向量表';

-- 说明：
--   建表后无需手动插入数据。首次调用批次分类（AI 模式）时，
--   SemanticCategoryLibrary 会检测到表为空，自动调用 Embedding 接口对全表向量化并写入本表；
--   之后启动优先从本表加载向量，仅当标准分类变更或向量缺失时才重新向量化。
