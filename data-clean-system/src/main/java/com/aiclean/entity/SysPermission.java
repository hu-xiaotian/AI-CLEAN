package com.aiclean.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serializable;

/**
 * 权限（功能点）实体
 * <p>对应 sys_permission 表，描述系统中可授权的功能，如 文件上传、数据清洗 等。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_permission")
public class SysPermission extends BaseEntity implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 主键
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 权限编码（唯一），如 data:import:upload
     */
    private String permCode;

    /**
     * 权限名称，如 文件上传
     */
    private String permName;

    /**
     * 所属模块，用于前端分组展示，如 数据导入 / 数据清洗
     */
    private String module;

    /**
     * 排序号，越小越靠前
     */
    private Integer sort;

    /**
     * 状态：1=启用，0=禁用
     */
    private Integer status;
}
