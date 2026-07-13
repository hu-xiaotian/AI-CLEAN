package com.aiclean.controller;

import com.aiclean.common.R;
import com.aiclean.common.UserContext;
import com.aiclean.entity.SysUser;
import com.aiclean.service.AuthService;
import com.aiclean.vo.ChangePasswordVO;
import com.aiclean.vo.LoginRequestVO;
import com.aiclean.vo.LoginResponseVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import javax.validation.Valid;

/**
 * 用户认证控制器
 * 负责登录、登出、获取当前用户、修改密码等接口
 */
@RestController
@RequestMapping("/api/auth")
@Tag(name = "用户认证模块", description = "登录、登出、当前用户、修改密码接口")
@Slf4j
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    /**
     * 用户登录
     */
    @PostMapping("/login")
    @Operation(summary = "用户登录", description = "使用用户名和密码登录，返回 JWT Token")
    public R<LoginResponseVO> login(@Valid @RequestBody LoginRequestVO request, HttpServletRequest httpRequest) {
        String ip = getClientIp(httpRequest);
        String userAgent = httpRequest.getHeader("User-Agent");
        LoginResponseVO response = authService.login(request, ip, userAgent);
        return R.success("登录成功", response);
    }

    /**
     * 用户登出
     */
    @PostMapping("/logout")
    @Operation(summary = "用户登出", description = "登出当前用户（前端清除 Token）")
    public R<Void> logout() {
        String username = UserContext.getUsername();
        if (username != null) {
            log.info("用户 [{}] 登出", username);
        }
        return R.success("登出成功");
    }

    /**
     * 获取当前登录用户信息
     */
    @GetMapping("/current")
    @Operation(summary = "获取当前用户", description = "获取当前登录用户的详细信息")
    public R<SysUser> current() {
        Long userId = UserContext.getUserId();
        if (userId == null) {
            return R.unauthorized("未登录");
        }
        SysUser user = authService.getUserById(userId);
        if (user == null) {
            return R.unauthorized("用户不存在");
        }
        return R.success(user);
    }

    /**
     * 修改密码
     */
    @PostMapping("/change-password")
    @Operation(summary = "修改密码", description = "修改当前登录用户的密码")
    public R<Void> changePassword(@Valid @RequestBody ChangePasswordVO request) {
        Long userId = UserContext.getUserId();
        if (userId == null) {
            return R.unauthorized("未登录");
        }
        authService.changePassword(userId, request);
        return R.success("密码修改成功");
    }

    /**
     * 获取客户端真实 IP
     */
    private String getClientIp(HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");
        if (isValidIp(ip)) {
            // 多级代理时取第一个非 unknown 的 IP
            int index = ip.indexOf(',');
            return index > 0 ? ip.substring(0, index).trim() : ip.trim();
        }
        ip = request.getHeader("X-Real-IP");
        if (isValidIp(ip)) {
            return ip.trim();
        }
        return request.getRemoteAddr();
    }

    private boolean isValidIp(String ip) {
        return ip != null && ip.length() > 0 && !"unknown".equalsIgnoreCase(ip);
    }
}
