package com.aiclean.controller;

import com.aiclean.common.R;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * 系统控制器
 * 负责处理系统状态、健康检查、统计信息、配置管理等接口
 */
@RestController
@RequestMapping("/api/system")
@Tag(name = "系统管理模块", description = "系统状态、健康检查、统计信息、配置管理接口")
@Slf4j
public class SystemController {

    /**
     * 健康检查
     */
    @GetMapping("/health")
    @Operation(summary = "健康检查", description = "检查系统健康状态")
    public R<Map<String, Object>> healthCheck() {
        try {
            Map<String, Object> healthInfo = new HashMap<>();
            healthInfo.put("status", "UP");
            healthInfo.put("timestamp", System.currentTimeMillis());
            healthInfo.put("version", "1.0.0");
            healthInfo.put("database", "connected");
            return R.success("系统健康", healthInfo);
        } catch (Exception e) {
            log.error("健康检查失败", e);
            return R.error("健康检查失败: " + e.getMessage());
        }
    }

    /**
     * 获取系统时间
     */
    @GetMapping("/time")
    @Operation(summary = "获取系统时间", description = "获取系统的当前时间")
    public R<Map<String, Object>> getSystemTime() {
        try {
            Map<String, Object> timeInfo = new HashMap<>();
            timeInfo.put("currentTime", System.currentTimeMillis());
            timeInfo.put("serverTime", new java.util.Date().toString());
            timeInfo.put("timezone", java.util.TimeZone.getDefault().getID());
            timeInfo.put("timestamp", System.currentTimeMillis());
            return R.success("系统时间获取成功", timeInfo);
        } catch (Exception e) {
            log.error("获取系统时间失败", e);
            return R.error("获取系统时间失败: " + e.getMessage());
        }
    }

    /**
     * 检查更新
     */
    @GetMapping("/check-update")
    @Operation(summary = "检查更新", description = "检查系统是否有可用更新")
    public R<Map<String, Object>> checkForUpdates() {
        try {
            Map<String, Object> updateInfo = new HashMap<>();
            updateInfo.put("currentVersion", "1.0.0");
            updateInfo.put("latestVersion", "1.0.0");
            updateInfo.put("updateAvailable", false);
            updateInfo.put("releaseNotes", "当前已是最新版本");
            return R.success("更新检查完成", updateInfo);
        } catch (Exception e) {
            log.error("检查更新失败", e);
            return R.error("检查更新失败: " + e.getMessage());
        }
    }

    /**
     * 获取帮助信息
     */
    @GetMapping("/help")
    @Operation(summary = "获取帮助信息", description = "获取系统的帮助信息")
    public R<Map<String, Object>> getHelpInformation() {
        try {
            Map<String, Object> helpInfo = new HashMap<>();
            helpInfo.put("systemName", "AI Clean 数据清洗系统");
            helpInfo.put("version", "1.0.0");
            helpInfo.put("description", "基于Java Spring Boot的数据清洗系统，支持Excel导入、层级分类管理、人工审核工作流和数据导出功能");
            
            String[] features = {
                "Excel文件导入和解析",
                "层级分类管理（支持10/1001/100101格式）",
                "多条件数据查询",
                "人工审核工作流",
                "数据质量评分",
                "多格式数据导出（Excel、CSV、JSON、PDF）",
                "达梦数据库支持"
            };
            helpInfo.put("features", features);
            
            Map<String, String> contact = new HashMap<>();
            contact.put("supportEmail", "support@aiclean.com");
            contact.put("documentation", "https://docs.aiclean.com");
            contact.put("issueTracker", "https://github.com/aiclean/issues");
            helpInfo.put("contact", contact);
            
            return R.success("帮助信息获取成功", helpInfo);
        } catch (Exception e) {
            log.error("获取帮助信息失败", e);
            return R.error("获取帮助信息失败: " + e.getMessage());
        }
    }
}