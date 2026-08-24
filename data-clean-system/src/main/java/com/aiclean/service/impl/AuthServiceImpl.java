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
    private final com.aiclean.mapper.SysRoleMapper sysRoleMapper;
    private final com.aiclean.mapper.SysUserRoleMapper sysUserRoleMapper;

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
        // 先确保内置角色存在（角色表为空时初始化 admin / user）
        initBuiltInRoles();

        Long count = sysUserMapper.selectCount(new LambdaQueryWrapper<>());
        if (count != null && count > 0) {
            // 已有用户时，补齐缺失的用户-角色关联（兼容旧数据升级）
            backfillUserRoles();
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

        bindUserRole(admin.getId(), "admin");
        log.info("已初始化默认管理员账号: admin / admin123，请尽快修改密码");
    }

    /**
     * 初始化内置角色（幂等）
     */
    private void initBuiltInRoles() {
        try {
            ensureRole("admin", "管理员", "系统内置管理员，拥有全部权限", 1);
            ensureRole("user", "普通用户", "系统内置普通用户，拥有基础数据处理权限", 2);
        } catch (Exception e) {
            log.warn("初始化内置角色失败（可能是表尚未创建）: {}", e.getMessage());
        }
    }

    /**
     * 角色不存在时创建
     */
    private void ensureRole(String roleCode, String roleName, String description, int sort) {
        Long exists = sysRoleMapper.selectCount(new LambdaQueryWrapper<com.aiclean.entity.SysRole>()
                .eq(com.aiclean.entity.SysRole::getRoleCode, roleCode));
        if (exists != null && exists > 0) {
            return;
        }
        com.aiclean.entity.SysRole role = new com.aiclean.entity.SysRole();
        role.setRoleCode(roleCode);
        role.setRoleName(roleName);
        role.setDescription(description);
        role.setSort(sort);
        role.setBuiltIn(1);
        role.setStatus(1);
        sysRoleMapper.insert(role);
        log.info("已初始化内置角色 [{}] {}", roleCode, roleName);
    }

    /**
     * 为历史用户补齐 sys_user_role 关联（按 sys_user.role 字段推导）
     */
    private void backfillUserRoles() {
        try {
            java.util.List<SysUser> users = sysUserMapper.selectList(new LambdaQueryWrapper<>());
            int fixed = 0;
            for (SysUser u : users) {
                Long rel = sysUserRoleMapper.selectCount(
                        new LambdaQueryWrapper<com.aiclean.entity.SysUserRole>()
                                .eq(com.aiclean.entity.SysUserRole::getUserId, u.getId()));
                if (rel != null && rel > 0) {
                    continue;
                }
                String code = (u.getRole() == null || u.getRole().trim().isEmpty()) ? "user" : u.getRole().trim();
                ensureRole(code, code, "由历史用户数据自动补全的角色", 90);
                bindUserRole(u.getId(), code);
                fixed++;
            }
            if (fixed > 0) {
                log.info("已为 {} 个历史用户补齐用户-角色关联", fixed);
            }
        } catch (Exception e) {
            log.warn("补齐用户角色关联失败: {}", e.getMessage());
        }
    }

    /**
     * 绑定用户与角色
     */
    private void bindUserRole(Long userId, String roleCode) {
        try {
            com.aiclean.entity.SysUserRole ur = new com.aiclean.entity.SysUserRole();
            ur.setUserId(userId);
            ur.setRoleCode(roleCode);
            sysUserRoleMapper.insert(ur);
        } catch (Exception e) {
            log.warn("绑定用户角色失败 userId={}, roleCode={}: {}", userId, roleCode, e.getMessage());
        }
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
