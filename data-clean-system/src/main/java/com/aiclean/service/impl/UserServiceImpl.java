package com.aiclean.service.impl;

import cn.hutool.crypto.digest.BCrypt;
import com.aiclean.common.UserContext;
import com.aiclean.controller.GlobalExceptionHandler.BusinessException;
import com.aiclean.entity.SysRole;
import com.aiclean.entity.SysUser;
import com.aiclean.entity.SysUserRole;
import com.aiclean.mapper.SysRoleMapper;
import com.aiclean.mapper.SysUserMapper;
import com.aiclean.mapper.SysUserRoleMapper;
import com.aiclean.service.RoleService;
import com.aiclean.service.UserService;
import com.aiclean.vo.UserFormVO;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

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
    private final SysUserRoleMapper sysUserRoleMapper;
    private final SysRoleMapper sysRoleMapper;
    private final RoleService roleService;

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
    public IPage<Map<String, Object>> pageUsersWithRoles(long page, long size, String keyword) {
        IPage<SysUser> userPage = pageUsers(page, size, keyword);

        // 角色编码 -> 角色名称 映射，便于前端直接展示中文名
        Map<String, String> roleNameMap = sysRoleMapper.selectList(null).stream()
                .collect(Collectors.toMap(SysRole::getRoleCode, SysRole::getRoleName, (a, b) -> a));

        List<Map<String, Object>> records = new ArrayList<>();
        for (SysUser user : userPage.getRecords()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", user.getId());
            item.put("username", user.getUsername());
            item.put("realName", user.getRealName());
            item.put("email", user.getEmail());
            item.put("phone", user.getPhone());
            item.put("role", user.getRole());
            item.put("status", user.getStatus());
            item.put("lastLoginTime", user.getLastLoginTime());
            item.put("lastLoginIp", user.getLastLoginIp());
            item.put("remark", user.getRemark());
            item.put("createdAt", user.getCreatedAt());

            List<String> codes = listRoleCodes(user.getId());
            item.put("roleCodes", codes);
            item.put("roleNames", codes.stream()
                    .map(c -> roleNameMap.getOrDefault(c, c))
                    .collect(Collectors.toList()));
            records.add(item);
        }

        Page<Map<String, Object>> result = new Page<>(userPage.getCurrent(), userPage.getSize(), userPage.getTotal());
        result.setRecords(records);
        return result;
    }

    @Override
    public List<String> listRoleCodes(Long userId) {
        if (userId == null) {
            return Collections.emptyList();
        }
        return sysUserRoleMapper.selectList(new LambdaQueryWrapper<SysUserRole>()
                        .eq(SysUserRole::getUserId, userId))
                .stream()
                .map(SysUserRole::getRoleCode)
                .distinct()
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
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

        // 同步分配角色关联：未显式传 roleCodes 时，用 role 字段作为默认单角色
        List<String> codes = vo.getRoleCodes();
        if (codes == null) {
            codes = Collections.singletonList(user.getRole());
        }
        roleService.assignRolesToUser(user.getId(), codes);

        log.info("创建用户 [{}] 成功，角色={}", vo.getUsername(), codes);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
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

        // roleCodes 非 null 才更新角色关联，避免误清空
        if (vo.getRoleCodes() != null) {
            roleService.assignRolesToUser(id, vo.getRoleCodes());
        }
        log.info("更新用户 [{}] 成功，角色={}", vo.getUsername(), vo.getRoleCodes());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteUser(Long id) {
        if (id.equals(UserContext.getUserId())) {
            throw new BusinessException("不能删除当前登录账号");
        }
        // 一并清理用户的角色关联，避免脏数据
        sysUserRoleMapper.delete(new LambdaQueryWrapper<SysUserRole>().eq(SysUserRole::getUserId, id));
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
