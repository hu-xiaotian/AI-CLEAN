package com.aiclean.service;

import com.aiclean.entity.SysOperationLog;

/**
 * 操作日志服务
 */
public interface OperationLogService {

    /**
     * 记录操作日志
     *
     * @param log 日志实体（action/module/actionDesc/status 等必填，用户信息自动从上下文补充）
     */
    void record(SysOperationLog log);
}
