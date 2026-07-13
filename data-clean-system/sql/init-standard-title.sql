-- ============================================
-- AI Clean - 标准字段表头初始化脚本
-- 适用数据库: 达梦(DM8)
-- ============================================

-- 1. 创建标准字段表头表 (达梦支持 IF NOT EXISTS)
CREATE TABLE IF NOT EXISTS standard_title (
    id BIGINT IDENTITY(1,1) PRIMARY KEY,
    category_code VARCHAR2(50) NOT NULL,
    col_title_1 VARCHAR2(200),
    col_title_2 VARCHAR2(200),
    col_title_3 VARCHAR2(200),
    col_title_4 VARCHAR2(200),
    col_title_5 VARCHAR2(200),
    col_title_6 VARCHAR2(200),
    col_title_7 VARCHAR2(200),
    col_title_8 VARCHAR2(200),
    col_title_9 VARCHAR2(200),
    col_title_10 VARCHAR2(200),
    col_title_11 VARCHAR2(200),
    col_title_12 VARCHAR2(200),
    col_title_13 VARCHAR2(200),
    col_title_14 VARCHAR2(200),
    col_title_15 VARCHAR2(200),
    col_title_16 VARCHAR2(200),
    col_title_17 VARCHAR2(200),
    col_title_18 VARCHAR2(200),
    col_title_19 VARCHAR2(200),
    col_title_20 VARCHAR2(200),
    col_title_1_is_must TINYINT DEFAULT 0,
    col_title_2_is_must TINYINT DEFAULT 0,
    col_title_3_is_must TINYINT DEFAULT 0,
    col_title_4_is_must TINYINT DEFAULT 0,
    col_title_5_is_must TINYINT DEFAULT 0,
    col_title_6_is_must TINYINT DEFAULT 0,
    col_title_7_is_must TINYINT DEFAULT 0,
    col_title_8_is_must TINYINT DEFAULT 0,
    col_title_9_is_must TINYINT DEFAULT 0,
    col_title_10_is_must TINYINT DEFAULT 0,
    col_title_11_is_must TINYINT DEFAULT 0,
    col_title_12_is_must TINYINT DEFAULT 0,
    col_title_13_is_must TINYINT DEFAULT 0,
    col_title_14_is_must TINYINT DEFAULT 0,
    col_title_15_is_must TINYINT DEFAULT 0,
    col_title_16_is_must TINYINT DEFAULT 0,
    col_title_17_is_must TINYINT DEFAULT 0,
    col_title_18_is_must TINYINT DEFAULT 0,
    col_title_19_is_must TINYINT DEFAULT 0,
    col_title_20_is_must TINYINT DEFAULT 0,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR2(50) DEFAULT 'system',
    updated_by VARCHAR2(50) DEFAULT 'system'
);

-- 2. 插入标准字段表头数据 (先删后插，避免重复)
DELETE FROM standard_title WHERE category_code = '10';

INSERT INTO standard_title (category_code,
    col_title_1, col_title_2, col_title_3, col_title_4, col_title_5,
    col_title_6, col_title_7, col_title_8, col_title_9, col_title_10,
    col_title_1_is_must, col_title_2_is_must, col_title_3_is_must, col_title_4_is_must, col_title_5_is_must)
VALUES ('10',
    '物料代码', '物料名称', '规格型号', '技术标准号', '牌号',
    '计量单位', '加工工艺', '热处理工艺', '特殊要求', '规格标准号',
    1, 1, 0, 0, 0);

COMMIT;

-- 3. 验证
SELECT COUNT(*) AS "标准表头数量" FROM standard_title;
SELECT * FROM standard_title;
