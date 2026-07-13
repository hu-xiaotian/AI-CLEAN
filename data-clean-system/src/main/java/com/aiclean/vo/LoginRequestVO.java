package com.aiclean.vo;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import java.io.Serializable;

/**
 * 登录请求
 */
@Data
public class LoginRequestVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 登录账号
     */
    @NotBlank(message = "用户名不能为空")
    private String username;

    /**
     * 密码
     */
    @NotBlank(message = "密码不能为空")
    private String password;
}
