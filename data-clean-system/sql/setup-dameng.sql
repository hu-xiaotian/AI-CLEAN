-- ============================================
-- AI Clean 数据清洗系统 - 达梦数据库初始化脚本
-- 版本: 2.0.0
-- ============================================

-- 1. 原始数据表头表 (temp_data_title)
-- 存储上传文件的基本信息和列名映射
CREATE TABLE temp_data_title (
    id BIGINT IDENTITY(1,1) PRIMARY KEY,
    file_name VARCHAR2(200) NOT NULL,
    upload_time VARCHAR2(50),
    total_rows INT DEFAULT 0,
    status VARCHAR2(50) DEFAULT 'draft',
    col1_title VARCHAR2(200),
    col2_title VARCHAR2(200),
    col3_title VARCHAR2(200),
    col4_title VARCHAR2(200),
    col5_title VARCHAR2(200),
    col6_title VARCHAR2(200),
    col7_title VARCHAR2(200),
    col8_title VARCHAR2(200),
    col9_title VARCHAR2(200),
    col10_title VARCHAR2(200),
    full_desc_col VARCHAR2(100),
    category_col VARCHAR2(100),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR2(50) DEFAULT 'system',
    updated_by VARCHAR2(50) DEFAULT 'system'
);
CREATE INDEX idx_tt_status ON temp_data_title(status);

-- 2. 原始数据表 (temp_data)
-- 存储从Excel解析的每一行原始数据
CREATE TABLE temp_data (
    id BIGINT IDENTITY(1,1) PRIMARY KEY,
    temp_data_title_id BIGINT NOT NULL,
    row_index INT NOT NULL,
    col1 VARCHAR2(2000),
    col2 VARCHAR2(2000),
    col3 VARCHAR2(2000),
    col4 VARCHAR2(2000),
    col5 VARCHAR2(2000),
    col6 VARCHAR2(2000),
    col7 VARCHAR2(2000),
    col8 VARCHAR2(2000),
    col9 VARCHAR2(2000),
    col10 VARCHAR2(2000),
    status VARCHAR2(50) DEFAULT 'draft',
    error_msg CLOB,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR2(50) DEFAULT 'system',
    updated_by VARCHAR2(50) DEFAULT 'system'
);
CREATE INDEX idx_td_title_id ON temp_data(temp_data_title_id);
CREATE INDEX idx_td_status ON temp_data(status);
CREATE INDEX idx_td_row_index ON temp_data(row_index);

-- 3. 解析规则表 (parse_rule)
-- 用户配置的全描述属性解析规则
CREATE TABLE parse_rule (
    id BIGINT IDENTITY(1,1) PRIMARY KEY,
    rule_name VARCHAR2(100) NOT NULL,
    description VARCHAR2(500),
    key_value_separator VARCHAR2(20) DEFAULT ' ',
    item_separator VARCHAR2(20) DEFAULT ';',
    escape_char VARCHAR2(10) DEFAULT '',
    trim_spaces TINYINT DEFAULT 1,
    ignore_empty_items TINYINT DEFAULT 1,
    is_active TINYINT DEFAULT 1,
    created_by VARCHAR2(50),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_by VARCHAR2(50)
);
CREATE INDEX idx_pr_active ON parse_rule(is_active);

-- 4. 补充数据表头表 (extra_data_title)
-- 存储从全描述中提取的额外属性列名
CREATE TABLE extra_data_title (
    id BIGINT IDENTITY(1,1) PRIMARY KEY,
    temp_data_title_id BIGINT NOT NULL,
    parse_rule_id BIGINT,
    col1_title VARCHAR2(200),
    col2_title VARCHAR2(200),
    col3_title VARCHAR2(200),
    col4_title VARCHAR2(200),
    col5_title VARCHAR2(200),
    col6_title VARCHAR2(200),
    col7_title VARCHAR2(200),
    col8_title VARCHAR2(200),
    col9_title VARCHAR2(200),
    col10_title VARCHAR2(200),
    col11_title VARCHAR2(200),
    col12_title VARCHAR2(200),
    col13_title VARCHAR2(200),
    col14_title VARCHAR2(200),
    col15_title VARCHAR2(200),
    col16_title VARCHAR2(200),
    col17_title VARCHAR2(200),
    col18_title VARCHAR2(200),
    col19_title VARCHAR2(200),
    col20_title VARCHAR2(200),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR2(50) DEFAULT 'system',
    updated_by VARCHAR2(50) DEFAULT 'system'
);
CREATE INDEX idx_edt_title_id ON extra_data_title(temp_data_title_id);

