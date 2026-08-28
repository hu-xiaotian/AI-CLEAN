package com.aiclean.config;

import com.aiclean.service.AuthService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.Statement;

/**
 * 应用启动数据初始化器
 * <p>1. 自动创建角色相关表（sys_role / sys_user_role），兼容 MySQL 与达梦；</p>
 * <p>2. 初始化内置角色，并在系统无任何用户时创建默认管理员账号。</p>
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class DataInitializer implements ApplicationRunner {

    private final AuthService authService;
    private final DataSource dataSource;

    @Override
    public void run(ApplicationArguments args) {
        // 先建表，保证后续初始化内置角色时表已存在
        try {
            initRoleTables();
        } catch (Exception e) {
            log.warn("初始化角色相关表失败，请手动执行 sql/role-init-*.sql: {}", e.getMessage());
        }
        // 初始化权限表与权限点（页面权限 + 功能权限）
        try {
            initPermissionTables();
        } catch (Exception e) {
            log.warn("初始化权限相关表失败，请手动执行 sql/permission-init-*.sql: {}", e.getMessage());
        }
        try {
            authService.initDefaultAdmin();
        } catch (Exception e) {
            log.warn("初始化默认管理员账号失败（可能 sys_user 表尚未创建）: {}", e.getMessage());
        }
    }

    /**
     * 建表：sys_role、sys_user_role（表已存在时跳过）
     */
    private void initRoleTables() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            boolean isDm = isDmDatabase(conn);

            if (!tableExists(conn, "sys_role")) {
                execute(conn, isDm ? dmRoleTableDdl() : mysqlRoleTableDdl());
                log.info("已自动创建表 sys_role");
            }
            if (!tableExists(conn, "sys_user_role")) {
                execute(conn, isDm ? dmUserRoleTableDdl() : mysqlUserRoleTableDdl());
                // 唯一索引单独建，兼容达梦
                executeQuietly(conn, "CREATE UNIQUE INDEX idx_sur_user_role ON sys_user_role(user_id, role_code)");
                log.info("已自动创建表 sys_user_role");
            }
        }
    }

    /**
     * 判断当前数据库是否为达梦
     */
    private boolean isDmDatabase(Connection conn) {
        try {
            String product = conn.getMetaData().getDatabaseProductName();
            return product != null && product.toUpperCase().contains("DM");
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 判断表是否存在（兼容大小写差异）
     */
    private boolean tableExists(Connection conn, String tableName) {
        DatabaseMetaData meta;
        try {
            meta = conn.getMetaData();
        } catch (Exception e) {
            return false;
        }
        for (String name : new String[]{tableName, tableName.toUpperCase()}) {
            try (ResultSet rs = meta.getTables(conn.getCatalog(), null, name, new String[]{"TABLE"})) {
                if (rs.next()) {
                    return true;
                }
            } catch (Exception ignored) {
                // 尝试下一种写法
            }
        }
        return false;
    }

    private void execute(Connection conn, String sql) throws Exception {
        try (Statement st = conn.createStatement()) {
            st.execute(sql);
        }
    }

    /**
     * 执行 SQL，失败仅记录（用于索引等非致命语句）
     */
    private void executeQuietly(Connection conn, String sql) {
        try (Statement st = conn.createStatement()) {
            st.execute(sql);
        } catch (Exception e) {
            log.debug("执行可选 SQL 失败（忽略）: {}", e.getMessage());
        }
    }

    // ==================== 权限表初始化 ====================

    /**
     * 建表并初始化权限点：sys_permission / sys_role_permission
     * <p>表不存在时自动创建（兼容 MySQL 与达梦），权限点按 perm_code 幂等补齐，
     * 并默认给 admin 分配全部权限、给 user 分配基础页面与功能权限。</p>
     */
    private void initPermissionTables() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            boolean isDm = isDmDatabase(conn);

            if (!tableExists(conn, "sys_permission")) {
                execute(conn, isDm ? dmPermissionTableDdl() : mysqlPermissionTableDdl());
                log.info("已自动创建表 sys_permission");
            }
            if (!tableExists(conn, "sys_role_permission")) {
                execute(conn, isDm ? dmRolePermissionTableDdl() : mysqlRolePermissionTableDdl());
                log.info("已自动创建表 sys_role_permission");
            }

            // 幂等补齐权限点（仅插入不存在的 perm_code）
            ensurePermissionRows(conn);

            // 默认角色分配权限
            assignRolePermissionRows(conn);
        }
    }

    /**
     * 幂等插入权限点（不存在则插入），并返回权限编码 -> id 的映射
     */
    private void ensurePermissionRows(Connection conn) throws Exception {
        String[][] perms = allPermissionPoints();
        int inserted = 0;
        try (Statement st = conn.createStatement()) {
            for (String[] p : perms) {
                String code = p[0];
                // 检查是否存在
                try (ResultSet rs = st.executeQuery("SELECT id FROM sys_permission WHERE perm_code = '" + code + "'")) {
                    if (rs.next()) {
                        continue;
                    }
                }
                String name = p[1];
                String module = p[2];
                String sort = p[3];
                String sql;
                if (isDmDatabase(conn)) {
                    sql = "INSERT INTO sys_permission (perm_code, perm_name, module, sort, status, created_at, updated_at, created_by, updated_by) VALUES ('"
                            + code + "', '" + name + "', '" + module + "', " + sort + ", 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system', 'system')";
                } else {
                    sql = "INSERT INTO sys_permission (perm_code, perm_name, module, sort, status, created_at, updated_at, created_by, updated_by) VALUES ('"
                            + code + "', '" + name + "', '" + module + "', " + sort + ", 1, NOW(), NOW(), 'system', 'system')";
                }
                st.executeUpdate(sql);
                inserted++;
            }
        }
        if (inserted > 0) {
            log.info("已初始化 {} 个权限点", inserted);
        }
    }

    /**
     * 默认角色权限分配：admin 全部权限；user 基础页面+功能权限
     */
    private void assignRolePermissionRows(Connection conn) throws Exception {
        try (Statement st = conn.createStatement()) {
            // admin 关联全部权限
            st.executeUpdate("INSERT INTO sys_role_permission (role_code, perm_id) SELECT 'admin', id FROM sys_permission "
                    + "WHERE NOT EXISTS (SELECT 1 FROM sys_role_permission srp WHERE srp.role_code = 'admin' AND srp.perm_id = sys_permission.id)");
            // user 关联基础页面与功能
            String userPerms = userRolePermissionCodes();
            if (!userPerms.isEmpty()) {
                st.executeUpdate("INSERT INTO sys_role_permission (role_code, perm_id) SELECT 'user', id FROM sys_permission "
                        + "WHERE perm_code IN (" + userPerms + ") "
                        + "AND NOT EXISTS (SELECT 1 FROM sys_role_permission srp WHERE srp.role_code = 'user' AND srp.perm_id = sys_permission.id)");
            }
        }
    }

    /**
     * user 角色默认可访问的权限编码列表（页面前端 + 基础功能）
     */
    private String userRolePermissionCodes() {
        String[] codes = {
                "page:dashboard", "page:import", "page:oneclick", "page:search",
                "page:externalclean", "page:clean", "page:extract",
                "page:result", "page:unmapped", "page:rule", "page:standard",
                "page:file",
                "data:import:upload", "data:import:delete", "data:clean:start",
                "data:clean:stop", "data:clean:export", "data:rule:manage"
        };
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < codes.length; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append("'").append(codes[i]).append("'");
        }
        return sb.toString();
    }

    /**
     * 全部权限点定义：{perm_code, perm_name, module, sort}
     */
    private String[][] allPermissionPoints() {
        return new String[][]{
                // ===== 页面级权限（前端菜单 / 页面访问控制） =====
                {"page:dashboard",      "数据看板",   "首页",       "1"},
                {"page:import",         "数据导入",   "数据导入",   "10"},
                {"page:oneclick",       "一键清洗",   "数据清洗",   "20"},
                {"page:search",         "数据检索",   "数据检索",   "30"},
                {"page:externalclean",  "智能体一键清洗", "外部清洗", "35"},
                {"page:clean",          "智能分类",   "数据清洗",   "40"},
                {"page:extract",        "属性提取",   "数据清洗",   "41"},
                {"page:result",         "结果数据",   "数据输出",   "50"},
                {"page:unmapped",       "无效数据",   "数据输出",   "51"},
                {"page:rule",           "描述拆分配置", "规则配置",  "60"},
                {"page:standard",       "标准列表",   "规则配置",   "61"},
                {"page:users",          "用户管理",   "系统管理",   "70"},
                {"page:role",           "角色管理",   "系统管理",   "71"},
                {"page:permission",     "权限配置",   "系统管理",   "72"},
                {"page:oplog",          "操作日志",   "系统管理",   "73"},
                {"page:file",           "文件管理",   "知识库文件", "74"},
                // ===== 功能级权限（页面内操作按钮） =====
                {"data:import:upload",  "文件上传",     "数据导入", "11"},
                {"data:import:delete",  "删除导入数据", "数据导入", "12"},
                {"data:clean:start",    "启动清洗",     "数据清洗", "21"},
                {"data:clean:stop",     "停止清洗",     "数据清洗", "22"},
                {"data:clean:export",   "导出结果",     "数据输出", "52"},
                {"data:rule:manage",    "解析规则管理", "规则配置", "62"},
                {"data:user:manage",    "用户管理",     "系统管理", "74"},
                {"data:permission:manage", "权限配置",  "系统管理", "75"},
                {"data:log:view",       "查看操作日志", "系统管理", "76"},
        };
    }

    private String mysqlPermissionTableDdl() {
        return "CREATE TABLE IF NOT EXISTS sys_permission ("
                + "id BIGINT AUTO_INCREMENT PRIMARY KEY,"
                + "perm_code VARCHAR(100) NOT NULL,"
                + "perm_name VARCHAR(100),"
                + "module VARCHAR(50),"
                + "sort INT DEFAULT 0,"
                + "status TINYINT DEFAULT 1,"
                + "created_at DATETIME DEFAULT CURRENT_TIMESTAMP,"
                + "updated_at DATETIME DEFAULT CURRENT_TIMESTAMP,"
                + "created_by VARCHAR(50) DEFAULT 'system',"
                + "updated_by VARCHAR(50) DEFAULT 'system',"
                + "UNIQUE KEY uk_sp_perm_code (perm_code)"
                + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='权限表'";
    }

    private String mysqlRolePermissionTableDdl() {
        return "CREATE TABLE IF NOT EXISTS sys_role_permission ("
                + "id BIGINT AUTO_INCREMENT PRIMARY KEY,"
                + "role_code VARCHAR(20) NOT NULL,"
                + "perm_id BIGINT NOT NULL,"
                + "UNIQUE KEY uk_srp_role_perm (role_code, perm_id)"
                + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='角色-权限关联表'";
    }

    private String dmPermissionTableDdl() {
        return "CREATE TABLE sys_permission ("
                + "id BIGINT IDENTITY(1,1) PRIMARY KEY,"
                + "perm_code VARCHAR2(100) NOT NULL UNIQUE,"
                + "perm_name VARCHAR2(100),"
                + "module VARCHAR2(50),"
                + "sort INTEGER DEFAULT 0,"
                + "status TINYINT DEFAULT 1,"
                + "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,"
                + "updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,"
                + "created_by VARCHAR2(50) DEFAULT 'system',"
                + "updated_by VARCHAR2(50) DEFAULT 'system'"
                + ")";
    }

    private String dmRolePermissionTableDdl() {
        return "CREATE TABLE sys_role_permission ("
                + "id BIGINT IDENTITY(1,1) PRIMARY KEY,"
                + "role_code VARCHAR2(20) NOT NULL,"
                + "perm_id BIGINT NOT NULL"
                + ")";
    }

    private String mysqlRoleTableDdl() {
        return "CREATE TABLE IF NOT EXISTS sys_role ("
                + "id BIGINT AUTO_INCREMENT PRIMARY KEY,"
                + "role_code VARCHAR(32) NOT NULL,"
                + "role_name VARCHAR(50) NOT NULL,"
                + "description VARCHAR(255),"
                + "sort INT DEFAULT 99,"
                + "built_in TINYINT DEFAULT 0,"
                + "status TINYINT DEFAULT 1,"
                + "created_at DATETIME DEFAULT CURRENT_TIMESTAMP,"
                + "updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,"
                + "created_by VARCHAR(50) DEFAULT 'system',"
                + "updated_by VARCHAR(50) DEFAULT 'system',"
                + "UNIQUE KEY uk_sr_role_code (role_code)"
                + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='角色表'";
    }

    private String mysqlUserRoleTableDdl() {
        return "CREATE TABLE IF NOT EXISTS sys_user_role ("
                + "id BIGINT AUTO_INCREMENT PRIMARY KEY,"
                + "user_id BIGINT NOT NULL,"
                + "role_code VARCHAR(32) NOT NULL,"
                + "UNIQUE KEY uk_sur_user_role (user_id, role_code)"
                + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户-角色关联表'";
    }

    private String dmRoleTableDdl() {
        return "CREATE TABLE sys_role ("
                + "id BIGINT IDENTITY(1,1) PRIMARY KEY,"
                + "role_code VARCHAR2(32) NOT NULL UNIQUE,"
                + "role_name VARCHAR2(50) NOT NULL,"
                + "description VARCHAR2(255),"
                + "sort INTEGER DEFAULT 99,"
                + "built_in TINYINT DEFAULT 0,"
                + "status TINYINT DEFAULT 1,"
                + "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,"
                + "updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,"
                + "created_by VARCHAR2(50) DEFAULT 'system',"
                + "updated_by VARCHAR2(50) DEFAULT 'system'"
                + ")";
    }

    private String dmUserRoleTableDdl() {
        return "CREATE TABLE sys_user_role ("
                + "id BIGINT IDENTITY(1,1) PRIMARY KEY,"
                + "user_id BIGINT NOT NULL,"
                + "role_code VARCHAR2(32) NOT NULL"
                + ")";
    }
}
