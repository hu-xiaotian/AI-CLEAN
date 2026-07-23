-- ============================================================
-- MySQL 升级脚本：为 cleaned_data 表补充分类匹配来源/置信度字段
-- 适用场景：已存在的 MySQL 库（初次建库用的是 setup-mysql.sql 的老版本，
--           缺少 match_source / match_confidence 两列）。
-- 执行方式（任选其一）：
--   1) 命令行：mysql -u<用户> -p<密码> <库名> < sql/upgrade-match-source-mysql.sql
--   2) 任意 MySQL 客户端直接执行本文件内容。
-- 说明：列若不存在才添加；可重复执行，无副作用。
-- ============================================================

SET @db = DATABASE();

-- 添加 match_source 列
SET @sql = CONCAT(
    'ALTER TABLE cleaned_data ADD COLUMN match_source VARCHAR(50) DEFAULT NULL COMMENT ''分类匹配来源(SYNONYM/NAME_EXACT/NAME_FUZZY/CODE_EXACT/CODE_PREFIX/EXTRA_NAME/SEMANTIC/UNMATCHED)'''
);
SET @col_exists = (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'cleaned_data' AND COLUMN_NAME = 'match_source'
);
SET @stmt = IF(@col_exists = 0, @sql, 'SELECT ''match_source already exists'' AS msg');
PREPARE s FROM @stmt;
EXECUTE s;
DEALLOCATE PREPARE s;

-- 添加 match_confidence 列
SET @sql = CONCAT(
    'ALTER TABLE cleaned_data ADD COLUMN match_confidence DOUBLE DEFAULT NULL COMMENT ''分类匹配置信度(0~1)'''
);
SET @col_exists = (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'cleaned_data' AND COLUMN_NAME = 'match_confidence'
);
SET @stmt = IF(@col_exists = 0, @sql, 'SELECT ''match_confidence already exists'' AS msg');
PREPARE s FROM @stmt;
EXECUTE s;
DEALLOCATE PREPARE s;

-- 补充索引
SET @idx_exists = (
    SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'cleaned_data' AND INDEX_NAME = 'idx_cd_match_source'
);
SET @stmt = IF(@idx_exists = 0,
    'CREATE INDEX idx_cd_match_source ON cleaned_data(match_source)',
    'SELECT ''idx_cd_match_source already exists'' AS msg');
PREPARE s FROM @stmt;
EXECUTE s;
DEALLOCATE PREPARE s;
