package com.aiclean.vo;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;
import java.io.Serializable;

/**
 * 用户新增/编辑表单
 */
@Data
public class UserFormVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 登录账号
     */
    @NotBlank(message = "用户名不能为空")
    @Size(max = 50, message = "用户名过长")
    private String username;

    /**
     * 密码（创建时可选，留空则使用默认密码；编辑时留空表示不修改）
     */
    @Size(max = 100, message = "密码过长")
    private String password;

    /**
     * 真实姓名
     */
    private String realName;

    /**
     * 邮箱
     */
    private String email;

    /**
     * 手机号
     */
    private String phone;

    /**
     * 角色：admin=管理员，user=普通用户
     */
    private String role;

    /**
     * 状态：1=启用，0=禁用
     */
    private Integer status;

    /**
     * 分配给该用户的角色编码列表（可多选）。
     * <p>为 null 时表示本次不修改角色关联；传空数组表示清空角色。</p>
     */
    private java.util.List<String> roleCodes;
}
