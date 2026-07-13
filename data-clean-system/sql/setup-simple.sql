-- AI Clean 数据清洗系统 - 简化版数据库初始化脚本
-- 适用于快速测试和开发环境

-- 创建数据库
CREATE DATABASE IF NOT EXISTS aiclean 
CHARACTER SET utf8mb4 
COLLATE utf8mb4_unicode_ci;

USE aiclean;

-- 1. 主数据分类表（层级结构） - 核心表
CREATE TABLE IF NOT EXISTS main_data_category (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    code VARCHAR(50) NOT NULL UNIQUE,
    name VARCHAR(100) NOT NULL,
    parent_id BIGINT,
    level INT NOT NULL,
    full_path VARCHAR(500) NOT NULL,
    sort_order INT DEFAULT 0,
    enabled BOOLEAN DEFAULT TRUE,
    description TEXT,
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_parent_id (parent_id),
    INDEX idx_full_path (full_path(255))
);

-- 2. 清洗后数据表 - 核心表
CREATE TABLE IF NOT EXISTS cleaned_data (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    category_id BIGINT NOT NULL,
    data_code VARCHAR(100) NOT NULL,
    data_name VARCHAR(200) NOT NULL,
    full_description TEXT,
    attributes JSON,
    quality_score DECIMAL(5,2) DEFAULT 0.00,
    data_status VARCHAR(20) NOT NULL DEFAULT 'pending',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_category_id (category_id),
    INDEX idx_data_status (data_status),
    FOREIGN KEY (category_id) REFERENCES main_data_category(id)
);

-- 3. 审核任务表
CREATE TABLE IF NOT EXISTS review_task (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    task_type VARCHAR(50) NOT NULL,
    title VARCHAR(200) NOT NULL,
    data_id BIGINT NOT NULL,
    creator_id VARCHAR(50) NOT NULL,
    assignee_id VARCHAR(50),
    priority VARCHAR(20) NOT NULL DEFAULT 'medium',
    task_status VARCHAR(20) NOT NULL DEFAULT 'pending',
    review_result VARCHAR(20),
    review_comment TEXT,
    due_time DATETIME,
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_data_id (data_id),
    INDEX idx_task_status (task_status),
    FOREIGN KEY (data_id) REFERENCES cleaned_data(id)
);

-- 4. 导出批次表
CREATE TABLE IF NOT EXISTS export_batch (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    batch_code VARCHAR(50) NOT NULL UNIQUE,
    export_format VARCHAR(20) NOT NULL,
    file_path VARCHAR(500),
    status VARCHAR(20) NOT NULL DEFAULT 'pending',
    progress INT DEFAULT 0,
    total_records INT DEFAULT 0,
    user_id VARCHAR(50) NOT NULL,
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

-- 插入初始分类数据
INSERT INTO main_data_category (code, name, parent_id, level, full_path, description) VALUES
('10', '原材料', NULL, 1, '/10', '原材料分类'),
('1001', '金属材料', 1, 2, '/10/1001', '金属原材料'),
('1002', '化工材料', 1, 2, '/10/1002', '化工原材料'),
('100101', '钢材', 2, 3, '/10/1001/100101', '各类钢材'),
('100102', '铝材', 2, 3, '/10/1001/100102', '各类铝材'),
('100201', '塑料原料', 3, 3, '/10/1002/100201', '塑料原材料'),
('100202', '橡胶原料', 3, 3, '/10/1002/100202', '橡胶原材料');

-- 插入示例数据
INSERT INTO cleaned_data (category_id, data_code, data_name, full_description, attributes, quality_score, data_status) VALUES
(4, 'MAT001', 'Q235钢板', 'Q235碳素结构钢板，厚度10mm', '{"规格": "10mm", "材质": "Q235", "产地": "宝钢"}', 85.5, 'approved'),
(4, 'MAT002', '304不锈钢板', '304不锈钢板，厚度5mm', '{"规格": "5mm", "材质": "304", "产地": "太钢"}', 90.0, 'approved'),
(5, 'MAT003', '6061铝板', '6061铝合金板，厚度8mm', '{"规格": "8mm", "材质": "6061", "产地": "中铝"}', 88.5, 'pending'),
(6, 'MAT004', 'PP塑料粒子', '聚丙烯塑料粒子，型号T30S', '{"型号": "T30S", "用途": "注塑", "产地": "中石化"}', 92.0, 'approved'),
(7, 'MAT005', '天然橡胶', '标准胶SCR5', '{"等级": "SCR5", "产地": "海南", "用途": "轮胎制造"}', 86.5, 'pending');

-- 插入示例审核任务
INSERT INTO review_task (task_type, title, data_id, creator_id, assignee_id, priority, task_status, due_time) VALUES
('quality_check', '审核Q235钢板数据', 1, 'admin', 'reviewer1', 'medium', 'completed', DATE_ADD(NOW(), INTERVAL 1 DAY)),
('category_audit', '审核6061铝板分类', 3, 'admin', 'reviewer2', 'high', 'in_progress', DATE_ADD(NOW(), INTERVAL 2 DAY)),
('field_mapping', '审核天然橡胶字段映射', 5, 'admin', NULL, 'low', 'pending', DATE_ADD(NOW(), INTERVAL 3 DAY));

SELECT '简化版数据库初始化完成！' as message;
SELECT '已创建表: main_data_category, cleaned_data, review_task, export_batch' as tables_created;
SELECT '已插入示例数据' as data_inserted;