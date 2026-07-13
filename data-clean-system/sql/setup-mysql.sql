-- ============================================
-- AI Clean 数据清洗系统 - MySQL数据库初始化脚本
-- 版本: 2.0.0
-- ============================================

-- 设置字符集
SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- 1. 原始数据表头表 (temp_data_title)
-- 存储上传文件的基本信息和列名映射
DROP TABLE IF EXISTS `temp_data_title`;
CREATE TABLE `temp_data_title` (
                                   `id` BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
                                   `file_name` VARCHAR(200) NOT NULL COMMENT '文件名',
                                   `upload_time` VARCHAR(50) DEFAULT NULL COMMENT '上传时间',
                                   `total_rows` INT DEFAULT 0 COMMENT '总行数',
                                   `status` VARCHAR(50) DEFAULT 'draft' COMMENT '状态: draft/pending/processing/completed',
                                   `col1_title` VARCHAR(200) DEFAULT NULL COMMENT '列1标题',
                                   `col2_title` VARCHAR(200) DEFAULT NULL,
                                   `col3_title` VARCHAR(200) DEFAULT NULL,
                                   `col4_title` VARCHAR(200) DEFAULT NULL,
                                   `col5_title` VARCHAR(200) DEFAULT NULL,
                                   `col6_title` VARCHAR(200) DEFAULT NULL,
                                   `col7_title` VARCHAR(200) DEFAULT NULL,
                                   `col8_title` VARCHAR(200) DEFAULT NULL,
                                   `col9_title` VARCHAR(200) DEFAULT NULL,
                                   `col10_title` VARCHAR(200) DEFAULT NULL,
                                   `full_desc_col` VARCHAR(100) DEFAULT NULL COMMENT '全描述列名',
                                   `category_col` VARCHAR(100) DEFAULT NULL COMMENT '分类列名',
                                   `created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                                   `updated_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
                                   `created_by` VARCHAR(50) DEFAULT 'system' COMMENT '创建人',
                                   `updated_by` VARCHAR(50) DEFAULT 'system' COMMENT '更新人'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='原始数据表头表';

CREATE INDEX `idx_tt_status` ON `temp_data_title`(`status`);

-- 2. 原始数据表 (temp_data)
-- 存储从Excel解析的每一行原始数据
DROP TABLE IF EXISTS `temp_data`;
CREATE TABLE `temp_data` (
                             `id` BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
                             `temp_data_title_id` BIGINT NOT NULL COMMENT '关联表头ID',
                             `row_index` INT NOT NULL COMMENT '行索引',
                             `col1` VARCHAR(200) DEFAULT NULL,
                             `col2` VARCHAR(200) DEFAULT NULL,
                             `col3` VARCHAR(200) DEFAULT NULL,
                             `col4` VARCHAR(200) DEFAULT NULL,
                             `col5` VARCHAR(200) DEFAULT NULL,
                             `col6` VARCHAR(200) DEFAULT NULL,
                             `col7` VARCHAR(200) DEFAULT NULL,
                             `col8` VARCHAR(200) DEFAULT NULL,
                             `col9` VARCHAR(200) DEFAULT NULL,
                             `col10` VARCHAR(200) DEFAULT NULL,
                             `status` VARCHAR(50) DEFAULT 'draft' COMMENT '状态',
                             `error_msg` VARCHAR(500) COMMENT '错误信息',
                             `created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                             `updated_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
                             `created_by` VARCHAR(50) DEFAULT 'system' COMMENT '创建人',
                             `updated_by` VARCHAR(50) DEFAULT 'system' COMMENT '更新人'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='原始数据表';

CREATE INDEX `idx_td_title_id` ON `temp_data`(`temp_data_title_id`);
CREATE INDEX `idx_td_status` ON `temp_data`(`status`);
CREATE INDEX `idx_td_row_index` ON `temp_data`(`row_index`);

-- 3. 解析规则表 (parse_rule)
-- 用户配置的全描述属性解析规则
DROP TABLE IF EXISTS `parse_rule`;
CREATE TABLE `parse_rule` (
                              `id` BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
                              `rule_name` VARCHAR(100) NOT NULL COMMENT '规则名称',
                              `description` VARCHAR(500) DEFAULT NULL COMMENT '规则描述',
                              `key_value_separator` VARCHAR(20) DEFAULT ' ' COMMENT '键值分隔符',
                              `item_separator` VARCHAR(20) DEFAULT ';' COMMENT '条目分隔符',
                              `escape_char` VARCHAR(10) DEFAULT '' COMMENT '转义字符',
                              `trim_spaces` TINYINT DEFAULT 1 COMMENT '是否去除空格: 0-否,1-是',
                              `ignore_empty_items` TINYINT DEFAULT 1 COMMENT '是否忽略空条目: 0-否,1-是',
                              `is_active` TINYINT DEFAULT 1 COMMENT '是否启用: 0-否,1-是',
                              `created_by` VARCHAR(50) DEFAULT NULL COMMENT '创建人',
                              `created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                              `updated_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
                              `updated_by` VARCHAR(50) DEFAULT NULL COMMENT '更新人'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='解析规则表';

CREATE INDEX `idx_pr_active` ON `parse_rule`(`is_active`);

-- 4. 补充数据表头表 (extra_data_title)
-- 存储从全描述中提取的额外属性列名
DROP TABLE IF EXISTS `extra_data_title`;
CREATE TABLE `extra_data_title` (
                                    `id` BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
                                    `temp_data_title_id` BIGINT NOT NULL COMMENT '关联原始表头ID',
                                    `parse_rule_id` BIGINT DEFAULT NULL COMMENT '使用的解析规则ID',
                                    `col1_title` VARCHAR(200) DEFAULT NULL,
                                    `col2_title` VARCHAR(200) DEFAULT NULL,
                                    `col3_title` VARCHAR(200) DEFAULT NULL,
                                    `col4_title` VARCHAR(200) DEFAULT NULL,
                                    `col5_title` VARCHAR(200) DEFAULT NULL,
                                    `col6_title` VARCHAR(200) DEFAULT NULL,
                                    `col7_title` VARCHAR(200) DEFAULT NULL,
                                    `col8_title` VARCHAR(200) DEFAULT NULL,
                                    `col9_title` VARCHAR(200) DEFAULT NULL,
                                    `col10_title` VARCHAR(200) DEFAULT NULL,
                                    `col11_title` VARCHAR(200) DEFAULT NULL,
                                    `col12_title` VARCHAR(200) DEFAULT NULL,
                                    `col13_title` VARCHAR(200) DEFAULT NULL,
                                    `col14_title` VARCHAR(200) DEFAULT NULL,
                                    `col15_title` VARCHAR(200) DEFAULT NULL,
                                    `col16_title` VARCHAR(200) DEFAULT NULL,
                                    `col17_title` VARCHAR(200) DEFAULT NULL,
                                    `col18_title` VARCHAR(200) DEFAULT NULL,
                                    `col19_title` VARCHAR(200) DEFAULT NULL,
                                    `col20_title` VARCHAR(200) DEFAULT NULL,
                                    `created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                                    `updated_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
                                    `created_by` VARCHAR(50) DEFAULT 'system' COMMENT '创建人',
                                    `updated_by` VARCHAR(50) DEFAULT 'system' COMMENT '更新人'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='补充数据表头表';

CREATE INDEX `idx_edt_title_id` ON `extra_data_title`(`temp_data_title_id`);

-- 5. 补充数据表 (extra_data)
-- 存储从全描述中提取的额外属性值
DROP TABLE IF EXISTS `extra_data`;
CREATE TABLE `extra_data` (
                              `id` BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
                              `extra_data_title_id` BIGINT NOT NULL COMMENT '关联补充表头ID',
                              `temp_data_id` BIGINT NOT NULL COMMENT '关联原始数据ID',
                              `col1` VARCHAR(200) DEFAULT NULL,
                              `col2` VARCHAR(200) DEFAULT NULL,
                              `col3` VARCHAR(200) DEFAULT NULL,
                              `col4` VARCHAR(200) DEFAULT NULL,
                              `col5` VARCHAR(200) DEFAULT NULL,
                              `col6` VARCHAR(200) DEFAULT NULL,
                              `col7` VARCHAR(200) DEFAULT NULL,
                              `col8` VARCHAR(200) DEFAULT NULL,
                              `col9` VARCHAR(200) DEFAULT NULL,
                              `col10` VARCHAR(200) DEFAULT NULL,
                              `col11` VARCHAR(200) DEFAULT NULL,
                              `col12` VARCHAR(200) DEFAULT NULL,
                              `col13` VARCHAR(200) DEFAULT NULL,
                              `col14` VARCHAR(200) DEFAULT NULL,
                              `col15` VARCHAR(200) DEFAULT NULL,
                              `col16` VARCHAR(200) DEFAULT NULL,
                              `col17` VARCHAR(200) DEFAULT NULL,
                              `col18` VARCHAR(200) DEFAULT NULL,
                              `col19` VARCHAR(200) DEFAULT NULL,
                              `col20` VARCHAR(200) DEFAULT NULL,
                              `created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                              `updated_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
                              `created_by` VARCHAR(50) DEFAULT 'system' COMMENT '创建人',
                              `updated_by` VARCHAR(50) DEFAULT 'system' COMMENT '更新人'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='补充数据表';

CREATE INDEX `idx_ed_title_id` ON `extra_data`(`extra_data_title_id`);
CREATE INDEX `idx_ed_temp_data_id` ON `extra_data`(`temp_data_id`);

-- 6. 主数据分类表 (main_data_category)
-- 层级结构，支持编码如10/1001/100101
DROP TABLE IF EXISTS `main_data_category`;
CREATE TABLE `main_data_category` (
                                      `id` BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
                                      `category_code` VARCHAR(50) NOT NULL COMMENT '分类编码',
                                      `category_name` VARCHAR(200) NOT NULL COMMENT '分类名称',
                                      `parent_id` BIGINT DEFAULT NULL COMMENT '父级ID',
                                      `level` INT NOT NULL COMMENT '层级',
                                      `full_path` VARCHAR(500) NOT NULL COMMENT '完整路径',
                                      `unit` VARCHAR(50) DEFAULT NULL COMMENT '计量单位',
                                      `description` LONGTEXT COMMENT '分类描述',
                                      `sort_order` INT DEFAULT 0 COMMENT '排序顺序',
                                      `is_active` TINYINT DEFAULT 1 COMMENT '是否启用: 0-否,1-是',
    -- 旧分类编码和名称，用于多版本数据匹配
                                      `old_code_1` VARCHAR(50) DEFAULT NULL COMMENT '旧编码1',
                                      `old_name_1` VARCHAR(200) DEFAULT NULL COMMENT '旧名称1',
                                      `old_code_2` VARCHAR(50) DEFAULT NULL COMMENT '旧编码2',
                                      `old_name_2` VARCHAR(200) DEFAULT NULL COMMENT '旧名称2',
                                      `old_code_3` VARCHAR(50) DEFAULT NULL COMMENT '旧编码3',
                                      `old_name_3` VARCHAR(200) DEFAULT NULL COMMENT '旧名称3',
                                      `old_code_4` VARCHAR(50) DEFAULT NULL COMMENT '旧编码4',
                                      `old_name_4` VARCHAR(200) DEFAULT NULL COMMENT '旧名称4',
                                      `old_code_5` VARCHAR(50) DEFAULT NULL COMMENT '旧编码5',
                                      `old_name_5` VARCHAR(200) DEFAULT NULL COMMENT '旧名称5',
                                      `created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                                      `updated_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
                                      `created_by` VARCHAR(50) DEFAULT 'system' COMMENT '创建人',
                                      `updated_by` VARCHAR(50) DEFAULT 'system' COMMENT '更新人'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='主数据分类表';

CREATE INDEX `idx_mdc_parent_id` ON `main_data_category`(`parent_id`);
CREATE INDEX `idx_mdc_full_path` ON `main_data_category`(`full_path`);
CREATE INDEX `idx_mdc_level` ON `main_data_category`(`level`);
CREATE INDEX `idx_mdc_code` ON `main_data_category`(`category_code`);
CREATE INDEX `idx_mdc_name` ON `main_data_category`(`category_name`);

-- 7. 标准字段定义表 (standard_field_definition)
-- 定义每个分类下需要的标准字段
DROP TABLE IF EXISTS `standard_field_definition`;
CREATE TABLE `standard_field_definition` (
                                             `id` BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
                                             `category_id` BIGINT DEFAULT NULL COMMENT '关联分类ID',
                                             `field_name` VARCHAR(100) DEFAULT NULL COMMENT '字段名',
                                             `display_name` VARCHAR(200) DEFAULT NULL COMMENT '显示名称',
                                             `data_type` VARCHAR(50) DEFAULT 'string' COMMENT '数据类型: string/number/date/boolean',
                                             `is_required` TINYINT DEFAULT 0 COMMENT '是否必填: 0-否,1-是',
                                             `min_length` INT DEFAULT NULL COMMENT '最小长度',
                                             `max_length` INT DEFAULT NULL COMMENT '最大长度',
                                             `min_value` VARCHAR(50) DEFAULT NULL COMMENT '最小值',
                                             `max_value` VARCHAR(50) DEFAULT NULL COMMENT '最大值',
                                             `pattern` VARCHAR(500) DEFAULT NULL COMMENT '正则校验模式',
                                             `allowed_values` LONGTEXT COMMENT '允许的值列表(JSON)',
                                             `display_order` INT DEFAULT 0 COMMENT '显示顺序',
                                             `is_visible` TINYINT DEFAULT 1 COMMENT '是否可见: 0-否,1-是',
                                             `is_editable` TINYINT DEFAULT 1 COMMENT '是否可编辑: 0-否,1-是',
                                             `default_value` VARCHAR(200) DEFAULT NULL COMMENT '默认值',
                                             `hint` VARCHAR(500) DEFAULT NULL COMMENT '提示信息',
                                             `depends_on_field` VARCHAR(100) DEFAULT NULL COMMENT '依赖字段',
                                             `depends_on_value` VARCHAR(200) DEFAULT NULL COMMENT '依赖值',
                                             `created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                                             `updated_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
                                             `created_by` VARCHAR(50) DEFAULT 'system' COMMENT '创建人',
                                             `updated_by` VARCHAR(50) DEFAULT 'system' COMMENT '更新人'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='标准字段定义表';

CREATE INDEX `idx_sfd_category` ON `standard_field_definition`(`category_id`);
CREATE INDEX `idx_sfd_field_name` ON `standard_field_definition`(`field_name`);

-- 8. 标准字段表头表 (standard_title)
-- 每个分类编码对应一组标准字段表头
DROP TABLE IF EXISTS `standard_title`;
CREATE TABLE `standard_title` (
                                  `id` BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
                                  `category_code` VARCHAR(50) NOT NULL COMMENT '分类编码',
                                  `col_title_1` VARCHAR(200) DEFAULT NULL,
                                  `col_title_2` VARCHAR(200) DEFAULT NULL,
                                  `col_title_3` VARCHAR(200) DEFAULT NULL,
                                  `col_title_4` VARCHAR(200) DEFAULT NULL,
                                  `col_title_5` VARCHAR(200) DEFAULT NULL,
                                  `col_title_6` VARCHAR(200) DEFAULT NULL,
                                  `col_title_7` VARCHAR(200) DEFAULT NULL,
                                  `col_title_8` VARCHAR(200) DEFAULT NULL,
                                  `col_title_9` VARCHAR(200) DEFAULT NULL,
                                  `col_title_10` VARCHAR(200) DEFAULT NULL,
                                  `col_title_11` VARCHAR(200) DEFAULT NULL,
                                  `col_title_12` VARCHAR(200) DEFAULT NULL,
                                  `col_title_13` VARCHAR(200) DEFAULT NULL,
                                  `col_title_14` VARCHAR(200) DEFAULT NULL,
                                  `col_title_15` VARCHAR(200) DEFAULT NULL,
                                  `col_title_16` VARCHAR(200) DEFAULT NULL,
                                  `col_title_17` VARCHAR(200) DEFAULT NULL,
                                  `col_title_18` VARCHAR(200) DEFAULT NULL,
                                  `col_title_19` VARCHAR(200) DEFAULT NULL,
                                  `col_title_20` VARCHAR(200) DEFAULT NULL,
                                  `col_title_1_is_must` TINYINT DEFAULT 0 COMMENT '列1是否必填',
                                  `col_title_2_is_must` TINYINT DEFAULT 0,
                                  `col_title_3_is_must` TINYINT DEFAULT 0,
                                  `col_title_4_is_must` TINYINT DEFAULT 0,
                                  `col_title_5_is_must` TINYINT DEFAULT 0,
                                  `col_title_6_is_must` TINYINT DEFAULT 0,
                                  `col_title_7_is_must` TINYINT DEFAULT 0,
                                  `col_title_8_is_must` TINYINT DEFAULT 0,
                                  `col_title_9_is_must` TINYINT DEFAULT 0,
                                  `col_title_10_is_must` TINYINT DEFAULT 0,
                                  `col_title_11_is_must` TINYINT DEFAULT 0,
                                  `col_title_12_is_must` TINYINT DEFAULT 0,
                                  `col_title_13_is_must` TINYINT DEFAULT 0,
                                  `col_title_14_is_must` TINYINT DEFAULT 0,
                                  `col_title_15_is_must` TINYINT DEFAULT 0,
                                  `col_title_16_is_must` TINYINT DEFAULT 0,
                                  `col_title_17_is_must` TINYINT DEFAULT 0,
                                  `col_title_18_is_must` TINYINT DEFAULT 0,
                                  `col_title_19_is_must` TINYINT DEFAULT 0,
                                  `col_title_20_is_must` TINYINT DEFAULT 0,
                                  `created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                                  `updated_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
                                  `created_by` VARCHAR(50) DEFAULT 'system' COMMENT '创建人',
                                  `updated_by` VARCHAR(50) DEFAULT 'system' COMMENT '更新人'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='标准字段表头表';

CREATE UNIQUE INDEX `idx_st_category_code` ON `standard_title`(`category_code`);

-- 9. 清洗结果数据表 (result_data)
-- 按标准字段表头填充的数据
DROP TABLE IF EXISTS `result_data`;
CREATE TABLE `result_data` (
                               `id` BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
                               `standard_title_id` BIGINT NOT NULL COMMENT '关联标准表头ID',
                               `temp_data_id` BIGINT DEFAULT NULL COMMENT '关联原始数据ID',
                               `cleaned_data_id` BIGINT DEFAULT NULL COMMENT '关联清洗后数据ID',
                               `col1` VARCHAR(200) DEFAULT NULL,
                               `col2` VARCHAR(200) DEFAULT NULL,
                               `col3` VARCHAR(200) DEFAULT NULL,
                               `col4` VARCHAR(200) DEFAULT NULL,
                               `col5` VARCHAR(200) DEFAULT NULL,
                               `col6` VARCHAR(200) DEFAULT NULL,
                               `col7` VARCHAR(200) DEFAULT NULL,
                               `col8` VARCHAR(200) DEFAULT NULL,
                               `col9` VARCHAR(200) DEFAULT NULL,
                               `col10` VARCHAR(200) DEFAULT NULL,
                               `col11` VARCHAR(200) DEFAULT NULL,
                               `col12` VARCHAR(200) DEFAULT NULL,
                               `col13` VARCHAR(200) DEFAULT NULL,
                               `col14` VARCHAR(200) DEFAULT NULL,
                               `col15` VARCHAR(200) DEFAULT NULL,
                               `col16` VARCHAR(200) DEFAULT NULL,
                               `col17` VARCHAR(200) DEFAULT NULL,
                               `col18` VARCHAR(200) DEFAULT NULL,
                               `col19` VARCHAR(200) DEFAULT NULL,
                               `col20` VARCHAR(200) DEFAULT NULL,
                               `status` VARCHAR(50) DEFAULT 'draft' COMMENT '状态',
                               `review_comment` VARCHAR(200) COMMENT '审核备注',
                               `reviewed_by` VARCHAR(50) DEFAULT NULL COMMENT '审核人',
                               `reviewed_at` TIMESTAMP NULL DEFAULT NULL COMMENT '审核时间',
                               `created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                               `updated_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
                               `created_by` VARCHAR(50) DEFAULT 'system' COMMENT '创建人',
                               `updated_by` VARCHAR(50) DEFAULT 'system' COMMENT '更新人'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='清洗结果数据表';

CREATE INDEX `idx_rd_standard_title_id` ON `result_data`(`standard_title_id`);
CREATE INDEX `idx_rd_status` ON `result_data`(`status`);
CREATE INDEX `idx_rd_temp_data_id` ON `result_data`(`temp_data_id`);

-- 9.1 填充失败结果数据表 (failed_result_data)
-- 记录因未匹配到标准字段表头（standard_title_id 非空约束）等原因而未能写入 result_data 的数据
DROP TABLE IF EXISTS `failed_result_data`;
CREATE TABLE `failed_result_data` (
                               `id` BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
                               `temp_data_id` BIGINT DEFAULT NULL COMMENT '关联原始数据ID',
                               `cleaned_data_id` BIGINT DEFAULT NULL COMMENT '关联清洗后数据ID',
                               `category_code` VARCHAR(100) DEFAULT NULL COMMENT '导致失败的分类编码',
                               `reason` VARCHAR(500) DEFAULT NULL COMMENT '失败原因',
                               `raw_data` TEXT COMMENT '原始数据快照',
                               `status` VARCHAR(50) DEFAULT 'FAILED' COMMENT '状态',
                               `created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                               `updated_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
                               `created_by` VARCHAR(50) DEFAULT 'system' COMMENT '创建人',
                               `updated_by` VARCHAR(50) DEFAULT 'system' COMMENT '更新人'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='填充失败结果数据表';
CREATE INDEX `idx_frd_temp_data_id` ON `failed_result_data`(`temp_data_id`);
CREATE INDEX `idx_frd_category_code` ON `failed_result_data`(`category_code`);

-- 10. 清洗后数据表 (cleaned_data)
-- 经过清洗和分类匹配后的数据
DROP TABLE IF EXISTS `cleaned_data`;
CREATE TABLE `cleaned_data` (
                                `id` BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
                                `category_id` BIGINT DEFAULT NULL COMMENT '关联分类ID',
                                `category_code` VARCHAR(50) DEFAULT NULL COMMENT '分类编码',
                                `category_level` INT DEFAULT NULL COMMENT '分类层级',
                                `category_full_path` VARCHAR(500) DEFAULT NULL COMMENT '分类完整路径',
                                `temp_data_id` BIGINT NOT NULL COMMENT '关联原始数据ID',
                                `standard_title_id` BIGINT DEFAULT NULL COMMENT '关联标准表头ID',
                                `material_code` VARCHAR(200) DEFAULT NULL COMMENT '物料代码',
                                `material_name` VARCHAR(500) DEFAULT NULL COMMENT '物料名称',
                                `specification` VARCHAR(500) DEFAULT NULL COMMENT '规格型号',
                                `technical_standard` VARCHAR(500) DEFAULT NULL COMMENT '技术标准号',
                                `grade` VARCHAR(200) DEFAULT NULL COMMENT '牌号',
                                `unit` VARCHAR(50) DEFAULT NULL COMMENT '计量单位',
                                `status` VARCHAR(50) DEFAULT 'draft' COMMENT '状态',
                                `quality_score` DOUBLE DEFAULT 0 COMMENT '质量评分',
                                `completeness_score` DOUBLE DEFAULT 0 COMMENT '完整性评分',
                                `accuracy_score` DOUBLE DEFAULT 0 COMMENT '准确度评分',
                                `review_comment` VARCHAR(200) COMMENT '审核备注',
                                `reviewed_by` VARCHAR(50) DEFAULT NULL COMMENT '审核人',
                                `reviewed_at` TIMESTAMP NULL DEFAULT NULL COMMENT '审核时间',
                                `export_batch_id` BIGINT DEFAULT NULL COMMENT '导出批次ID',
                                `exported_at` TIMESTAMP NULL DEFAULT NULL COMMENT '导出时间',
                                `created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                                `updated_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
                                `created_by` VARCHAR(50) DEFAULT 'system' COMMENT '创建人',
                                `updated_by` VARCHAR(50) DEFAULT 'system' COMMENT '更新人'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='清洗后数据表';

CREATE INDEX `idx_cd_category_id` ON `cleaned_data`(`category_id`);
CREATE INDEX `idx_cd_category_code` ON `cleaned_data`(`category_code`);
CREATE INDEX `idx_cd_category_path` ON `cleaned_data`(`category_full_path`);
CREATE INDEX `idx_cd_material_code` ON `cleaned_data`(`material_code`);
CREATE INDEX `idx_cd_status` ON `cleaned_data`(`status`);
CREATE INDEX `idx_cd_temp_data_id` ON `cleaned_data`(`temp_data_id`);

-- 11. 字段映射审核表 (field_mapping_audit)
-- 系统字段映射结果与人工审核记录
DROP TABLE IF EXISTS `field_mapping_audit`;
CREATE TABLE `field_mapping_audit` (
                                       `id` BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
                                       `standard_title_id` BIGINT DEFAULT NULL COMMENT '关联标准表头ID',
                                       `temp_data_title_id` BIGINT DEFAULT NULL COMMENT '关联原始表头ID',
                                       `source_type` VARCHAR(50) DEFAULT NULL COMMENT '来源类型',
                                       `source_field` VARCHAR(200) DEFAULT NULL COMMENT '源字段',
                                       `target_field` VARCHAR(200) DEFAULT NULL COMMENT '目标字段',
                                       `mapping_type` VARCHAR(50) DEFAULT 'auto' COMMENT '映射类型: auto/manual',
                                       `confidence` DOUBLE DEFAULT 0 COMMENT '置信度',
                                       `status` VARCHAR(50) DEFAULT 'pending' COMMENT '状态: pending/approved/rejected',
                                       `suggested_target_field` VARCHAR(200) DEFAULT NULL COMMENT '建议的目标字段',
                                       `review_comment` VARCHAR(200) COMMENT '审核备注',
                                       `reviewed_by` VARCHAR(50) DEFAULT NULL COMMENT '审核人',
                                       `reviewed_at` TIMESTAMP NULL DEFAULT NULL COMMENT '审核时间',
                                       `created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                                       `updated_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
                                       `created_by` VARCHAR(50) DEFAULT 'system' COMMENT '创建人',
                                       `updated_by` VARCHAR(50) DEFAULT 'system' COMMENT '更新人'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='字段映射审核表';

CREATE INDEX `idx_fma_title_id` ON `field_mapping_audit`(`temp_data_title_id`);
CREATE INDEX `idx_fma_status` ON `field_mapping_audit`(`status`);
CREATE INDEX `idx_fma_standard_title_id` ON `field_mapping_audit`(`standard_title_id`);

-- 11.x 数据文件-标准字段表头关联表 (title_standard_title)
-- 在数据清洗/结果填充时记录每个数据文件关联的标准字段表头，供结果数据下拉框按文件快速查询
CREATE TABLE `title_standard_title` (
    `id` BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    `temp_data_title_id` BIGINT NOT NULL COMMENT '数据文件ID',
    `standard_title_id` BIGINT NOT NULL COMMENT '标准字段表头ID',
    `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `created_by` VARCHAR(50) DEFAULT 'system' COMMENT '创建人',
    `updated_by` VARCHAR(50) DEFAULT 'system' COMMENT '更新人'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='数据文件-标准字段表头关联表';
CREATE UNIQUE INDEX `idx_tst_title_std` ON `title_standard_title`(`temp_data_title_id`, `standard_title_id`);
CREATE INDEX `idx_tst_title_id` ON `title_standard_title`(`temp_data_title_id`);
CREATE INDEX `idx_tst_std_id` ON `title_standard_title`(`standard_title_id`);

-- 12. 审核任务表 (review_task)
DROP TABLE IF EXISTS `review_task`;
CREATE TABLE `review_task` (
                               `id` BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
                               `task_type` VARCHAR(50) NOT NULL COMMENT '任务类型',
                               `entity_type` VARCHAR(100) NOT NULL COMMENT '实体类型',
                               `entity_id` BIGINT NOT NULL COMMENT '实体ID',
                               `priority` VARCHAR(20) DEFAULT 'medium' COMMENT '优先级: high/medium/low',
                               `status` VARCHAR(50) DEFAULT 'pending' COMMENT '状态: pending/processing/completed/cancelled',
                               `title` VARCHAR(500) DEFAULT NULL COMMENT '任务标题',
                               `description` VARCHAR(500) COMMENT '任务描述',
                               `issue_details` VARCHAR(500) COMMENT '问题详情',
                               `assigned_to` VARCHAR(50) DEFAULT NULL COMMENT '分配给',
                               `assigned_by` VARCHAR(50) DEFAULT NULL COMMENT '分配人',
                               `assigned_at` TIMESTAMP NULL DEFAULT NULL COMMENT '分配时间',
                               `resolution` VARCHAR(50) DEFAULT NULL COMMENT '解决方案',
                               `resolution_comment` VARCHAR(500) COMMENT '解决备注',
                               `completed_by` VARCHAR(50) DEFAULT NULL COMMENT '完成人',
                               `completed_at` TIMESTAMP NULL DEFAULT NULL COMMENT '完成时间',
                               `due_date` TIMESTAMP NULL DEFAULT NULL COMMENT '截止日期',
                               `estimated_minutes` INT DEFAULT 30 COMMENT '预估分钟数',
                               `created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                               `updated_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
                               `created_by` VARCHAR(50) DEFAULT 'system' COMMENT '创建人',
                               `updated_by` VARCHAR(50) DEFAULT 'system' COMMENT '更新人'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='审核任务表';

CREATE INDEX `idx_rt_task_type` ON `review_task`(`task_type`);
CREATE INDEX `idx_rt_entity` ON `review_task`(`entity_type`, `entity_id`);
CREATE INDEX `idx_rt_status` ON `review_task`(`status`);
CREATE INDEX `idx_rt_assigned_to` ON `review_task`(`assigned_to`);
CREATE INDEX `idx_rt_due_date` ON `review_task`(`due_date`);

-- 13. 导出批次表 (export_batch)
DROP TABLE IF EXISTS `export_batch`;
CREATE TABLE `export_batch` (
                                `id` BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
                                `batch_name` VARCHAR(200) DEFAULT NULL COMMENT '批次名称',
                                `export_type` VARCHAR(50) DEFAULT NULL COMMENT '导出类型',
                                `filter_conditions` VARCHAR(200) COMMENT '过滤条件(JSON)',
                                `category_ids` VARCHAR(200) COMMENT '分类ID列表(JSON)',
                                `status_list` VARCHAR(200) COMMENT '状态列表(JSON)',
                                `format` VARCHAR(20) DEFAULT NULL COMMENT '导出格式: xlsx/csv',
                                `include_columns` VARCHAR(200) COMMENT '包含列(JSON)',
                                `file_name` VARCHAR(200) DEFAULT NULL COMMENT '文件名',
                                `file_path` VARCHAR(500) DEFAULT NULL COMMENT '文件路径',
                                `file_size` BIGINT DEFAULT NULL COMMENT '文件大小(字节)',
                                `total_records` INT DEFAULT 0 COMMENT '总记录数',
                                `exported_records` INT DEFAULT 0 COMMENT '已导出记录数',
                                `status` VARCHAR(50) DEFAULT 'pending' COMMENT '状态: pending/processing/completed/failed',
                                `error_message` VARCHAR(200) COMMENT '错误信息',
                                `exported_by` VARCHAR(50) DEFAULT NULL COMMENT '导出人',
                                `exported_at` TIMESTAMP NULL DEFAULT NULL COMMENT '导出时间',
                                `created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                                `updated_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
                                `created_by` VARCHAR(50) DEFAULT 'system' COMMENT '创建人',
                                `updated_by` VARCHAR(50) DEFAULT 'system' COMMENT '更新人'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='导出批次表';

CREATE INDEX `idx_eb_status` ON `export_batch`(`status`);
CREATE INDEX `idx_eb_exported_by` ON `export_batch`(`exported_by`);
CREATE INDEX `idx_eb_export_type` ON `export_batch`(`export_type`);

SET FOREIGN_KEY_CHECKS = 1;

-- ============================================
-- 插入初始数据
-- ============================================

-- 插入默认解析规则
INSERT INTO `parse_rule` (`rule_name`, `description`, `key_value_separator`, `item_separator`, `trim_spaces`, `ignore_empty_items`, `is_active`, `created_by`) VALUES
    ('默认规则', '按空格区分key/value，按分号结束条目', ' ', ';', 1, 1, 1, 'system');

-- 插入主数据分类（层级结构示例）
-- 先插入根节点
INSERT INTO `main_data_category` (`category_code`, `category_name`, `parent_id`, `level`, `full_path`, `sort_order`, `description`, `unit`, `old_code_1`, `old_name_1`) VALUES
    ('10', '铸锻件', NULL, 1, '/10', 1, '铸锻件一级分类', '千克', '109904', '铸钢件');

-- 获取刚插入的根ID (使用 LAST_INSERT_ID())
-- 注意：这里 parent_id 使用的是假设值，实际使用时需要根据 LAST_INSERT_ID() 动态获取
-- 以下插入语句中 parent_id 值需要根据实际情况调整

INSERT INTO `main_data_category` (`category_code`, `category_name`, `parent_id`, `level`, `full_path`, `sort_order`, `description`, `unit`) VALUES
                                                                                                                                                ('1001', '铸钢件', 1, 2, '/10/1001', 1, '铸钢件二级分类', '千克'),
                                                                                                                                                ('1002', '铸铁件', 1, 2, '/10/1002', 2, '铸铁件二级分类', '千克'),
                                                                                                                                                ('1003', '锻件', 1, 2, '/10/1003', 3, '锻件二级分类', '千克');

INSERT INTO `main_data_category` (`category_code`, `category_name`, `parent_id`, `level`, `full_path`, `sort_order`, `description`, `unit`) VALUES
                                                                                                                                                ('100101', '碳素铸钢件', 2, 3, '/10/1001/100101', 1, '碳素铸钢件三级分类', '千克'),
                                                                                                                                                ('100102', '合金铸钢件', 2, 3, '/10/1001/100102', 2, '合金铸钢件三级分类', '千克'),
                                                                                                                                                ('100201', '灰铸铁件', 3, 3, '/10/1002/100201', 1, '灰铸铁件三级分类', '千克');

-- 插入标准字段定义
INSERT INTO `standard_field_definition` (`field_name`, `display_name`, `data_type`, `is_required`, `display_order`, `is_visible`, `is_editable`) VALUES
                                                                                                                                                     ('material_code', '物料代码', 'string', 1, 1, 1, 1),
                                                                                                                                                     ('material_name', '物料名称', 'string', 1, 2, 1, 1),
                                                                                                                                                     ('specification', '规格型号', 'string', 0, 3, 1, 1),
                                                                                                                                                     ('technical_standard', '技术标准号', 'string', 0, 4, 1, 1),
                                                                                                                                                     ('grade', '牌号', 'string', 0, 5, 1, 1),
                                                                                                                                                     ('unit', '计量单位', 'string', 0, 6, 1, 1),
                                                                                                                                                     ('process', '加工工艺', 'string', 0, 7, 1, 1),
                                                                                                                                                     ('heat_treatment', '热处理工艺', 'string', 0, 8, 1, 1),
                                                                                                                                                     ('special_requirement', '特殊要求', 'string', 0, 9, 1, 1),
                                                                                                                                                     ('standard_code', '规格标准号', 'string', 0, 10, 1, 1);

-- 插入标准字段表头示例 (对应分类编码10)
INSERT INTO `standard_title` (`category_code`, `col_title_1`, `col_title_2`, `col_title_3`, `col_title_4`, `col_title_5`, `col_title_6`, `col_title_7`, `col_title_8`, `col_title_9`, `col_title_10`, `col_title_1_is_must`, `col_title_2_is_must`, `col_title_3_is_must`, `col_title_4_is_must`, `col_title_5_is_must`) VALUES
    ('10', '物料代码', '物料名称', '规格型号', '技术标准号', '牌号', '计量单位', '加工工艺', '热处理工艺', '特殊要求', '规格标准号', 1, 1, 0, 0, 0);

SELECT 'MySQL数据库初始化完成!' AS `message`;