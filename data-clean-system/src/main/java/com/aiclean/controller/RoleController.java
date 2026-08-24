package com.aiclean.controller;

import com.aiclean.annotation.RequirePermission;
import com.aiclean.common.R;
import com.aiclean.common.UserContext;
import com.aiclean.controller.GlobalExceptionHandler.BusinessException;
import com.aiclean.entity.SysOperationLog;
import com.aiclean.entity.SysRole;
import com.aiclean.service.OperationLogService;
import com.aiclean.service.RoleService;
import com.aiclean.vo.RoleFormVO;
import com.baomidou.mybatisplus.core.metadata.IPage;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 角色管理控制器
 * <p>提供角色 CRUD、用户-角色分配相关接口，写操作仅管理员可用。</p>
 */
@RestController
@RequestMapping("/api/roles")
@Tag(name = "角色管理模块", description = "角色列表、新增、编辑、删除、启停、用户角色分配")
@Slf4j
@RequiredArgsConstructor
public class RoleController {

    private final RoleService roleService;
    private final OperationLogService operationLogService;

    /**
     * 校验当前用户是否为管理员
     */
    private void assertAdmin() {
        if (!"admin".equals(UserContext.getRole())) {
            throw new BusinessException("无权限，仅管理员可操作角色");
        }
    }

    /**
     * 记录角色管理操作日志
     */
    private void recordRoleLog(String action, String desc, boolean success, String errorMsg, Long duration) {
        try {
            operationLogService.record(new SysOperationLog() {{
                setAction(action);
                setModule("角色管理");
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
            log.error("记录角色操作日志失败", e);
        }
    }

    /**
     * 分页查询角色
     */
    @GetMapping
    @Operation(summary = "分页查询角色", description = "支持按角色编码/名称搜索，返回用户数与权限数统计")
    @RequirePermission("page:role")
    public R<IPage<Map<String, Object>>> list(@RequestParam(defaultValue = "1") int page,
                                              @RequestParam(defaultValue = "10") int size,
                                              @RequestParam(required = false) String keyword) {
        return R.success(roleService.pageRoles(page, size, keyword));
    }

    /**
     * 查询全部启用角色（下拉框用）
     */
    @GetMapping("/enabled")
    @Operation(summary = "查询启用角色", description = "返回全部启用状态的角色，供下拉选择使用")
    public R<List<SysRole>> listEnabled() {
        return R.success(roleService.listEnabledRoles());
    }

    /**
     * 新建角色
     */
    @PostMapping
    @Operation(summary = "新建角色")
    public R<SysRole> create(@RequestBody RoleFormVO vo) {
        long start = System.currentTimeMillis();
        assertAdmin();
        try {
            SysRole role = roleService.createRole(vo);
            recordRoleLog("role:create", "新建角色：" + role.getRoleName() + "（" + role.getRoleCode() + "）", true, null, System.currentTimeMillis() - start);
            return R.success(role);
        } catch (Exception e) {
            recordRoleLog("role:create", "新建角色", false, e.getMessage(), System.currentTimeMillis() - start);
            throw e;
        }
    }

    /**
     * 编辑角色
     */
    @PutMapping("/{id}")
    @Operation(summary = "编辑角色")
    public R<SysRole> update(@PathVariable Long id, @RequestBody RoleFormVO vo) {
        long start = System.currentTimeMillis();
        assertAdmin();
        try {
            SysRole role = roleService.updateRole(id, vo);
            recordRoleLog("role:update", "编辑角色 ID=" + id + "（" + role.getRoleName() + "）", true, null, System.currentTimeMillis() - start);
            return R.success(role);
        } catch (Exception e) {
            recordRoleLog("role:update", "编辑角色 ID=" + id, false, e.getMessage(), System.currentTimeMillis() - start);
            throw e;
        }
    }

    /**
     * 删除角色
     */
    @DeleteMapping("/{id}")
    @Operation(summary = "删除角色", description = "内置角色与已分配用户的角色不可删除")
    public R<Void> delete(@PathVariable Long id) {
        long start = System.currentTimeMillis();
        assertAdmin();
        try {
            roleService.deleteRole(id);
            recordRoleLog("role:delete", "删除角色 ID=" + id, true, null, System.currentTimeMillis() - start);
            return R.success("删除成功");
        } catch (Exception e) {
            recordRoleLog("role:delete", "删除角色 ID=" + id, false, e.getMessage(), System.currentTimeMillis() - start);
            throw e;
        }
    }

    /**
     * 启用/禁用角色
     */
    @PostMapping("/{id}/status")
    @Operation(summary = "启用/禁用角色")
    public R<Void> status(@PathVariable Long id, @RequestParam Integer status) {
        long start = System.currentTimeMillis();
        assertAdmin();
        try {
            roleService.updateStatus(id, status);
            recordRoleLog("role:status", (status != null && status == 1 ? "启用" : "禁用") + "角色 ID=" + id, true, null, System.currentTimeMillis() - start);
            return R.success(status != null && status == 1 ? "已启用" : "已禁用");
        } catch (Exception e) {
            recordRoleLog("role:status", "变更角色状态 ID=" + id, false, e.getMessage(), System.currentTimeMillis() - start);
            throw e;
        }
    }

    /**
     * 查询指定角色下的用户
     */
    @GetMapping("/{roleCode}/users")
    @Operation(summary = "查询角色下的用户", description = "返回已分配该角色的用户列表")
    public R<List<Map<String, Object>>> usersOfRole(@PathVariable String roleCode) {
        return R.success(roleService.listUsersByRoleCode(roleCode));
    }

    /**
     * 查询指定用户已分配的角色编码
     */
    @GetMapping("/user/{userId}")
    @Operation(summary = "查询用户已分配角色", description = "返回该用户拥有的角色编码列表")
    public R<List<String>> rolesOfUser(@PathVariable Long userId) {
        return R.success(roleService.listRoleCodesByUserId(userId));
    }

    /**
     * 给用户分配角色（全量覆盖）
     */
    @PostMapping("/user/{userId}/assign")
    @Operation(summary = "给用户分配角色", description = "roleCodes 为全量角色编码列表，传空数组表示清空该用户角色")
    public R<Void> assignRolesToUser(@PathVariable Long userId, @RequestBody List<String> roleCodes) {
        long start = System.currentTimeMillis();
        assertAdmin();
        try {
            roleService.assignRolesToUser(userId, roleCodes);
            recordRoleLog("user:assignRole", "为用户 ID=" + userId + " 分配角色：" + (roleCodes == null || roleCodes.isEmpty() ? "无" : String.join(",", roleCodes)), true, null, System.currentTimeMillis() - start);
            return R.success("角色已分配");
        } catch (Exception e) {
            recordRoleLog("user:assignRole", "为用户 ID=" + userId + " 分配角色", false, e.getMessage(), System.currentTimeMillis() - start);
            throw e;
        }
    }
}
