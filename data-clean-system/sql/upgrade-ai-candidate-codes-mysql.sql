-- ============================================================
-- MySQL 升级脚本：为 cleaned_data 表补充 AI 分类 top-k 候选字段
-- 适用场景：已存在的 MySQL 库（缺少 ai_candidate_codes 一列）。
-- 执行方式（任选其一）：
--   1) 命令行：mysql -u<用户> -p<密码> <库名> < sql/upgrade-ai-candidate-codes-mysql.sql
--   2) 任意 MySQL 客户端直接执行本文件内容。
-- 说明：列若不存在才添加；可重复执行，无副作用。
-- ============================================================

SET @db = DATABASE();

-- 添加 ai_candidate_codes 列（AI 分类命中的 top-k 候选分类 JSON，供人工复核/编辑分类参考）
SET @sql = CONCAT(
    'ALTER TABLE cleaned_data ADD COLUMN ai_candidate_codes TEXT COMMENT ''AI 分类命中的 top-k 候选分类 JSON（如 [{"code":"100101","name":"冷轧板材"}]），供人工复核/编辑分类参考'''
);
SET @col_exists = (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'cleaned_data' AND COLUMN_NAME = 'ai_candidate_codes'
);
SET @stmt = IF(@col_exists = 0, @sql, 'SELECT ''ai_candidate_codes already exists'' AS msg');
PREPARE s FROM @stmt;
EXECUTE s;
DEALLOCATE PREPARE s;
