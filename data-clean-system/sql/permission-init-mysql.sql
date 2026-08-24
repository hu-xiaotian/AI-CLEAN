-- ============================================
-- AI Clean 数据清洗系统 - 权限配置初始数据
-- 数据库: MySQL
-- 说明: 初始化系统权限点(sys_permission)及管理员默认权限关联(sys_role_permission)
--       执行前请确认 auth-module.sql 中的 sys_permission / sys_role_permission 表已存在。
-- ============================================

-- 1. 清空已有数据（如重复初始化，先清理，避免主键冲突）
DELETE FROM sys_role_permission;
DELETE FROM sys_permission;

-- 2. 初始化权限点
INSERT INTO sys_permission (perm_code, perm_name, module, sort, status, created_at, updated_at, created_by, updated_by) VALUES
('data:import:upload',   '文件上传',     '数据导入', 1, 1, NOW(), NOW(), 'system', 'system'),
('data:import:delete',   '删除导入数据', '数据导入', 2, 1, NOW(), NOW(), 'system', 'system'),
('data:clean:start',     '启动清洗',     '数据清洗', 3, 1, NOW(), NOW(), 'system', 'system'),
('data:clean:stop',      '停止清洗',     '数据清洗', 4, 1, NOW(), NOW(), 'system', 'system'),
('data:clean:export',    '导出结果',     '数据清洗', 5, 1, NOW(), NOW(), 'system', 'system'),
('data:rule:manage',     '解析规则管理', '规则配置', 6, 1, NOW(), NOW(), 'system', 'system'),
('data:user:manage',     '用户管理',     '系统管理', 7, 1, NOW(), NOW(), 'system', 'system'),
('data:permission:manage','权限配置',    '系统管理', 8, 1, NOW(), NOW(), 'system', 'system'),
('data:log:view',        '查看操作日志', '系统管理', 9, 1, NOW(), NOW(), 'system', 'system');

-- 3. 管理员默认拥有全部权限
INSERT INTO sys_role_permission (role_code, perm_id)
SELECT 'admin', id FROM sys_permission;

-- 4. 普通用户默认仅拥有导入与清洗相关权限
INSERT INTO sys_role_permission (role_code, perm_id)
SELECT 'user', id FROM sys_permission
WHERE perm_code IN (
    'data:import:upload',
    'data:import:delete',
    'data:clean:start',
    'data:clean:stop',
    'data:clean:export'
);
