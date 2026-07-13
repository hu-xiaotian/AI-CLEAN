package com.aiclean.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 登录日志实体类
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_login_log")
public class SysLoginLog extends BaseEntity {

    /**
     * 登录账号
     */
    private String username;

    /**
     * 登录 IP
     */
    private String loginIp;

    /**
     * 登录时间
     */
    private LocalDateTime loginTime;

    /**
     * 结果：1=成功，0=失败
     */
    private Integer status;

    /**
     * 结果说明
     */
    private String message;

    /**
     * 浏览器 UA
     */
    private String userAgent;
}
