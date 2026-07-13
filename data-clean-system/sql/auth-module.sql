-- ============================================
-- AI Clean 数据清洗系统 - 用户登录认证模块
-- 数据库: 达梦 (Oracle 兼容模式)
-- 版本: 1.0.0
-- 说明: 系统用户表与登录日志表，并初始化默认管理员账号
--       默认管理员: admin / admin123 (密码经 BCrypt 加密，强度 10)
--       若应用已启动过 DataInitializer 自动建过账号，重复执行本 INSERT
--       会因 username 唯一索引报错，可忽略或先清空 sys_user 表。
-- ============================================

-- 1. 系统用户表 (sys_user)
CREATE TABLE sys_user (
    id BIGINT IDENTITY(1,1) PRIMARY KEY,
    username VARCHAR2(50) NOT NULL,           -- 登录账号
    password VARCHAR2(100) NOT NULL,          -- 密码(BCrypt 加密)
    real_name VARCHAR2(50),                   -- 真实姓名
    email VARCHAR2(100),                      -- 邮箱
    phone VARCHAR2(20),                       -- 手机号
    avatar VARCHAR2(500),                     -- 头像 URL
    role VARCHAR2(20) DEFAULT 'user',         -- 角色: admin=管理员, user=普通用户
    status TINYINT DEFAULT 1,                 -- 状态: 1=启用, 0=禁用
    last_login_time TIMESTAMP,                -- 最后登录时间
    last_login_ip VARCHAR2(50),               -- 最后登录 IP
    remark VARCHAR2(500),                     -- 备注
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR2(50) DEFAULT 'system',
    updated_by VARCHAR2(50) DEFAULT 'system'
);
CREATE UNIQUE INDEX idx_su_username ON sys_user(username);
CREATE INDEX idx_su_status ON sys_user(status);
CREATE INDEX idx_su_role ON sys_user(role);

-- 2. 登录日志表 (sys_login_log)
CREATE TABLE sys_login_log (
    id BIGINT IDENTITY(1,1) PRIMARY KEY,
    username VARCHAR2(50),                     -- 登录账号
    login_ip VARCHAR2(50),                     -- 登录 IP
    login_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,  -- 登录时间
    status TINYINT DEFAULT 1,                  -- 结果: 1=成功, 0=失败
    message VARCHAR2(200),                     -- 结果说明
    user_agent VARCHAR2(500),                  -- 浏览器 UA
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR2(50) DEFAULT 'system',
    updated_by VARCHAR2(50) DEFAULT 'system'
);
CREATE INDEX idx_sll_username ON sys_login_log(username);
CREATE INDEX idx_sll_login_time ON sys_login_log(login_time);
CREATE INDEX idx_sll_status ON sys_login_log(status);

-- 3. 初始化默认管理员账号 (admin / admin123)
--    密码 admin123 的 BCrypt 哈希(强度 10)，与后端 AuthServiceImpl 默认一致
INSERT INTO sys_user (username, password, real_name, role, status, remark, created_by, updated_by)
VALUES ('admin', '$2a$10$8rRrj/YI3ycixaOshDl5m.gtyRdO1zbexYIwocP58abP.i4ire6ie',
        '系统管理员', 'admin', 1, '系统初始化默认管理员账号', 'system', 'system');

SELECT '用户登录认证模块表创建完成!' as message;

-- ============================================
-- MySQL 版本(开发环境参考，与达梦二选一执行)
-- ============================================
-- CREATE TABLE sys_user (
--     id BIGINT AUTO_INCREMENT PRIMARY KEY,
--     username VARCHAR(50) NOT NULL,
--     password VARCHAR(100) NOT NULL,
--     real_name VARCHAR(50),
--     email VARCHAR(100),
--     phone VARCHAR(20),
--     avatar VARCHAR(500),
--     role VARCHAR(20) DEFAULT 'user',
--     status TINYINT DEFAULT 1,
--     last_login_time DATETIME,
--     last_login_ip VARCHAR(50),
--     remark VARCHAR(500),
--     created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
--     updated_at DATETIME DEFAULT CURRENT_TIMESTAMP,
--     created_by VARCHAR(50) DEFAULT 'system',
--     updated_by VARCHAR(50) DEFAULT 'system',
--     UNIQUE KEY idx_su_username (username),
--     KEY idx_su_status (status),
--     KEY idx_su_role (role)
-- ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
--
-- CREATE TABLE sys_login_log (
--     id BIGINT AUTO_INCREMENT PRIMARY KEY,
--     username VARCHAR(50),
--     login_ip VARCHAR(50),
--     login_time DATETIME DEFAULT CURRENT_TIMESTAMP,
--     status TINYINT DEFAULT 1,
--     message VARCHAR(200),
--     user_agent VARCHAR(500),
--     created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
--     updated_at DATETIME DEFAULT CURRENT_TIMESTAMP,
--     created_by VARCHAR(50) DEFAULT 'system',
--     updated_by VARCHAR(50) DEFAULT 'system',
--     KEY idx_sll_username (username),
--     KEY idx_sll_login_time (login_time),
--     KEY idx_sll_status (status)
-- ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
--
-- -- 初始化默认管理员账号 (admin / admin123)
-- INSERT INTO sys_user (username, password, real_name, role, status, remark, created_by, updated_by)
-- VALUES ('admin', '$2a$10$8rRrj/YI3ycixaOshDl5m.gtyRdO1zbexYIwocP58abP.i4ire6ie',
--         '系统管理员', 'admin', 1, '系统初始化默认管理员账号', 'system', 'system');
