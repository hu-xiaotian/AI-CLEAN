package com.aiclean.service.impl;

import com.aiclean.common.UserContext;
import com.aiclean.entity.SysOperationLog;
import com.aiclean.mapper.SysOperationLogMapper;
import com.aiclean.service.OperationLogService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 操作日志服务实现
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class OperationLogServiceImpl implements OperationLogService {

    private final SysOperationLogMapper operationLogMapper;

    @Override
    public void record(SysOperationLog opLog) {
        try {
            // 自动补充操作人信息
            if (opLog.getUserId() == null) {
                opLog.setUserId(UserContext.getUserId());
            }
            if (opLog.getUsername() == null) {
                opLog.setUsername(UserContext.getUsername());
            }
            if (opLog.getRealName() == null) {
                opLog.setRealName(UserContext.getRealName());
            }
            if (opLog.getOperateTime() == null) {
                opLog.setOperateTime(LocalDateTime.now());
            }
            if (opLog.getStatus() == null) {
                opLog.setStatus(1);
            }
            operationLogMapper.insert(opLog);
        } catch (Exception e) {
            // 日志写入失败不影响主业务
            log.error("记录操作日志失败", e);
        }
    }
}
