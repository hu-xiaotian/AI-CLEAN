-- ============================================
-- 外部数据清洗对接模块 - MySQL 初始化脚本
-- 模块完全独立，不与系统其他表产生外键耦合
-- 版本: 1.0.0
-- ============================================

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- 1. 外部清洗任务表 (external_clean_task)
DROP TABLE IF EXISTS `external_clean_task`;
CREATE TABLE `external_clean_task` (
    `id` BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    `task_id` VARCHAR(64) NOT NULL COMMENT '任务号 task-{YYYYMMDD}-{seq}，与外部服务交互唯一标识',
    `temp_data_title_id` BIGINT DEFAULT NULL COMMENT '来源数据文件ID（松引用，无外键）',
    `file_name` VARCHAR(200) DEFAULT NULL COMMENT '来源文件名快照',
    `mode` VARCHAR(10) DEFAULT 'async' COMMENT '提交模式: sync/async',
    `status` VARCHAR(30) DEFAULT 'pending' COMMENT '状态: pending/submitting/processing/completed/failed/cancelled/callback_timeout',
    `callback_url` VARCHAR(500) DEFAULT NULL COMMENT '下发给外部服务的回调地址',
    `total_rows` INT DEFAULT 0 COMMENT '总行数',
    `classified_rows` INT DEFAULT 0 COMMENT '已分类行数',
    `processed_rows` INT DEFAULT 0 COMMENT '已处理行数',
    `high_confidence` INT DEFAULT 0 COMMENT '高置信行数(>=0.9)',
    `medium_confidence` INT DEFAULT 0 COMMENT '中置信行数(0.7~0.9)',
    `low_confidence` INT DEFAULT 0 COMMENT '低置信行数(<0.7)',
    `confidence_sum` DOUBLE DEFAULT 0 COMMENT '置信度累加值(用于计算平均置信度)',
    `estimated_accuracy` DOUBLE DEFAULT 0 COMMENT '预估准确率',
    `options_json` TEXT COMMENT '提交时的清洗选项快照(CleanOptions JSON)',
    `submitted_at` TIMESTAMP NULL DEFAULT NULL COMMENT '提交外部服务时间',
    `completed_at` TIMESTAMP NULL DEFAULT NULL COMMENT '任务完成时间',
    `callback_received_at` TIMESTAMP NULL DEFAULT NULL COMMENT '收到回调时间（空则触发轮询兜底）',
    `error_message` VARCHAR(1000) DEFAULT NULL COMMENT '失败原因',
    `retry_count` INT DEFAULT 0 COMMENT '提交重试次数',
    `created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `created_by` VARCHAR(50) DEFAULT 'system' COMMENT '创建人',
    `updated_by` VARCHAR(50) DEFAULT 'system' COMMENT '更新人'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='外部清洗任务表';

CREATE UNIQUE INDEX `idx_ect_task_id` ON `external_clean_task`(`task_id`);
CREATE INDEX `idx_ect_status` ON `external_clean_task`(`status`);
CREATE INDEX `idx_ect_title_id` ON `external_clean_task`(`temp_data_title_id`);

-- 2. 外部清洗任务行表 (external_clean_task_row)
-- 保存提交快照 + 清洗结果 + 采纳/修正记录
DROP TABLE IF EXISTS `external_clean_task_row`;
CREATE TABLE `external_clean_task_row` (
    `id` BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    `task_id` VARCHAR(64) NOT NULL COMMENT '关联任务号',
    `row_index` INT NOT NULL COMMENT '行号，对应 RawRow.index',
    `temp_data_id` BIGINT DEFAULT NULL COMMENT '源数据行ID（松引用，无外键）',
    `request_columns_json` TEXT COMMENT '提交的原始列数据快照(JSON: 列名->列值)',
    -- 清洗结果（CleanResult）
    `category_code` VARCHAR(50) DEFAULT NULL COMMENT '匹配分类编码',
    `category_name` VARCHAR(200) DEFAULT NULL COMMENT '分类中文名',
    `category_path` VARCHAR(500) DEFAULT NULL COMMENT '分类完整路径',
    `confidence` DOUBLE DEFAULT NULL COMMENT '综合置信度 0~1',
    `category_confidence` DOUBLE DEFAULT NULL COMMENT '分类环节置信度 0~1',
    `extracted_attrs_json` TEXT COMMENT '提取的属性键值对(JSON)',
    `missing_attrs_json` VARCHAR(1000) DEFAULT NULL COMMENT '缺失必填属性列表(JSON数组)',
    `needs_review` TINYINT DEFAULT 0 COMMENT '是否需人工复核: 0-否,1-是',
    `review_reason` VARCHAR(500) DEFAULT NULL COMMENT '复核原因',
    `category_citations_json` TEXT COMMENT '分类判定依据的引用片段(JSON数组)',
    `attr_citations_json` TEXT COMMENT '属性提取的引用片段(JSON对象: 属性名->引用)',
    -- 行状态与采纳/修正
    `row_status` VARCHAR(30) DEFAULT 'pending' COMMENT '行状态: pending/completed/skipped/accepted/corrected/rejected',
    `corrected_category_code` VARCHAR(50) DEFAULT NULL COMMENT '人工修正后的分类编码',
    `corrected_category_name` VARCHAR(200) DEFAULT NULL COMMENT '人工修正后的分类名称',
    `corrected_attrs_json` TEXT COMMENT '人工修正后的属性键值对(JSON)',
    `correct_comment` VARCHAR(500) DEFAULT NULL COMMENT '修正/采纳备注',
    `operated_by` VARCHAR(50) DEFAULT NULL COMMENT '采纳/修正操作人',
    `operated_at` TIMESTAMP NULL DEFAULT NULL COMMENT '采纳/修正时间',
    `created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `created_by` VARCHAR(50) DEFAULT 'system' COMMENT '创建人',
    `updated_by` VARCHAR(50) DEFAULT 'system' COMMENT '更新人'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='外部清洗任务行表（请求快照+结果+采纳修正）';

CREATE UNIQUE INDEX `idx_ecr_task_row` ON `external_clean_task_row`(`task_id`, `row_index`);
CREATE INDEX `idx_ecr_task_id` ON `external_clean_task_row`(`task_id`);
CREATE INDEX `idx_ecr_needs_review` ON `external_clean_task_row`(`needs_review`);
CREATE INDEX `idx_ecr_row_status` ON `external_clean_task_row`(`row_status`);

-- 3. 外部清洗回调日志表 (external_clean_callback_log)
-- 记录每次回调，payload_digest 用于幂等去重
DROP TABLE IF EXISTS `external_clean_callback_log`;
CREATE TABLE `external_clean_callback_log` (
    `id` BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    `task_id` VARCHAR(64) NOT NULL COMMENT '任务号',
    `callback_status` VARCHAR(30) DEFAULT NULL COMMENT '回调携带的任务状态',
    `page_no` INT DEFAULT NULL COMMENT '分页回调页码（回调体>10MB分页场景）',
    `page_size` INT DEFAULT NULL COMMENT '分页回调页大小',
    `payload_digest` VARCHAR(64) DEFAULT NULL COMMENT '回调体SHA-256摘要（幂等去重）',
    `payload_snapshot` LONGTEXT COMMENT '原始回调报文快照',
    `process_result` VARCHAR(20) DEFAULT NULL COMMENT '处理结果: success/duplicate/invalid/error',
    `error_message` VARCHAR(1000) DEFAULT NULL COMMENT '处理异常信息',
    `received_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '接收时间',
    `created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `created_by` VARCHAR(50) DEFAULT 'system' COMMENT '创建人',
    `updated_by` VARCHAR(50) DEFAULT 'system' COMMENT '更新人'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='外部清洗回调日志表';

CREATE INDEX `idx_ecl_task_id` ON `external_clean_callback_log`(`task_id`);
CREATE INDEX `idx_ecl_digest` ON `external_clean_callback_log`(`payload_digest`);

SET FOREIGN_KEY_CHECKS = 1;

SELECT '外部清洗模块 MySQL 初始化完成!' AS `message`;
