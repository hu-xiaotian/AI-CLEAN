package com.aiclean.service.impl;

import cn.hutool.crypto.digest.BCrypt;
import com.aiclean.common.UserContext;
import com.aiclean.controller.GlobalExceptionHandler.BusinessException;
import com.aiclean.entity.SysUser;
import com.aiclean.mapper.SysUserMapper;
import com.aiclean.service.UserService;
import com.aiclean.vo.UserFormVO;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 用户管理服务实现
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    /**
     * 默认重置密码
     */
    private static final String DEFAULT_PASSWORD = "admin123";

    private final SysUserMapper sysUserMapper;

    @Override
    public IPage<SysUser> pageUsers(long page, long size, String keyword) {
        Page<SysUser> pageReq = new Page<>(page, size);
        LambdaQueryWrapper<SysUser> wrapper = new LambdaQueryWrapper<>();
        if (keyword != null && !keyword.trim().isEmpty()) {
            String k = keyword.trim();
            wrapper.and(w -> w.like(SysUser::getUsername, k).or().like(SysUser::getRealName, k));
        }
        wrapper.orderByDesc(SysUser::getId);
        return sysUserMapper.selectPage(pageReq, wrapper);
    }

    @Override
    public void createUser(UserFormVO vo) {
        if (sysUserMapper.selectCount(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getUsername, vo.getUsername())) > 0) {
            throw new BusinessException("用户名已存在");
        }
        SysUser user = new SysUser();
        user.setUsername(vo.getUsername());
        user.setPassword(BCrypt.hashpw(vo.getPassword() == null || vo.getPassword().isEmpty()
                ? DEFAULT_PASSWORD : vo.getPassword()));
        user.setRealName(vo.getRealName());
        user.setEmail(vo.getEmail());
        user.setPhone(vo.getPhone());
        user.setRole(vo.getRole() == null || vo.getRole().isEmpty() ? "user" : vo.getRole());
        user.setStatus(vo.getStatus() == null ? 1 : vo.getStatus());
        user.setRemark("管理员创建");
        sysUserMapper.insert(user);
        log.info("创建用户 [{}] 成功", vo.getUsername());
    }

    @Override
    public void updateUser(Long id, UserFormVO vo) {
        SysUser existing = sysUserMapper.selectById(id);
        if (existing == null) {
            throw new BusinessException("用户不存在");
        }
        if (!existing.getUsername().equals(vo.getUsername())
                && sysUserMapper.selectCount(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getUsername, vo.getUsername()).ne(SysUser::getId, id)) > 0) {
            throw new BusinessException("用户名已存在");
        }
        SysUser update = new SysUser();
        update.setId(id);
        update.setUsername(vo.getUsername());
        update.setRealName(vo.getRealName());
        update.setEmail(vo.getEmail());
        update.setPhone(vo.getPhone());
        update.setRole(vo.getRole());
        update.setStatus(vo.getStatus());
        if (vo.getPassword() != null && !vo.getPassword().trim().isEmpty()) {
            update.setPassword(BCrypt.hashpw(vo.getPassword()));
        }
        sysUserMapper.updateById(update);
        log.info("更新用户 [{}] 成功", vo.getUsername());
    }

    @Override
    public void deleteUser(Long id) {
        if (id.equals(UserContext.getUserId())) {
            throw new BusinessException("不能删除当前登录账号");
        }
        sysUserMapper.deleteById(id);
        log.info("删除用户 id={} 成功", id);
    }

    @Override
    public void updateStatus(Long id, Integer status) {
        if (status != null && status == 0 && id.equals(UserContext.getUserId())) {
            throw new BusinessException("不能禁用当前登录账号");
        }
        SysUser update = new SysUser();
        update.setId(id);
        update.setStatus(status);
        sysUserMapper.updateById(update);
        log.info("用户 id={} 状态更新为 {}", id, status);
    }

    @Override
    public void resetPassword(Long id) {
        SysUser update = new SysUser();
        update.setId(id);
        update.setPassword(BCrypt.hashpw(DEFAULT_PASSWORD));
        sysUserMapper.updateById(update);
        log.info("用户 id={} 密码已重置", id);
    }
}
