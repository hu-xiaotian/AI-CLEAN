package com.aiclean.vo;

import com.aiclean.entity.SysUser;
import lombok.Data;

import java.io.Serializable;

/**
 * 登录响应
 */
@Data
public class LoginResponseVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 访问令牌
     */
    private String token;

    /**
     * 用户信息
     */
    private SysUser user;

    public LoginResponseVO() {
    }

    public LoginResponseVO(String token, SysUser user) {
        this.token = token;
        this.user = user;
    }
}
