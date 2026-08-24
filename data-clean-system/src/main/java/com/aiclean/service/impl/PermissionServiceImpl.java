package com.aiclean.service.impl;

import com.aiclean.entity.SysPermission;
import com.aiclean.entity.SysRolePermission;
import com.aiclean.mapper.SysPermissionMapper;
import com.aiclean.mapper.SysRolePermissionMapper;
import com.aiclean.service.PermissionService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.StringUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 权限配置服务实现
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class PermissionServiceImpl implements PermissionService {

    private final SysPermissionMapper permissionMapper;
    private final SysRolePermissionMapper rolePermissionMapper;
    private final com.aiclean.mapper.SysUserRoleMapper userRoleMapper;

    @Override
    public Map<String, List<SysPermission>> listPermissionsGroupedByModule() {
        LambdaQueryWrapper<SysPermission> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(SysPermission::getStatus, 1);
        wrapper.orderByAsc(SysPermission::getSort);
        List<SysPermission> all = permissionMapper.selectList(wrapper);

        // 使用 LinkedHashMap 保持模块出现顺序
        Map<String, List<SysPermission>> grouped = new LinkedHashMap<>();
        for (SysPermission p : all) {
            String module = StringUtils.isBlank(p.getModule()) ? "其他" : p.getModule();
            grouped.computeIfAbsent(module, k -> new java.util.ArrayList<>()).add(p);
        }
        return grouped;
    }

    @Override
    public List<Long> listPermissionIdsByRole(String roleCode) {
        LambdaQueryWrapper<SysRolePermission> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(SysRolePermission::getRoleCode, roleCode);
        return rolePermissionMapper.selectList(wrapper).stream()
                .map(SysRolePermission::getPermId)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void assignPermissions(String roleCode, List<Long> permIds) {
        // 删除该角色原有关联
        rolePermissionMapper.delete(new LambdaQueryWrapper<SysRolePermission>()
                .eq(SysRolePermission::getRoleCode, roleCode));

        if (permIds != null) {
            for (Long permId : permIds) {
                if (permId == null) {
                    continue;
                }
                SysRolePermission rp = new SysRolePermission();
                rp.setRoleCode(roleCode);
                rp.setPermId(permId);
                rolePermissionMapper.insert(rp);
            }
        }
        log.info("角色 [{}] 权限已更新，共分配 {} 项", roleCode, permIds == null ? 0 : permIds.size());
    }

    @Override
    public List<String> listPermissionCodesByUserId(Long userId) {
        if (userId == null) {
            return java.util.Collections.emptyList();
        }
        // 1. 查出用户的全部角色编码
        List<String> roleCodes = userRoleMapper.selectList(
                        new LambdaQueryWrapper<com.aiclean.entity.SysUserRole>()
                                .eq(com.aiclean.entity.SysUserRole::getUserId, userId))
                .stream()
                .map(com.aiclean.entity.SysUserRole::getRoleCode)
                .distinct()
                .collect(Collectors.toList());
        if (roleCodes.isEmpty()) {
            return java.util.Collections.emptyList();
        }

        // 2. 查出这些角色关联的权限ID
        List<Long> permIds = rolePermissionMapper.selectList(
                        new LambdaQueryWrapper<SysRolePermission>()
                                .in(SysRolePermission::getRoleCode, roleCodes))
                .stream()
                .map(SysRolePermission::getPermId)
                .distinct()
                .collect(Collectors.toList());
        if (permIds.isEmpty()) {
            return java.util.Collections.emptyList();
        }

        // 3. 转换为权限编码
        return permissionMapper.selectList(new LambdaQueryWrapper<SysPermission>()
                        .in(SysPermission::getId, permIds)
                        .eq(SysPermission::getStatus, 1))
                .stream()
                .map(SysPermission::getPermCode)
                .distinct()
                .collect(Collectors.toList());
    }
}
