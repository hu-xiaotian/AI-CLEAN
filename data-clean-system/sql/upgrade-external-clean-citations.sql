-- ============================================================
-- 外部清洗回调 v1.3 新增字段升级脚本
-- 对应 api-design.md 6.1 章节：
--   results[].category_citations  分类判定依据的引用片段
--   results[].attr_citations      属性提取的引用片段
--   stats.classified_rows         已分类行数
--   stats.confidence_sum          置信度累加值
-- 说明：已存在同名列时请忽略报错，或先注释掉对应语句
-- ============================================================

-- ---------------------- MySQL ----------------------
ALTER TABLE `external_clean_task`
    ADD COLUMN `classified_rows` INT DEFAULT 0 COMMENT '已分类行数' AFTER `total_rows`,
    ADD COLUMN `confidence_sum` DOUBLE DEFAULT 0 COMMENT '置信度累加值(用于计算平均置信度)' AFTER `low_confidence`;

ALTER TABLE `external_clean_task_row`
    ADD COLUMN `category_citations_json` TEXT COMMENT '分类判定依据的引用片段(JSON数组)' AFTER `review_reason`,
    ADD COLUMN `attr_citations_json` TEXT COMMENT '属性提取的引用片段(JSON对象: 属性名->引用)' AFTER `category_citations_json`;

-- ---------------------- 达梦 DM ----------------------
-- ALTER TABLE external_clean_task ADD classified_rows INT DEFAULT 0;
-- ALTER TABLE external_clean_task ADD confidence_sum DOUBLE DEFAULT 0;
-- COMMENT ON COLUMN external_clean_task.classified_rows IS '已分类行数';
-- COMMENT ON COLUMN external_clean_task.confidence_sum IS '置信度累加值(用于计算平均置信度)';
--
-- ALTER TABLE external_clean_task_row ADD category_citations_json CLOB;
-- ALTER TABLE external_clean_task_row ADD attr_citations_json CLOB;
-- COMMENT ON COLUMN external_clean_task_row.category_citations_json IS '分类判定依据的引用片段(JSON数组)';
-- COMMENT ON COLUMN external_clean_task_row.attr_citations_json IS '属性提取的引用片段(JSON对象: 属性名->引用)';
