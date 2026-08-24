package com.aiclean.controller;

import com.aiclean.common.R;
import com.aiclean.entity.SysOperationLog;
import com.aiclean.mapper.SysOperationLogMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;

/**
 * 操作日志控制器
 */
@RestController
@RequestMapping("/api/operation-logs")
@Tag(name = "操作日志模块", description = "操作日志查询接口，用于操作行为追溯")
@Slf4j
@RequiredArgsConstructor
public class OperationLogController {

    private final SysOperationLogMapper operationLogMapper;

    /**
     * 分页查询操作日志
     */
    @GetMapping
    @Operation(summary = "分页查询操作日志", description = "支持按操作人、操作类型、模块、时间范围筛选")
    public R<IPage<SysOperationLog>> page(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "15") long size,
            @RequestParam(required = false) String username,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String module,
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime startTime,
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime endTime) {

        LambdaQueryWrapper<SysOperationLog> wrapper = new LambdaQueryWrapper<>();
        if (username != null && !username.trim().isEmpty()) {
            wrapper.like(SysOperationLog::getUsername, username.trim());
        }
        if (action != null && !action.trim().isEmpty()) {
            wrapper.eq(SysOperationLog::getAction, action.trim());
        }
        if (module != null && !module.trim().isEmpty()) {
            wrapper.eq(SysOperationLog::getModule, module.trim());
        }
        if (startTime != null) {
            wrapper.ge(SysOperationLog::getOperateTime, startTime);
        }
        if (endTime != null) {
            wrapper.le(SysOperationLog::getOperateTime, endTime);
        }
        wrapper.orderByDesc(SysOperationLog::getOperateTime);

        IPage<SysOperationLog> result = operationLogMapper.selectPage(new Page<>(page, size), wrapper);
        return R.success(result);
    }
}
