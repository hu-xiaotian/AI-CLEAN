package com.aiclean.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serializable;

/**
 * 角色实体
 * <p>对应 sys_role 表。角色是权限的载体：角色关联页面/功能权限，用户再关联角色。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_role")
public class SysRole extends BaseEntity implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 角色编码（唯一），如 admin / user，与 sys_role_permission.role_code 对应
     */
    private String roleCode;

    /**
     * 角色名称，如 管理员
     */
    private String roleName;

    /**
     * 角色描述
     */
    private String description;

    /**
     * 排序号，越小越靠前
     */
    private Integer sort;

    /**
     * 是否内置角色：1=内置（不可删除/不可改编码），0=自定义
     */
    private Integer builtIn;

    /**
     * 状态：1=启用，0=禁用
     */
    private Integer status;
}
