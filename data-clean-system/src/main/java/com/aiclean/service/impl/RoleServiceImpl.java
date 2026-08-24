package com.aiclean.service.impl;

import com.aiclean.controller.GlobalExceptionHandler.BusinessException;
import com.aiclean.entity.SysRole;
import com.aiclean.entity.SysRolePermission;
import com.aiclean.entity.SysUser;
import com.aiclean.entity.SysUserRole;
import com.aiclean.mapper.SysRoleMapper;
import com.aiclean.mapper.SysRolePermissionMapper;
import com.aiclean.mapper.SysUserMapper;
import com.aiclean.mapper.SysUserRoleMapper;
import com.aiclean.service.RoleService;
import com.aiclean.vo.RoleFormVO;
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
 * 角色服务实现
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class RoleServiceImpl implements RoleService {

    private final SysRoleMapper sysRoleMapper;
    private final SysUserRoleMapper sysUserRoleMapper;
    private final SysRolePermissionMapper sysRolePermissionMapper;
    private final SysUserMapper sysUserMapper;

    @Override
    public IPage<Map<String, Object>> pageRoles(int page, int size, String keyword) {
        Page<SysRole> pageReq = new Page<>(page, size);
        LambdaQueryWrapper<SysRole> wrapper = new LambdaQueryWrapper<>();
        if (keyword != null && !keyword.trim().isEmpty()) {
            String k = keyword.trim();
            wrapper.and(w -> w.like(SysRole::getRoleCode, k).or().like(SysRole::getRoleName, k));
        }
        wrapper.orderByAsc(SysRole::getSort).orderByAsc(SysRole::getId);
        IPage<SysRole> rolePage = sysRoleMapper.selectPage(pageReq, wrapper);

        // 统计每个角色的用户数与权限数
        List<Map<String, Object>> records = new ArrayList<>();
        for (SysRole role : rolePage.getRecords()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", role.getId());
            item.put("roleCode", role.getRoleCode());
            item.put("roleName", role.getRoleName());
            item.put("description", role.getDescription());
            item.put("sort", role.getSort());
            item.put("builtIn", role.getBuiltIn());
            item.put("status", role.getStatus());
            item.put("createdAt", role.getCreatedAt());
            item.put("userCount", sysUserRoleMapper.selectCount(new LambdaQueryWrapper<SysUserRole>()
                    .eq(SysUserRole::getRoleCode, role.getRoleCode())));
            item.put("permCount", sysRolePermissionMapper.selectCount(new LambdaQueryWrapper<SysRolePermission>()
                    .eq(SysRolePermission::getRoleCode, role.getRoleCode())));
            records.add(item);
        }

        Page<Map<String, Object>> result = new Page<>(rolePage.getCurrent(), rolePage.getSize(), rolePage.getTotal());
        result.setRecords(records);
        return result;
    }

    @Override
    public List<SysRole> listEnabledRoles() {
        return sysRoleMapper.selectList(new LambdaQueryWrapper<SysRole>()
                .eq(SysRole::getStatus, 1)
                .orderByAsc(SysRole::getSort)
                .orderByAsc(SysRole::getId));
    }

    @Override
    public SysRole getById(Long id) {
        return sysRoleMapper.selectById(id);
    }

    @Override
    public SysRole createRole(RoleFormVO vo) {
        String roleCode = vo.getRoleCode() == null ? "" : vo.getRoleCode().trim();
        if (roleCode.isEmpty()) {
            throw new BusinessException("角色编码不能为空");
        }
        if (!roleCode.matches("^[A-Za-z][A-Za-z0-9_]{1,31}$")) {
            throw new BusinessException("角色编码需以字母开头，仅允许字母、数字、下划线，长度 2-32 位");
        }
        if (vo.getRoleName() == null || vo.getRoleName().trim().isEmpty()) {
            throw new BusinessException("角色名称不能为空");
        }
        if (sysRoleMapper.selectCount(new LambdaQueryWrapper<SysRole>()
                .eq(SysRole::getRoleCode, roleCode)) > 0) {
            throw new BusinessException("角色编码已存在：" + roleCode);
        }
        SysRole role = new SysRole();
        role.setRoleCode(roleCode);
        role.setRoleName(vo.getRoleName().trim());
        role.setDescription(vo.getDescription());
        role.setSort(vo.getSort() == null ? 99 : vo.getSort());
        role.setBuiltIn(0);
        role.setStatus(vo.getStatus() == null ? 1 : vo.getStatus());
        sysRoleMapper.insert(role);
        log.info("创建角色 [{}] 成功", roleCode);
        return role;
    }

    @Override
    public SysRole updateRole(Long id, RoleFormVO vo) {
        SysRole existing = sysRoleMapper.selectById(id);
        if (existing == null) {
            throw new BusinessException("角色不存在");
        }
        if (vo.getRoleName() == null || vo.getRoleName().trim().isEmpty()) {
            throw new BusinessException("角色名称不能为空");
        }
        String newCode = vo.getRoleCode() == null ? existing.getRoleCode() : vo.getRoleCode().trim();
        boolean codeChanged = !existing.getRoleCode().equals(newCode);
        if (codeChanged) {
            if (isBuiltIn(existing)) {
                throw new BusinessException("内置角色的编码不允许修改");
            }
            if (sysRoleMapper.selectCount(new LambdaQueryWrapper<SysRole>()
                    .eq(SysRole::getRoleCode, newCode).ne(SysRole::getId, id)) > 0) {
                throw new BusinessException("角色编码已存在：" + newCode);
            }
        }

        SysRole update = new SysRole();
        update.setId(id);
        update.setRoleCode(newCode);
        update.setRoleName(vo.getRoleName().trim());
        update.setDescription(vo.getDescription());
        update.setSort(vo.getSort());
        update.setStatus(vo.getStatus());
        sysRoleMapper.updateById(update);

        // 角色编码变更时，同步更新关联表，避免权限/用户关联丢失
        if (codeChanged) {
            syncRoleCode(existing.getRoleCode(), newCode);
        }
        log.info("更新角色 id={} 成功", id);
        return sysRoleMapper.selectById(id);
    }

    /**
     * 角色编码变更后同步关联表中的 role_code
     */
    private void syncRoleCode(String oldCode, String newCode) {
        List<SysUserRole> userRoles = sysUserRoleMapper.selectList(
                new LambdaQueryWrapper<SysUserRole>().eq(SysUserRole::getRoleCode, oldCode));
        for (SysUserRole ur : userRoles) {
            ur.setRoleCode(newCode);
            sysUserRoleMapper.updateById(ur);
        }
        List<SysRolePermission> rolePerms = sysRolePermissionMapper.selectList(
                new LambdaQueryWrapper<SysRolePermission>().eq(SysRolePermission::getRoleCode, oldCode));
        for (SysRolePermission rp : rolePerms) {
            rp.setRoleCode(newCode);
            sysRolePermissionMapper.updateById(rp);
        }
        log.info("角色编码 {} -> {} 关联数据已同步（用户 {} 条，权限 {} 条）",
                oldCode, newCode, userRoles.size(), rolePerms.size());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteRole(Long id) {
        SysRole role = sysRoleMapper.selectById(id);
        if (role == null) {
            throw new BusinessException("角色不存在");
        }
        if (isBuiltIn(role)) {
            throw new BusinessException("内置角色不允许删除");
        }
        long userCount = sysUserRoleMapper.selectCount(new LambdaQueryWrapper<SysUserRole>()
                .eq(SysUserRole::getRoleCode, role.getRoleCode()));
        if (userCount > 0) {
            throw new BusinessException("该角色下还有 " + userCount + " 个用户，请先解除分配后再删除");
        }
        // 一并清理角色的权限关联
        sysRolePermissionMapper.delete(new LambdaQueryWrapper<SysRolePermission>()
                .eq(SysRolePermission::getRoleCode, role.getRoleCode()));
        sysRoleMapper.deleteById(id);
        log.info("删除角色 [{}] 成功", role.getRoleCode());
    }

    @Override
    public void updateStatus(Long id, Integer status) {
        SysRole role = sysRoleMapper.selectById(id);
        if (role == null) {
            throw new BusinessException("角色不存在");
        }
        if (isBuiltIn(role) && status != null && status == 0) {
            throw new BusinessException("内置角色不允许禁用");
        }
        SysRole update = new SysRole();
        update.setId(id);
        update.setStatus(status);
        sysRoleMapper.updateById(update);
        log.info("角色 id={} 状态更新为 {}", id, status);
    }

    @Override
    public List<String> listRoleCodesByUserId(Long userId) {
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
    public void assignRolesToUser(Long userId, List<String> roleCodes) {
        SysUser user = sysUserMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException("用户不存在");
        }
        // 全量覆盖：先清空旧关联
        sysUserRoleMapper.delete(new LambdaQueryWrapper<SysUserRole>().eq(SysUserRole::getUserId, userId));

        List<String> validCodes = new ArrayList<>();
        if (roleCodes != null) {
            for (String code : roleCodes) {
                if (code == null || code.trim().isEmpty() || validCodes.contains(code.trim())) {
                    continue;
                }
                String c = code.trim();
                if (sysRoleMapper.selectCount(new LambdaQueryWrapper<SysRole>()
                        .eq(SysRole::getRoleCode, c)) == 0) {
                    throw new BusinessException("角色不存在：" + c);
                }
                validCodes.add(c);
            }
        }
        for (String code : validCodes) {
            SysUserRole ur = new SysUserRole();
            ur.setUserId(userId);
            ur.setRoleCode(code);
            sysUserRoleMapper.insert(ur);
        }

        // 同步 sys_user.role 主角色字段，保证登录 JWT 与历史逻辑仍可用
        // 规则：含 admin 则主角色为 admin，否则取第一个角色，无角色时降级为 user
        String primaryRole = validCodes.contains("admin") ? "admin"
                : (validCodes.isEmpty() ? "user" : validCodes.get(0));
        SysUser update = new SysUser();
        update.setId(userId);
        update.setRole(primaryRole);
        sysUserMapper.updateById(update);

        log.info("用户 id={} 分配角色 {}，主角色={}", userId, validCodes, primaryRole);
    }

    @Override
    public List<Map<String, Object>> listUsersByRoleCode(String roleCode) {
        List<SysUserRole> relations = sysUserRoleMapper.selectList(
                new LambdaQueryWrapper<SysUserRole>().eq(SysUserRole::getRoleCode, roleCode));
        List<Map<String, Object>> result = new ArrayList<>();
        for (SysUserRole ur : relations) {
            SysUser user = sysUserMapper.selectById(ur.getUserId());
            if (user == null) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("userId", user.getId());
            item.put("username", user.getUsername());
            item.put("realName", user.getRealName());
            item.put("status", user.getStatus());
            result.add(item);
        }
        return result;
    }

    /**
     * 是否内置角色
     */
    private boolean isBuiltIn(SysRole role) {
        return role.getBuiltIn() != null && role.getBuiltIn() == 1;
    }
}
