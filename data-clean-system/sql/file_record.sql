-- 文件管理记录表
-- 记录上传到知识库文件库(/opt/kb)的文件信息：上传时间、导入知识库状态、备注、更新时间等。
-- 同名重新上传时物理覆盖，导入状态重置为未录入，更新时间刷新。

CREATE TABLE IF NOT EXISTS file_record (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    file_name       VARCHAR(255)   NOT NULL COMMENT '文件名（含扩展名）',
    relative_path   VARCHAR(512)   NOT NULL COMMENT '相对 KB_FILE_DIR 的路径，如 category/property/a.docx',
    absolute_path   VARCHAR(1024)  NOT NULL COMMENT '服务器绝对路径',
    file_size       BIGINT         NOT NULL DEFAULT 0 COMMENT '文件字节大小',
    file_hash       VARCHAR(64)    COMMENT 'SHA-256 文件指纹，用于判重/变更检测',
    file_type       VARCHAR(32)    COMMENT '扩展名，如 docx',
    import_status   VARCHAR(16)    NOT NULL DEFAULT 'NOT_IMPORTED' COMMENT '导入知识库状态: IMPORTED(已录入)/NOT_IMPORTED(未录入)',
    remark          VARCHAR(512)   COMMENT '备注',
    uploaded_at     DATETIME       NOT NULL COMMENT '首次上传时间',
    imported_at     DATETIME       COMMENT '导入知识库时间',
    created_at      DATETIME       COMMENT '记录创建时间',
    updated_at      DATETIME       COMMENT '更新时间（同名覆盖时刷新）',
    created_by      VARCHAR(64)    COMMENT '上传人',
    updated_by      VARCHAR(64)    COMMENT '更新人',
    UNIQUE KEY uk_relative_path (relative_path)
) COMMENT='文件管理记录表';

-- 索引
CREATE INDEX IF NOT EXISTS idx_file_record_name ON file_record (file_name);
CREATE INDEX IF NOT EXISTS idx_file_record_status ON file_record (import_status);
CREATE INDEX IF NOT EXISTS idx_file_record_uploaded ON file_record (uploaded_at);
