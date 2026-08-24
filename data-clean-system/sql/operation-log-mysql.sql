-- ============================================
-- AI Clean 数据清洗系统 - 操作日志表
-- 数据库: MySQL
-- 说明: 记录用户关键操作(文件上传、数据清洗、删除、导出等)，用于后续追溯。
-- ============================================

CREATE TABLE IF NOT EXISTS sys_operation_log (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    action        VARCHAR(50)   COMMENT '操作类型，如 upload/clean/delete/export',
    action_desc   VARCHAR(500)  COMMENT '操作描述',
    module        VARCHAR(50)   COMMENT '操作模块',
    user_id       BIGINT        COMMENT '操作用户ID',
    username      VARCHAR(50)   COMMENT '操作用户名',
    real_name     VARCHAR(50)   COMMENT '操作人真实姓名',
    request_method VARCHAR(10)  COMMENT '请求方法',
    request_url   VARCHAR(255)  COMMENT '请求地址',
    ip            VARCHAR(50)   COMMENT '客户端IP',
    status        TINYINT DEFAULT 1 COMMENT '操作状态：1=成功，0=失败',
    error_msg     VARCHAR(1000) COMMENT '失败原因/异常信息',
    duration      BIGINT        COMMENT '操作耗时(毫秒)',
    operate_time  DATETIME      COMMENT '操作时间',
    created_at    DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at    DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    created_by    VARCHAR(50) DEFAULT 'system',
    updated_by    VARCHAR(50) DEFAULT 'system'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='操作日志表';

CREATE INDEX idx_ol_username ON sys_operation_log(username);
CREATE INDEX idx_ol_action ON sys_operation_log(action);
CREATE INDEX idx_ol_module ON sys_operation_log(module);
CREATE INDEX idx_ol_operate_time ON sys_operation_log(operate_time);
