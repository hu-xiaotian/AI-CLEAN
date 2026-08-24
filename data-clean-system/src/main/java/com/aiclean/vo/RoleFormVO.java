package com.aiclean.vo;

import lombok.Data;

/**
 * 角色新建/编辑表单
 */
@Data
public class RoleFormVO {

    /**
     * 角色编码（新建必填，编辑时内置角色不可修改）
     */
    private String roleCode;

    /**
     * 角色名称
     */
    private String roleName;

    /**
     * 角色描述
     */
    private String description;

    /**
     * 排序号
     */
    private Integer sort;

    /**
     * 状态：1=启用，0=禁用
     */
    private Integer status;
}
