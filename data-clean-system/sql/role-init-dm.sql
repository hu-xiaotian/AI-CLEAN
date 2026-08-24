-- ============================================
-- AI Clean 数据清洗系统 - 角色表与用户角色关联 (达梦 DM)
-- 数据库: DM8
-- 说明: 创建角色表(sys_role)、用户-角色关联表(sys_user_role)，
--       初始化内置角色 admin/user，并按 sys_user.role 字段补齐历史用户的角色关联。
-- 依赖: 需先执行 auth-module 相关脚本（创建 sys_user）与 permission-init-dm.sql（创建权限表）
--       若重复执行会先删除旧表再重建，请谨慎用于生产环境。
-- ============================================

-- 1. 若表已存在则删除（便于重复初始化）
DROP TABLE IF EXISTS sys_user_role;
DROP TABLE IF EXISTS sys_role;

-- 2. 角色表
CREATE TABLE sys_role (
    id          BIGINT IDENTITY(1,1) PRIMARY KEY,
    role_code   VARCHAR2(32)  NOT NULL,                  -- 角色编码（唯一）
    role_name   VARCHAR2(50)  NOT NULL,                  -- 角色名称，如 管理员
    description VARCHAR2(255),                           -- 角色描述
    sort        INTEGER DEFAULT 99,                      -- 排序号，越小越靠前
    built_in    TINYINT DEFAULT 0,                       -- 是否内置角色：1=内置，0=自定义
    status      TINYINT DEFAULT 1,                       -- 状态：1=启用，0=禁用
    created_at  TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    created_by  VARCHAR2(50) DEFAULT 'system',
    updated_by  VARCHAR2(50) DEFAULT 'system'
);
CREATE UNIQUE INDEX idx_sr_role_code ON sys_role(role_code);
CREATE INDEX idx_sr_status ON sys_role(status);

-- 3. 用户-角色关联表（支持一个用户多个角色）
CREATE TABLE sys_user_role (
    id        BIGINT IDENTITY(1,1) PRIMARY KEY,
    user_id   BIGINT       NOT NULL,                     -- 用户ID
    role_code VARCHAR2(32) NOT NULL                      -- 角色编码
);
CREATE UNIQUE INDEX idx_sur_user_role ON sys_user_role(user_id, role_code);
CREATE INDEX idx_sur_user ON sys_user_role(user_id);
CREATE INDEX idx_sur_role ON sys_user_role(role_code);

-- 4. 初始化内置角色
INSERT INTO sys_role (role_code, role_name, description, sort, built_in, status) VALUES
('admin', '管理员',   '系统内置管理员，拥有全部权限',           1, 1, 1),
('user',  '普通用户', '系统内置普通用户，拥有基础数据处理权限', 2, 1, 1);

-- 5. 按 sys_user.role 字段补齐历史用户的角色关联
--    role 为空的用户默认归入 user 角色
INSERT INTO sys_user_role (user_id, role_code)
SELECT u.id,
       CASE WHEN u.role IS NULL OR u.role = '' THEN 'user' ELSE u.role END
FROM sys_user u
WHERE EXISTS (
    SELECT 1 FROM sys_role r
    WHERE r.role_code = CASE WHEN u.role IS NULL OR u.role = '' THEN 'user' ELSE u.role END
);

SELECT '角色表与用户角色关联初始化完成!' AS message;
