-- ============================================
-- 外部数据清洗对接模块 - 达梦数据库初始化脚本
-- 模块完全独立，不与系统其他表产生外键耦合
-- 版本: 1.0.0
-- ============================================

-- 1. 外部清洗任务表 (external_clean_task)
CREATE TABLE external_clean_task (
    id BIGINT IDENTITY(1,1) PRIMARY KEY,
    task_id VARCHAR2(64) NOT NULL,
    temp_data_title_id BIGINT,
    file_name VARCHAR2(200),
    mode VARCHAR2(10) DEFAULT 'async',
    status VARCHAR2(30) DEFAULT 'pending',
    callback_url VARCHAR2(500),
    total_rows INT DEFAULT 0,
    classified_rows INT DEFAULT 0,
    processed_rows INT DEFAULT 0,
    high_confidence INT DEFAULT 0,
    medium_confidence INT DEFAULT 0,
    low_confidence INT DEFAULT 0,
    confidence_sum DOUBLE DEFAULT 0,
    estimated_accuracy DOUBLE DEFAULT 0,
    options_json CLOB,
    submitted_at TIMESTAMP,
    completed_at TIMESTAMP,
    callback_received_at TIMESTAMP,
    error_message VARCHAR2(1000),
    retry_count INT DEFAULT 0,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR2(50) DEFAULT 'system',
    updated_by VARCHAR2(50) DEFAULT 'system'
);
CREATE UNIQUE INDEX idx_ect_task_id ON external_clean_task(task_id);
CREATE INDEX idx_ect_status ON external_clean_task(status);
CREATE INDEX idx_ect_title_id ON external_clean_task(temp_data_title_id);

-- 2. 外部清洗任务行表 (external_clean_task_row)
CREATE TABLE external_clean_task_row (
    id BIGINT IDENTITY(1,1) PRIMARY KEY,
    task_id VARCHAR2(64) NOT NULL,
    row_index INT NOT NULL,
    temp_data_id BIGINT,
    request_columns_json CLOB,
    category_code VARCHAR2(50),
    category_name VARCHAR2(200),
    category_path VARCHAR2(500),
    confidence DOUBLE,
    category_confidence DOUBLE,
    extracted_attrs_json CLOB,
    missing_attrs_json VARCHAR2(1000),
    needs_review TINYINT DEFAULT 0,
    review_reason VARCHAR2(500),
    category_citations_json CLOB,
    attr_citations_json CLOB,
    row_status VARCHAR2(30) DEFAULT 'pending',
    corrected_category_code VARCHAR2(50),
    corrected_category_name VARCHAR2(200),
    corrected_attrs_json CLOB,
    correct_comment VARCHAR2(500),
    operated_by VARCHAR2(50),
    operated_at TIMESTAMP,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR2(50) DEFAULT 'system',
    updated_by VARCHAR2(50) DEFAULT 'system'
);
CREATE UNIQUE INDEX idx_ecr_task_row ON external_clean_task_row(task_id, row_index);
CREATE INDEX idx_ecr_task_id ON external_clean_task_row(task_id);
CREATE INDEX idx_ecr_needs_review ON external_clean_task_row(needs_review);
CREATE INDEX idx_ecr_row_status ON external_clean_task_row(row_status);

-- 3. 外部清洗回调日志表 (external_clean_callback_log)
CREATE TABLE external_clean_callback_log (
    id BIGINT IDENTITY(1,1) PRIMARY KEY,
    task_id VARCHAR2(64) NOT NULL,
    callback_status VARCHAR2(30),
    page_no INT,
    page_size INT,
    payload_digest VARCHAR2(64),
    payload_snapshot CLOB,
    process_result VARCHAR2(20),
    error_message VARCHAR2(1000),
    received_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR2(50) DEFAULT 'system',
    updated_by VARCHAR2(50) DEFAULT 'system'
);
CREATE INDEX idx_ecl_task_id ON external_clean_callback_log(task_id);
CREATE INDEX idx_ecl_digest ON external_clean_callback_log(payload_digest);

SELECT '外部清洗模块 达梦初始化完成!' AS message;
