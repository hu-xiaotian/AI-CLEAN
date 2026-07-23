-- ============================================================
-- 长期优化升级脚本：AI 打分改用属性拆分列 + 数据血缘/去重 + 主动学习样本
-- 适用：已存在数据库（cleaned_data / main_data_category 已存在）
-- 仅执行 MySQL 版本（生产若用达梦，请将语法改为达梦兼容）
-- ============================================================

-- 1) cleaned_data 增加字段
ALTER TABLE cleaned_data ADD COLUMN `full_description` TEXT COMMENT '导入时指定的"属性拆分列"原始文本，用于 AI 打分匹配';
ALTER TABLE cleaned_data ADD COLUMN `source_row_hash` VARCHAR(64) DEFAULT NULL COMMENT '原始行数据指纹（数据血缘/去重/增量清洗）';
ALTER TABLE cleaned_data ADD COLUMN `is_duplicate` TINYINT DEFAULT 0 COMMENT '是否重复数据（同文件内指纹相同）: 0-否,1-是';

CREATE INDEX `idx_cd_source_row_hash` ON `cleaned_data`(`source_row_hash`);
CREATE INDEX `idx_cd_is_duplicate` ON `cleaned_data`(`is_duplicate`);

-- 2) 主动学习样本表
DROP TABLE IF EXISTS `active_learning_sample`;
CREATE TABLE `active_learning_sample` (
                                  `id` BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
                                  `sample_type` VARCHAR(50) NOT NULL COMMENT '样本类型: LOW_CONFIDENCE(低置信)/CORRECTION(人工修正)',
                                  `entity_id` BIGINT DEFAULT NULL COMMENT '关联清洗数据ID(cleaned_data.id)',
                                  `source_text` TEXT COMMENT '原始属性拆分列文本/物料描述',
                                  `source_category_name` VARCHAR(500) DEFAULT NULL COMMENT '原始(错误)分类名称',
                                  `source_category_code` VARCHAR(50) DEFAULT NULL COMMENT '原始(错误)分类编码',
                                  `target_category_id` BIGINT DEFAULT NULL COMMENT '修正后/推荐的标准分类ID',
                                  `target_category_code` VARCHAR(50) DEFAULT NULL COMMENT '修正后/推荐的标准分类编码',
                                  `target_category_name` VARCHAR(500) DEFAULT NULL COMMENT '修正后/推荐的标准分类名称',
                                  `confidence` DOUBLE DEFAULT NULL COMMENT '匹配置信度',
                                  `score` DOUBLE DEFAULT NULL COMMENT '质量评分',
                                  `reason` VARCHAR(500) DEFAULT NULL COMMENT '说明',
                                  `status` VARCHAR(50) DEFAULT 'pending' COMMENT '状态: pending/used',
                                  `created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                                  `updated_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
                                  `created_by` VARCHAR(50) DEFAULT 'system' COMMENT '创建人',
                                  `updated_by` VARCHAR(50) DEFAULT 'system' COMMENT '更新人'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='主动学习样本表';

CREATE INDEX `idx_als_type` ON `active_learning_sample`(`sample_type`);
CREATE INDEX `idx_als_entity_id` ON `active_learning_sample`(`entity_id`);
CREATE INDEX `idx_als_target_code` ON `active_learning_sample`(`target_category_code`);

SELECT '长期优化升级完成!' AS `message`;