-- 5. 补充数据表 (extra_data)
-- 存储从全描述中提取的额外属性值
CREATE TABLE extra_data (
    id BIGINT IDENTITY(1,1) PRIMARY KEY,
    extra_data_title_id BIGINT NOT NULL,
    temp_data_id BIGINT NOT NULL,
    col1 VARCHAR2(2000),
    col2 VARCHAR2(2000),
    col3 VARCHAR2(2000),
    col4 VARCHAR2(2000),
    col5 VARCHAR2(2000),
    col6 VARCHAR2(2000),
    col7 VARCHAR2(2000),
    col8 VARCHAR2(2000),
    col9 VARCHAR2(2000),
    col10 VARCHAR2(2000),
    col11 VARCHAR2(2000),
    col12 VARCHAR2(2000),
    col13 VARCHAR2(2000),
    col14 VARCHAR2(2000),
    col15 VARCHAR2(2000),
    col16 VARCHAR2(2000),
    col17 VARCHAR2(2000),
    col18 VARCHAR2(2000),
    col19 VARCHAR2(2000),
    col20 VARCHAR2(2000),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR2(50) DEFAULT 'system',
    updated_by VARCHAR2(50) DEFAULT 'system'
);
CREATE INDEX idx_ed_title_id ON extra_data(extra_data_title_id);
CREATE INDEX idx_ed_temp_data_id ON extra_data(temp_data_id);

-- 6. 主数据分类表 (main_data_category)
-- 层级结构，支持编码如10/1001/100101
CREATE TABLE main_data_category (
    id BIGINT IDENTITY(1,1) PRIMARY KEY,
    category_code VARCHAR2(50) NOT NULL,
    category_name VARCHAR2(200) NOT NULL,
    parent_id BIGINT,
    level INT NOT NULL,
    full_path VARCHAR2(500) NOT NULL,
    unit VARCHAR2(50),
    description CLOB,
    sort_order INT DEFAULT 0,
    is_active TINYINT DEFAULT 1,
    -- 旧分类编码和名称，用于多版本数据匹配
    old_code_1 VARCHAR2(50),
    old_name_1 VARCHAR2(200),
    old_code_2 VARCHAR2(50),
    old_name_2 VARCHAR2(200),
    old_code_3 VARCHAR2(50),
    old_name_3 VARCHAR2(200),
    old_code_4 VARCHAR2(50),
    old_name_4 VARCHAR2(200),
    old_code_5 VARCHAR2(50),
    old_name_5 VARCHAR2(200),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR2(50) DEFAULT 'system',
    updated_by VARCHAR2(50) DEFAULT 'system'
);
CREATE INDEX idx_mdc_parent_id ON main_data_category(parent_id);
CREATE INDEX idx_mdc_full_path ON main_data_category(full_path);
CREATE INDEX idx_mdc_level ON main_data_category(level);
CREATE INDEX idx_mdc_code ON main_data_category(category_code);
CREATE INDEX idx_mdc_name ON main_data_category(category_name);

-- 7. 标准字段定义表 (standard_field_definition)
-- 定义每个分类下需要的标准字段
CREATE TABLE standard_field_definition (
    id BIGINT IDENTITY(1,1) PRIMARY KEY,
    category_id BIGINT,
    field_name VARCHAR2(100),
    display_name VARCHAR2(200),
    data_type VARCHAR2(50) DEFAULT 'string',
    is_required TINYINT DEFAULT 0,
    min_length INT,
    max_length INT,
    min_value VARCHAR2(50),
    max_value VARCHAR2(50),
    pattern VARCHAR2(500),
    allowed_values CLOB,
    display_order INT DEFAULT 0,
    is_visible TINYINT DEFAULT 1,
    is_editable TINYINT DEFAULT 1,
    default_value VARCHAR2(200),
    hint VARCHAR2(500),
    depends_on_field VARCHAR2(100),
    depends_on_value VARCHAR2(200),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR2(50) DEFAULT 'system',
    updated_by VARCHAR2(50) DEFAULT 'system'
);
CREATE INDEX idx_sfd_category ON standard_field_definition(category_id);
CREATE INDEX idx_sfd_field_name ON standard_field_definition(field_name);

