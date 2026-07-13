package com.aiclean.service.impl;

import cn.hutool.crypto.digest.BCrypt;
import com.aiclean.common.JwtUtil;
import com.aiclean.controller.GlobalExceptionHandler.BusinessException;
import com.aiclean.entity.SysLoginLog;
import com.aiclean.entity.SysUser;
import com.aiclean.mapper.SysLoginLogMapper;
import com.aiclean.mapper.SysUserMapper;
import com.aiclean.service.AuthService;
import com.aiclean.vo.ChangePasswordVO;
import com.aiclean.vo.LoginRequestVO;
import com.aiclean.vo.LoginResponseVO;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 用户认证服务实现
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    private final SysUserMapper sysUserMapper;
    private final SysLoginLogMapper sysLoginLogMapper;
    private final JwtUtil jwtUtil;

    @Override
    public LoginResponseVO login(LoginRequestVO request, String loginIp, String userAgent) {
        String username = request.getUsername();
        SysUser user = sysUserMapper.selectByUsername(username);

        // 校验用户存在性与密码
        if (user == null || !BCrypt.checkpw(request.getPassword(), user.getPassword())) {
            saveLoginLog(username, loginIp, userAgent, 0, "用户名或密码错误");
            throw new BusinessException("用户名或密码错误");
        }

        // 校验账号状态
        if (user.getStatus() != null && user.getStatus() == 0) {
            saveLoginLog(username, loginIp, userAgent, 0, "账号已被禁用");
            throw new BusinessException("账号已被禁用，请联系管理员");
        }

        // 更新最后登录信息
        SysUser update = new SysUser();
        update.setId(user.getId());
        update.setLastLoginTime(LocalDateTime.now());
        update.setLastLoginIp(loginIp);
        sysUserMapper.updateById(update);

        // 记录登录日志
        saveLoginLog(username, loginIp, userAgent, 1, "登录成功");

        // 生成 Token
        String token = jwtUtil.generateToken(user.getId(), user.getUsername(), user.getRole());
        log.info("用户 [{}] 登录成功, IP: {}", username, loginIp);
        return new LoginResponseVO(token, user);
    }

    @Override
    public SysUser getUserById(Long userId) {
        return sysUserMapper.selectById(userId);
    }

    @Override
    public SysUser getUserByUsername(String username) {
        return sysUserMapper.selectByUsername(username);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void changePassword(Long userId, ChangePasswordVO request) {
        SysUser user = sysUserMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException("用户不存在");
        }
        if (!BCrypt.checkpw(request.getOldPassword(), user.getPassword())) {
            throw new BusinessException("旧密码错误");
        }
        SysUser update = new SysUser();
        update.setId(userId);
        update.setPassword(BCrypt.hashpw(request.getNewPassword()));
        sysUserMapper.updateById(update);
        log.info("用户 [{}] 修改密码成功", user.getUsername());
    }

    @Override
    public void initDefaultAdmin() {
        Long count = sysUserMapper.selectCount(new LambdaQueryWrapper<>());
        if (count != null && count > 0) {
            return;
        }
        SysUser admin = new SysUser();
        admin.setUsername("admin");
        admin.setPassword(BCrypt.hashpw("admin123"));
        admin.setRealName("系统管理员");
        admin.setRole("admin");
        admin.setStatus(1);
        admin.setRemark("系统初始化默认管理员账号");
        sysUserMapper.insert(admin);
        log.info("已初始化默认管理员账号: admin / admin123，请尽快修改密码");
    }

    /**
     * 保存登录日志（失败不影响主流程）
     */
    private void saveLoginLog(String username, String loginIp, String userAgent, Integer status, String message) {
        try {
            SysLoginLog logEntity = new SysLoginLog();
            logEntity.setUsername(username);
            logEntity.setLoginIp(loginIp);
            logEntity.setLoginTime(LocalDateTime.now());
            logEntity.setStatus(status);
            logEntity.setMessage(message);
            if (userAgent != null && userAgent.length() > 500) {
                userAgent = userAgent.substring(0, 500);
            }
            logEntity.setUserAgent(userAgent);
            sysLoginLogMapper.insert(logEntity);
        } catch (Exception e) {
            log.warn("保存登录日志失败: {}", e.getMessage());
        }
    }
}
