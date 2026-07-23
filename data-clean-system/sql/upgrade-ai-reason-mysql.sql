-- ============================================================
-- MySQL 升级脚本：为 cleaned_data 表补充 AI 辅助分类理由字段
-- 适用场景：已存在的 MySQL 库（初次建库用的是 setup-mysql.sql 的老版本，
--           缺少 ai_reason 一列）。
-- 执行方式（任选其一）：
--   1) 命令行：mysql -u<用户> -p<密码> <库名> < sql/upgrade-ai-reason-mysql.sql
--   2) 任意 MySQL 客户端直接执行本文件内容。
-- 说明：列若不存在才添加；可重复执行，无副作用。
-- ============================================================

SET @db = DATABASE();

-- 添加 ai_reason 列（AI 辅助分类理由描述，启用 AI 辅助评分时记录）
SET @sql = CONCAT(
    'ALTER TABLE cleaned_data ADD COLUMN ai_reason TEXT COMMENT ''AI 辅助分类理由描述（启用 AI 辅助评分时记录）'''
);
SET @col_exists = (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'cleaned_data' AND COLUMN_NAME = 'ai_reason'
);
SET @stmt = IF(@col_exists = 0, @sql, 'SELECT ''ai_reason already exists'' AS msg');
PREPARE s FROM @stmt;
EXECUTE s;
DEALLOCATE PREPARE s;
