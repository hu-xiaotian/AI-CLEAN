-- ============================================
-- AI Clean 数据清洗系统 - 权限配置表与初始数据 (达梦 DM)
-- 数据库: DM8
-- 说明: 创建权限表(sys_permission)、角色-权限关联表(sys_role_permission)，
--       并写入系统权限点及管理员/普通用户的默认权限关联。
--       若重复执行会先删除旧表再重建，请谨慎用于生产环境。
-- ============================================

-- 1. 若表已存在则删除（便于重复初始化）
DROP TABLE IF EXISTS sys_role_permission;
DROP TABLE IF EXISTS sys_permission;

-- 2. 权限（功能点）表
CREATE TABLE sys_permission (
    id         BIGINT IDENTITY(1,1) PRIMARY KEY,
    perm_code  VARCHAR2(100) NOT NULL,                 -- 权限编码，如 data:import:upload
    perm_name  VARCHAR2(100),                          -- 权限名称，如 文件上传
    module     VARCHAR2(50),                           -- 所属模块（前端分组用）
    sort       INTEGER DEFAULT 0,                      -- 排序号
    status     TINYINT DEFAULT 1,                      -- 状态：1=启用，0=禁用
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR2(50) DEFAULT 'system',
    updated_by VARCHAR2(50) DEFAULT 'system'
);
CREATE UNIQUE INDEX idx_sp_perm_code ON sys_permission(perm_code);
CREATE INDEX idx_sp_module ON sys_permission(module);

-- 3. 角色-权限关联表
CREATE TABLE sys_role_permission (
    id        BIGINT IDENTITY(1,1) PRIMARY KEY,
    role_code VARCHAR2(20) NOT NULL,                   -- 角色编码，如 admin / user
    perm_id   BIGINT NOT NULL                          -- 权限ID
);
CREATE INDEX idx_srp_role ON sys_role_permission(role_code);
CREATE INDEX idx_srp_perm ON sys_role_permission(perm_id);

-- 4. 初始化权限点（DM8 支持多值 INSERT；时间列使用默认值，省略）
INSERT INTO sys_permission (perm_code, perm_name, module, sort, status, created_by, updated_by) VALUES
('data:import:upload',     '文件上传',     '数据导入', 1, 1, 'system', 'system'),
('data:import:delete',     '删除导入数据', '数据导入', 2, 1, 'system', 'system'),
('data:clean:start',       '启动清洗',     '数据清洗', 3, 1, 'system', 'system'),
('data:clean:stop',        '停止清洗',     '数据清洗', 4, 1, 'system', 'system'),
('data:clean:export',      '导出结果',     '数据清洗', 5, 1, 'system', 'system'),
('data:rule:manage',       '解析规则管理', '规则配置', 6, 1, 'system', 'system'),
('data:user:manage',       '用户管理',     '系统管理', 7, 1, 'system', 'system'),
('data:permission:manage', '权限配置',     '系统管理', 8, 1, 'system', 'system'),
('data:log:view',          '查看操作日志', '系统管理', 9, 1, 'system', 'system'),
('page:file',              '文件管理',     '知识库文件', 10, 1, 'system', 'system');

-- 5. 管理员默认拥有全部权限
INSERT INTO sys_role_permission (role_code, perm_id)
SELECT 'admin', id FROM sys_permission;

-- 6. 普通用户默认仅拥有导入与清洗相关权限
INSERT INTO sys_role_permission (role_code, perm_id)
SELECT 'user', id FROM sys_permission
WHERE perm_code IN (
    'data:import:upload',
    'data:import:delete',
    'data:clean:start',
    'data:clean:stop',
    'data:clean:export'
);

SELECT '权限表与初始数据写入完成!' as message;
