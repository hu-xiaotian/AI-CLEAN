-- ============================================================
-- MySQL 升级脚本：为 temp_data_title 表补充文件级清洗开始/结束时间字段
-- 适用场景：已存在的 MySQL 库（初次建库用的是 setup-mysql.sql 的老版本，
--           缺少 clean_start_time / clean_end_time 两列）。
-- 执行方式（任选其一）：
--   1) 命令行：mysql -u<用户> -p<密码> <库名> < sql/upgrade-clean-time-title-mysql.sql
--   2) 任意 MySQL 客户端直接执行本文件内容。
-- 说明：列若不存在才添加；可重复执行，无副作用。
-- ============================================================

SET @db = DATABASE();

-- 添加 clean_start_time 列（文件级清洗开始时间）
SET @sql = CONCAT(
    'ALTER TABLE temp_data_title ADD COLUMN clean_start_time TIMESTAMP NULL DEFAULT NULL COMMENT ''文件级清洗开始时间'''
);
SET @col_exists = (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'temp_data_title' AND COLUMN_NAME = 'clean_start_time'
);
SET @stmt = IF(@col_exists = 0, @sql, 'SELECT ''clean_start_time already exists'' AS msg');
PREPARE s FROM @stmt;
EXECUTE s;
DEALLOCATE PREPARE s;

-- 添加 clean_end_time 列（文件级清洗结束时间）
SET @sql = CONCAT(
    'ALTER TABLE temp_data_title ADD COLUMN clean_end_time TIMESTAMP NULL DEFAULT NULL COMMENT ''文件级清洗结束时间'''
);
SET @col_exists = (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'temp_data_title' AND COLUMN_NAME = 'clean_end_time'
);
SET @stmt = IF(@col_exists = 0, @sql, 'SELECT ''clean_end_time already exists'' AS msg');
PREPARE s FROM @stmt;
EXECUTE s;
DEALLOCATE PREPARE s;