-- 8. 标准字段表头表 (standard_title)
-- 每个分类编码对应一组标准字段表头
CREATE TABLE standard_title (
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
CREATE UNIQUE INDEX idx_st_category_code ON standard_title(category_code);

-- 9. 清洗结果数据表 (result_data)
-- 按标准字段表头填充的数据
CREATE TABLE result_data (
    id BIGINT IDENTITY(1,1) PRIMARY KEY,
    standard_title_id BIGINT NOT NULL,
    temp_data_id BIGINT,
    cleaned_data_id BIGINT,
    col1 VARCHAR2(2000),
    col2 VARCHAR2(2000),
    col3 VARCHAR2(2000),
    col4 VARCHAR2(2000),
    col5 VARCHAR2(2000),
    col6 VARCHAR2(2000),
    col7 VARCHAR2(2000),
    col8 VARCHAR2(2000),
    col9 VARCHAR2(2000),
    col10 VARCHAR2(2000),
    col11 VARCHAR2(2000),
    col12 VARCHAR2(2000),
    col13 VARCHAR2(2000),
    col14 VARCHAR2(2000),
    col15 VARCHAR2(2000),
    col16 VARCHAR2(2000),
    col17 VARCHAR2(2000),
    col18 VARCHAR2(2000),
    col19 VARCHAR2(2000),
    col20 VARCHAR2(2000),
    status VARCHAR2(50) DEFAULT 'draft',
    review_comment CLOB,
    reviewed_by VARCHAR2(50),
    reviewed_at TIMESTAMP,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR2(50) DEFAULT 'system',
    updated_by VARCHAR2(50) DEFAULT 'system'
);
CREATE INDEX idx_rd_standard_title_id ON result_data(standard_title_id);
CREATE INDEX idx_rd_status ON result_data(status);
CREATE INDEX idx_rd_temp_data_id ON result_data(temp_data_id);

-- 9.1 填充失败结果数据表 (failed_result_data)
-- 记录因未匹配到标准字段表头（standard_title_id 非空约束）等原因而未能写入 result_data 的数据
CREATE TABLE failed_result_data (
    id BIGINT IDENTITY(1,1) PRIMARY KEY,
    temp_data_id BIGINT,
    cleaned_data_id BIGINT,
    category_code VARCHAR2(100),
    reason VARCHAR2(500),
    raw_data CLOB,
    status VARCHAR2(50) DEFAULT 'FAILED',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR2(50) DEFAULT 'system',
    updated_by VARCHAR2(50) DEFAULT 'system'
);
CREATE INDEX idx_frd_temp_data_id ON failed_result_data(temp_data_id);
CREATE INDEX idx_frd_category_code ON failed_result_data(category_code);

-- 10. 清洗后数据表 (cleaned_data)
-- 经过清洗和分类匹配后的数据
CREATE TABLE cleaned_data (
    id BIGINT IDENTITY(1,1) PRIMARY KEY,
    category_id BIGINT,
    category_code VARCHAR2(50),
    category_level INT,
    category_full_path VARCHAR2(500),
    temp_data_id BIGINT NOT NULL,
    standard_title_id BIGINT,
    material_code VARCHAR2(200),
    material_name VARCHAR2(500),
    specification VARCHAR2(500),
    technical_standard VARCHAR2(500),
    grade VARCHAR2(200),
    unit VARCHAR2(50),
    status VARCHAR2(50) DEFAULT 'draft',
    quality_score DOUBLE DEFAULT 0,
    ai_reason CLOB COMMENT 'AI 辅助分类理由描述（启用 AI 辅助评分时记录）',
    completeness_score DOUBLE DEFAULT 0,
    accuracy_score DOUBLE DEFAULT 0,
    review_comment CLOB,
    reviewed_by VARCHAR2(50),
    reviewed_at TIMESTAMP,
    export_batch_id BIGINT,
    exported_at TIMESTAMP,
    match_source VARCHAR2(50),
    match_confidence DOUBLE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR2(50) DEFAULT 'system',
    updated_by VARCHAR2(50) DEFAULT 'system'
);
CREATE INDEX idx_cd_category_id ON cleaned_data(category_id);
CREATE INDEX idx_cd_category_code ON cleaned_data(category_code);
CREATE INDEX idx_cd_category_path ON cleaned_data(category_full_path);
CREATE INDEX idx_cd_material_code ON cleaned_data(material_code);
CREATE INDEX idx_cd_status ON cleaned_data(status);
CREATE INDEX idx_cd_temp_data_id ON cleaned_data(temp_data_id);
CREATE INDEX idx_cd_match_source ON cleaned_data(match_source);

-- 9.1 分类同义词映射表 (category_synonym)
-- 维护人工维护的"别名 -> 标准分类"映射，提升分类匹配召回率
CREATE TABLE category_synonym (
    id BIGINT IDENTITY(1,1) PRIMARY KEY,
    category_id BIGINT NOT NULL,
    synonym_name VARCHAR2(200) NOT NULL,
    synonym_norm VARCHAR2(200),
    description VARCHAR2(500),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR2(50) DEFAULT 'system',
    updated_by VARCHAR2(50) DEFAULT 'system'
);
CREATE INDEX idx_cs_category_id ON category_synonym(category_id);
CREATE INDEX idx_cs_synonym_norm ON category_synonym(synonym_norm);

-- 11. 字段映射审核表 (field_mapping_audit)
-- 系统字段映射结果与人工审核记录
CREATE TABLE field_mapping_audit (
    id BIGINT IDENTITY(1,1) PRIMARY KEY,
    standard_title_id BIGINT,
    temp_data_title_id BIGINT,
    source_type VARCHAR2(50),
    source_field VARCHAR2(200),
    target_field VARCHAR2(200),
    mapping_type VARCHAR2(50) DEFAULT 'auto',
    confidence DOUBLE DEFAULT 0,
    status VARCHAR2(50) DEFAULT 'pending',
    suggested_target_field VARCHAR2(200),
    review_comment CLOB,
    reviewed_by VARCHAR2(50),
    reviewed_at TIMESTAMP,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR2(50) DEFAULT 'system',
    updated_by VARCHAR2(50) DEFAULT 'system'
);
CREATE INDEX idx_fma_title_id ON field_mapping_audit(temp_data_title_id);
CREATE INDEX idx_fma_status ON field_mapping_audit(status);
CREATE INDEX idx_fma_standard_title_id ON field_mapping_audit(standard_title_id);

-- 11.x 数据文件-标准字段表头关联表 (title_standard_title)
-- 在数据清洗/结果填充时记录每个数据文件关联的标准字段表头，供结果数据下拉框按文件快速查询
CREATE TABLE title_standard_title (
    id BIGINT IDENTITY(1,1) PRIMARY KEY,
    temp_data_title_id BIGINT NOT NULL,
    standard_title_id BIGINT NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR2(50) DEFAULT 'system',
    updated_by VARCHAR2(50) DEFAULT 'system'
);
xian

-- 12. 审核任务表 (review_task)
CREATE TABLE review_task (
    id BIGINT IDENTITY(1,1) PRIMARY KEY,
    task_type VARCHAR2(50) NOT NULL,
    entity_type VARCHAR2(100) NOT NULL,
    entity_id BIGINT NOT NULL,
    priority VARCHAR2(20) DEFAULT 'medium',
    status VARCHAR2(50) DEFAULT 'pending',
    title VARCHAR2(500),
    description CLOB,
    issue_details CLOB,
    assigned_to VARCHAR2(50),
    assigned_by VARCHAR2(50),
    assigned_at TIMESTAMP,
    resolution VARCHAR2(50),
    resolution_comment CLOB,
    completed_by VARCHAR2(50),
    completed_at TIMESTAMP,
    due_date TIMESTAMP,
    estimated_minutes INT DEFAULT 30,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR2(50) DEFAULT 'system',
    updated_by VARCHAR2(50) DEFAULT 'system'
);
CREATE INDEX idx_rt_task_type ON review_task(task_type);
CREATE INDEX idx_rt_entity ON review_task(entity_type, entity_id);
CREATE INDEX idx_rt_status ON review_task(status);
CREATE INDEX idx_rt_assigned_to ON review_task(assigned_to);
CREATE INDEX idx_rt_due_date ON review_task(due_date);

-- 13. 导出批次表 (export_batch)
CREATE TABLE export_batch (
    id BIGINT IDENTITY(1,1) PRIMARY KEY,
    batch_name VARCHAR2(200),
    export_type VARCHAR2(50),
    filter_conditions CLOB,
    category_ids CLOB,
    status_list CLOB,
    format VARCHAR2(20),
    include_columns CLOB,
    file_name VARCHAR2(200),
    file_path VARCHAR2(500),
    file_size BIGINT,
    total_records INT DEFAULT 0,
    exported_records INT DEFAULT 0,
    status VARCHAR2(50) DEFAULT 'pending',
    error_message CLOB,
    exported_by VARCHAR2(50),
    exported_at TIMESTAMP,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR2(50) DEFAULT 'system',
    updated_by VARCHAR2(50) DEFAULT 'system'
);
CREATE INDEX idx_eb_status ON export_batch(status);
CREATE INDEX idx_eb_exported_by ON export_batch(exported_by);
CREATE INDEX idx_eb_export_type ON export_batch(export_type);

-- ============================================
-- 插入初始数据
-- ============================================

-- 插入默认解析规则
INSERT INTO parse_rule (rule_name, description, key_value_separator, item_separator, trim_spaces, ignore_empty_items, is_active, created_by) VALUES
('默认规则', '按空格区分key/value，按分号结束条目', ' ', ';', 1, 1, 1, 'system');

-- 插入主数据分类（层级结构示例）
INSERT INTO main_data_category (category_code, category_name, parent_id, level, full_path, sort_order, description, unit, old_code_1, old_name_1) VALUES
('10', '铸锻件', NULL, 1, '/10', 1, '铸锻件一级分类', '千克', '109904', '铸钢件');
-- 获取刚插入的根ID (达梦中用IDENTITY相关函数)
-- 注意：这里parent_id使用的是假设ID值，实际使用时需要调整

INSERT INTO main_data_category (category_code, category_name, parent_id, level, full_path, sort_order, description, unit) VALUES
('1001', '铸钢件', 1, 2, '/10/1001', 1, '铸钢件二级分类', '千克'),
('1002', '铸铁件', 1, 2, '/10/1002', 2, '铸铁件二级分类', '千克'),
('1003', '锻件', 1, 2, '/10/1003', 3, '锻件二级分类', '千克');

INSERT INTO main_data_category (category_code, category_name, parent_id, level, full_path, sort_order, description, unit) VALUES
('100101', '碳素铸钢件', 2, 3, '/10/1001/100101', 1, '碳素铸钢件三级分类', '千克'),
('100102', '合金铸钢件', 2, 3, '/10/1001/100102', 2, '合金铸钢件三级分类', '千克'),
('100201', '灰铸铁件', 3, 3, '/10/1002/100201', 1, '灰铸铁件三级分类', '千克');

-- 插入标准字段定义
INSERT INTO standard_field_definition (field_name, display_name, data_type, is_required, display_order, is_visible, is_editable) VALUES
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
INSERT INTO standard_title (category_code, col_title_1, col_title_2, col_title_3, col_title_4, col_title_5, col_title_6, col_title_7, col_title_8, col_title_9, col_title_10, col_title_1_is_must, col_title_2_is_must, col_title_3_is_must, col_title_4_is_must, col_title_5_is_must) VALUES
('10', '物料代码', '物料名称', '规格型号', '技术标准号', '牌号', '计量单位', '加工工艺', '热处理工艺', '特殊要求', '规格标准号', 1, 1, 0, 0, 0);

SELECT '达梦数据库初始化完成!' as message;
