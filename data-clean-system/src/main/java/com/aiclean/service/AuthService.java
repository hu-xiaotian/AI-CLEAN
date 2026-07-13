package com.aiclean.service;

import com.aiclean.entity.SysUser;
import com.aiclean.vo.ChangePasswordVO;
import com.aiclean.vo.LoginRequestVO;
import com.aiclean.vo.LoginResponseVO;

/**
 * 用户认证服务
 */
public interface AuthService {

    /**
     * 用户登录
     *
     * @param request   登录请求
     * @param loginIp   登录 IP
     * @param userAgent 浏览器 UA
     * @return 登录结果（token + 用户信息）
     */
    LoginResponseVO login(LoginRequestVO request, String loginIp, String userAgent);

    /**
     * 根据用户ID获取用户信息
     */
    SysUser getUserById(Long userId);

    /**
     * 根据用户名获取用户信息
     */
    SysUser getUserByUsername(String username);

    /**
     * 修改密码
     */
    void changePassword(Long userId, ChangePasswordVO request);

    /**
     * 若系统无任何用户，初始化默认管理员账号（admin/admin123）
     */
    void initDefaultAdmin();
}
