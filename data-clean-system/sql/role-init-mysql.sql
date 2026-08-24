-- ============================================
-- AI Clean 数据清洗系统 - 角色表与用户角色关联
-- 数据库: MySQL
-- 说明: 创建角色表(sys_role)、用户-角色关联表(sys_user_role)，
--       初始化内置角色 admin/user，并按 sys_user.role 字段补齐历史用户的角色关联。
-- 依赖: 需先执行 auth-module.sql（创建 sys_user）与 permission-init-mysql.sql（创建权限表）
-- ============================================

-- 1. 若表已存在则删除（便于重复初始化）
DROP TABLE IF EXISTS sys_user_role;
DROP TABLE IF EXISTS sys_role;

-- 2. 角色表
CREATE TABLE sys_role (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    role_code   VARCHAR(32)  NOT NULL COMMENT '角色编码（唯一），与 sys_role_permission.role_code 对应',
    role_name   VARCHAR(50)  NOT NULL COMMENT '角色名称，如 管理员',
    description VARCHAR(255) COMMENT '角色描述',
    sort        INT      DEFAULT 99 COMMENT '排序号，越小越靠前',
    built_in    TINYINT  DEFAULT 0  COMMENT '是否内置角色：1=内置(不可删除/改编码)，0=自定义',
    status      TINYINT  DEFAULT 1  COMMENT '状态：1=启用，0=禁用',
    created_at  DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    created_by  VARCHAR(50) DEFAULT 'system',
    updated_by  VARCHAR(50) DEFAULT 'system',
    UNIQUE KEY uk_sr_role_code (role_code),
    KEY idx_sr_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='角色表';

-- 3. 用户-角色关联表（支持一个用户多个角色）
CREATE TABLE sys_user_role (
    id        BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id   BIGINT      NOT NULL COMMENT '用户ID',
    role_code VARCHAR(32) NOT NULL COMMENT '角色编码',
    UNIQUE KEY uk_sur_user_role (user_id, role_code),
    KEY idx_sur_user (user_id),
    KEY idx_sur_role (role_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户-角色关联表';

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

SELECT CONCAT('角色初始化完成，共 ', (SELECT COUNT(*) FROM sys_role), ' 个角色，',
              (SELECT COUNT(*) FROM sys_user_role), ' 条用户角色关联') AS message;
