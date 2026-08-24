package com.aiclean.controller;

import com.aiclean.annotation.RequirePermission;
import com.aiclean.common.R;
import com.aiclean.common.UserContext;
import com.aiclean.entity.SysOperationLog;
import com.aiclean.entity.SysPermission;
import com.aiclean.service.OperationLogService;
import com.aiclean.service.PermissionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 权限配置控制器
 */
@RestController
@RequestMapping("/api/permissions")
@Tag(name = "权限配置模块", description = "权限查询与角色权限分配接口")
@Slf4j
@RequiredArgsConstructor
public class PermissionController {

    private final PermissionService permissionService;
    private final com.aiclean.service.RoleService roleService;
    private final OperationLogService operationLogService;

    /**
     * 校验当前用户是否为管理员
     */
    private void assertAdmin() {
        if (!"admin".equals(UserContext.getRole())) {
            throw new GlobalExceptionHandler.BusinessException("无权限，仅管理员可配置权限");
        }
    }

    /**
     * 记录权限配置操作日志
     */
    private void recordPermLog(String action, String desc, boolean success, String errorMsg, Long duration) {
        try {
            operationLogService.record(new SysOperationLog() {{
                setAction(action);
                setModule("权限配置");
                setActionDesc(desc);
                setStatus(success ? 1 : 0);
                if (errorMsg != null) {
                    setErrorMsg(errorMsg);
                }
                if (duration != null) {
                    setDuration(duration);
                }
            }});
        } catch (Exception e) {
            log.error("记录权限配置操作日志失败", e);
        }
    }

    /**
     * 获取全部权限（按模块分组），用于权限配置页面展示
     */
    @GetMapping("/all")
    @Operation(summary = "获取全部权限（按模块分组）", description = "返回按功能模块分组的权限列表，供权限配置页查看")
    @RequirePermission("page:permission")
    public R<Map<String, List<SysPermission>>> listAll() {
        return R.success(permissionService.listPermissionsGroupedByModule());
    }

    /**
     * 获取指定角色已分配的权限ID
     */
    @GetMapping("/role")
    @Operation(summary = "获取角色已分配权限", description = "根据角色编码返回其已拥有的权限ID列表")
    @RequirePermission("page:permission")
    public R<List<Long>> listByRole(@RequestParam String roleCode) {
        return R.success(permissionService.listPermissionIdsByRole(roleCode));
    }

    /**
     * 为角色分配权限（全量覆盖）
     */
    @PostMapping("/role/assign")
    @Operation(summary = "分配角色权限", description = "为指定角色重新分配权限，permIds 为全量权限ID列表")
    public R<Void> assign(@RequestParam String roleCode, @RequestBody List<Long> permIds) {
        long start = System.currentTimeMillis();
        assertAdmin();
        try {
            permissionService.assignPermissions(roleCode, permIds);
            recordPermLog("perm:assign", "为角色 " + roleCode + " 分配权限共 " + (permIds == null ? 0 : permIds.size()) + " 项", true, null, System.currentTimeMillis() - start);
            return R.success("权限已保存");
        } catch (Exception e) {
            recordPermLog("perm:assign", "为角色 " + roleCode + " 分配权限", false, e.getMessage(), System.currentTimeMillis() - start);
            throw e;
        }
    }

    /**
     * 可配置权限的角色列表（用于权限配置页面的角色切换），取自 sys_role 表
     */
    @GetMapping("/roles")
    @Operation(summary = "获取角色列表", description = "返回系统中可配置权限的启用角色列表")
    @RequirePermission("page:permission")
    public R<List<Map<String, String>>> listRoles() {
        List<Map<String, String>> roles = new java.util.ArrayList<>();
        for (com.aiclean.entity.SysRole role : roleService.listEnabledRoles()) {
            Map<String, String> m = new java.util.LinkedHashMap<>();
            m.put("roleCode", role.getRoleCode());
            m.put("roleName", role.getRoleName());
            roles.add(m);
        }
        return R.success(roles);
    }

    /**
     * 当前登录用户拥有的权限编码集合，前端可据此控制菜单/按钮显隐
     */
    @GetMapping("/mine")
    @Operation(summary = "获取当前用户权限编码", description = "返回当前登录用户通过角色继承到的权限编码列表")
    public R<List<String>> myPermissions() {
        return R.success(permissionService.listPermissionCodesByUserId(UserContext.getUserId()));
    }
}
