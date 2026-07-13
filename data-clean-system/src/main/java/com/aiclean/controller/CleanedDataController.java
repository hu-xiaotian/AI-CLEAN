package com.aiclean.controller;

import com.aiclean.common.R;
import com.aiclean.entity.CleanedDataEntity;
import com.aiclean.model.SearchCondition;
import com.aiclean.service.DataCleaningService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 清洗数据控制器
 * 负责处理清洗后数据的查询、更新、状态管理、多条件查询等操作
 */
@RestController
@RequestMapping("/api/cleaned-data")
@Tag(name = "清洗数据模块", description = "清洗后数据的查询、管理、多条件查询接口")
@Slf4j
public class CleanedDataController {

    @Autowired
    private DataCleaningService dataCleaningService;

    /**
     * 搜索清洗后的数据
     */
    @PostMapping("/search")
    @Operation(summary = "搜索清洗数据", description = "根据查询条件搜索清洗后的数据")
    public R<List<CleanedDataEntity>> searchCleanedData(@RequestBody SearchCondition condition) {
        try {
            List<CleanedDataEntity> dataList = dataCleaningService.searchCleanedData(condition);
            return R.success("数据搜索成功", dataList);
        } catch (Exception e) {
            log.error("搜索清洗数据失败", e);
            return R.error("搜索清洗数据失败: " + e.getMessage());
        }
    }

    /**
     * 获取清洗统计数据
     */
    @GetMapping("/statistics")
    @Operation(summary = "获取清洗统计数据", description = "获取数据清洗的统计信息")
    public R<Map<String, Object>> getCleaningStatistics(
            @RequestParam(value = "titleId", required = false) Long titleId) {
        try {
            Map<String, Object> statistics = dataCleaningService.getCleaningStatistics(titleId);
            return R.success("统计信息获取成功", statistics);
        } catch (Exception e) {
            log.error("获取统计信息失败", e);
            return R.error("获取统计信息失败: " + e.getMessage());
        }
    }

    /**
     * 获取数据质量报告
     */
    @GetMapping("/quality-report")
    @Operation(summary = "获取数据质量报告", description = "获取数据质量分析报告")
    public R<Map<String, Object>> getQualityReport(
            @RequestParam(value = "titleId", required = false) Long titleId) {
        try {
            Map<String, Object> report = dataCleaningService.getQualityReport(titleId);
            return R.success("质量报告获取成功", report);
        } catch (Exception e) {
            log.error("获取质量报告失败", e);
            return R.error("获取质量报告失败: " + e.getMessage());
        }
    }
}