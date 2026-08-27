-- 升级脚本：为 extra_data_title 增加 AI 提取元数据字段
-- 适用已部署的 MySQL 数据库（与 setup-mysql.sql 的新建表结构保持一致）

ALTER TABLE `extra_data_title`
    ADD COLUMN IF NOT EXISTS `extract_status` VARCHAR(20) DEFAULT NULL COMMENT '提取状态 RUNNING/SUCCESS/PARTIAL/FAILED',
    ADD COLUMN IF NOT EXISTS `extract_start_time` DATETIME DEFAULT NULL COMMENT '提取开始时间',
    ADD COLUMN IF NOT EXISTS `extract_end_time` DATETIME DEFAULT NULL COMMENT '提取结束时间',
    ADD COLUMN IF NOT EXISTS `extract_cost_ms` BIGINT DEFAULT NULL COMMENT '提取耗时(毫秒)',
    ADD COLUMN IF NOT EXISTS `row_count` INT DEFAULT NULL COMMENT '提取行数';
