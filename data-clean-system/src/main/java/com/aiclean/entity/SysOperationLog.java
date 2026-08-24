package com.aiclean.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 操作日志实体
 * <p>记录用户在系统中的关键操作（如文件上传、数据清洗等），便于事后追溯。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_operation_log")
public class SysOperationLog extends BaseEntity implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 主键
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 操作类型，如 upload / clean / export
     */
    private String action;

    /**
     * 操作描述，如 上传文件 xxx.xlsx
     */
    private String actionDesc;

    /**
     * 操作模块，如 数据导入 / 数据清洗
     */
    private String module;

    /**
     * 操作用户ID
     */
    private Long userId;

    /**
     * 操作用户名
     */
    private String username;

    /**
     * 操作人真实姓名
     */
    private String realName;

    /**
     * 请求方法 GET/POST/...
     */
    private String requestMethod;

    /**
     * 请求地址
     */
    private String requestUrl;

    /**
     * 客户端IP
     */
    private String ip;

    /**
     * 操作状态：1=成功，0=失败
     */
    private Integer status;

    /**
     * 失败原因/异常信息
     */
    private String errorMsg;

    /**
     * 操作耗时（毫秒）
     */
    private Long duration;

    /**
     * 操作时间
     */
    private LocalDateTime operateTime;
}
