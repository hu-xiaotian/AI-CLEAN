-- ============================================
-- AI Clean 数据清洗系统 - 操作日志表 (达梦 DM)
-- 数据库: DM8
-- 说明: 记录用户关键操作(文件上传、数据清洗、删除、导出等)，用于后续追溯。
--       需在执行权限模块表(sys_permission / sys_role_permission)之后运行。
-- ============================================

CREATE TABLE sys_operation_log (
    id              BIGINT IDENTITY(1,1) PRIMARY KEY,
    action          VARCHAR2(50),                       -- 操作类型，如 upload/clean/delete/export
    action_desc     VARCHAR2(500),                      -- 操作描述
    module          VARCHAR2(50),                       -- 操作模块
    user_id         BIGINT,                             -- 操作用户ID
    username        VARCHAR2(50),                       -- 操作用户名
    real_name       VARCHAR2(50),                       -- 操作人真实姓名
    request_method  VARCHAR2(10),                       -- 请求方法
    request_url     VARCHAR2(255),                      -- 请求地址
    ip              VARCHAR2(50),                       -- 客户端IP
    status          TINYINT DEFAULT 1,                  -- 操作状态：1=成功，0=失败
    error_msg       VARCHAR2(1000),                     -- 失败原因/异常信息
    duration        BIGINT,                             -- 操作耗时(毫秒)
    operate_time    TIMESTAMP,                          -- 操作时间
    created_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    created_by      VARCHAR2(50) DEFAULT 'system',
    updated_by      VARCHAR2(50) DEFAULT 'system'
);

CREATE INDEX idx_ol_username ON sys_operation_log(username);
CREATE INDEX idx_ol_action ON sys_operation_log(action);
CREATE INDEX idx_ol_module ON sys_operation_log(module);
CREATE INDEX idx_ol_operate_time ON sys_operation_log(operate_time);

SELECT '操作日志表创建完成!' as message;
