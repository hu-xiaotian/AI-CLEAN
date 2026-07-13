-- ============================================================
-- 分类匹配增强升级脚本（同义词表 + 匹配来源/置信度字段）
-- 适用：已存在的数据库（cleaned_data 表已存在）
-- 根据所用数据库类型，仅执行对应版本（生产为达梦）
-- ============================================================

-- ========== 达梦数据库版本 ==========

-- 0) 列名修正（synonym 是达梦保留字，已将列改名为 synonym_name）
--    若旧表已用引号建了 "synonym" 列，执行以下改名；新建库无需执行。
-- ALTER TABLE category_synonym RENAME COLUMN "synonym" TO synonym_name;

ALTER TABLE cleaned_data ADD COLUMN match_source VARCHAR2(50);
ALTER TABLE cleaned_data ADD COLUMN match_confidence DOUBLE;

CREATE TABLE category_synonym (
    id BIGINT IDENTITY(1,1) PRIMARY KEY,
    category_id BIGINT NOT NULL,
    synonym_name VARCHAR2(200) NOT NULL,
    synonym_norm VARCHAR2(200),
    description VARCHAR2(500),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR2(50) DEFAULT 'system',
    updated_by VARCHAR2(50) DEFAULT 'system'
);
CREATE INDEX idx_cs_category_id ON category_synonym(category_id);
CREATE INDEX idx_cs_synonym_norm ON category_synonym(synonym_norm);

-- ========== MySQL 数据库版本（如未使用达梦，请改用以下语句） ==========
-- ALTER TABLE cleaned_data ADD COLUMN match_source VARCHAR(50);
-- ALTER TABLE cleaned_data ADD COLUMN match_confidence DOUBLE;
-- CREATE TABLE category_synonym (
--     id BIGINT AUTO_INCREMENT PRIMARY KEY,
--     category_id BIGINT NOT NULL,
--     synonym_name VARCHAR(200) NOT NULL,
--     synonym_norm VARCHAR(200),
--     description VARCHAR(500),
--     created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
--     updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
--     created_by VARCHAR(50) DEFAULT 'system',
--     updated_by VARCHAR(50) DEFAULT 'system'
-- );
-- CREATE INDEX idx_cs_category_id ON category_synonym(category_id);
-- CREATE INDEX idx_cs_synonym_norm ON category_synonym(synonym_norm);

-- ============================================================
-- 同义词表示例数据（按需调整 category_id 指向真实分类）
-- 归一化规则与代码一致：去空格/标点/大小写，例如 "无缝管" -> "无缝管"
-- ============================================================
-- INSERT INTO category_synonym (category_id, synonym_name, synonym_norm, description) VALUES
-- (4, '无缝管', '无缝管', '无缝钢管的简称'),
-- (4, '焊管', '焊管', '焊接钢管的简称');
