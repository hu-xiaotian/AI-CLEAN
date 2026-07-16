package com.aiclean.controller;

import com.aiclean.common.R;
import com.aiclean.entity.*;
import com.aiclean.model.SearchCondition;
import com.aiclean.service.DataCleaningService;
import com.baomidou.mybatisplus.core.metadata.IPage;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 数据清洗控制器
 * 负责解析规则管理、全描述提取、分类匹配、字段映射、结果数据填充等核心清洗流程
 */
@RestController
@RequestMapping("/api/cleaning")
@Tag(name = "数据清洗模块", description = "数据清洗全流程管理接口")
@Slf4j
public class DataCleaningController {

    @Autowired
    private DataCleaningService dataCleaningService;

    // ==================== 解析规则管理 ====================

    @PostMapping("/parse-rule")
    @Operation(summary = "创建解析规则")
    public R<ParseRuleEntity> createParseRule(@RequestBody ParseRuleEntity rule) {
        return R.success(dataCleaningService.createParseRule(rule));
    }

    @PutMapping("/parse-rule/{id}")
    @Operation(summary = "更新解析规则")
    public R<ParseRuleEntity> updateParseRule(@PathVariable Long id, @RequestBody ParseRuleEntity rule) {
        rule.setId(id);
        return R.success(dataCleaningService.updateParseRule(rule));
    }

    @DeleteMapping("/parse-rule/{id}")
    @Operation(summary = "删除解析规则")
    public R<Void> deleteParseRule(@PathVariable Long id) {
        dataCleaningService.deleteParseRule(id);
        return R.success();
    }

    @GetMapping("/parse-rule/{id}")
    @Operation(summary = "获取解析规则详情")
    public R<ParseRuleEntity> getParseRuleById(@PathVariable Long id) {
        return R.success(dataCleaningService.getParseRuleById(id));
    }

    @GetMapping("/parse-rules/active")
    @Operation(summary = "获取所有启用的解析规则")
    public R<List<ParseRuleEntity>> getActiveParseRules() {
        return R.success(dataCleaningService.getActiveParseRules());
    }

    // ==================== 全描述提取 ====================

    @PostMapping("/extract-extra")
    @Operation(summary = "提取全描述属性")
    public R<ExtraDataTitleEntity> extractExtraData(@RequestParam Long titleId, @RequestParam Long parseRuleId) {
        return R.success(dataCleaningService.extractExtraData(titleId, parseRuleId));
    }

    @DeleteMapping("/extra-title/{id}")
    @Operation(summary = "删除全描述提取结果", description = "删除补充数据表头及其关联的补充数据详情")
    public R<Void> deleteExtraTitle(@PathVariable Long id) {
        try {
            log.info("删除全描述提取结果，ID: {}", id);
            dataCleaningService.deleteExtraTitle(id);
            return R.success("全描述提取结果已删除");
        } catch (Exception e) {
            log.error("删除全描述提取结果失败，ID: {}", id, e);
            return R.error("删除失败: " + e.getMessage());
        }
    }

    @PostMapping("/extract-extra-ai")
    @Operation(summary = "AI 提取属性", description = "按每行分类编码查找标准字段表头(参数1)，结合属性拆分列(参数2)，由 AI 拆分为 JSON 键值对并入库")
    public R<String> extractExtraDataByAi(@RequestParam Long titleId) {
        return R.success(dataCleaningService.startAiExtract(titleId));
    }

    @GetMapping("/ai-extract-progress/{titleId}")
    @Operation(summary = "获取 AI 提取进度", description = "WebSocket 不可用时作为轮询兜底")
    public R<Map<String, Object>> getAiExtractProgress(@PathVariable Long titleId) {
        return R.success(dataCleaningService.getAiExtractProgress(titleId));
    }

    // ==================== 数据清洗 ====================

    @PostMapping("/start")
    @Operation(summary = "启动批量数据清洗")
    public R<String> startCleaning(@RequestParam Long titleId,
                                   @RequestParam(required = false) Long parseRuleId,
                                   @RequestParam(required = false) Boolean useAi) {
        return R.success(dataCleaningService.startCleaning(titleId, parseRuleId, useAi));
    }

    @GetMapping("/progress/{titleId}")
    @Operation(summary = "获取清洗进度")
    public R<Map<String, Object>> getCleaningProgress(@PathVariable Long titleId) {
        return R.success(dataCleaningService.getCleaningProgress(titleId));
    }

    @PostMapping("/stop/{titleId}")
    @Operation(summary = "停止清洗任务")
    public R<Void> stopCleaning(@PathVariable Long titleId) {
        dataCleaningService.stopCleaning(titleId);
        return R.success();
    }

    @PostMapping("/reclean/{cleanedDataId}")
    @Operation(summary = "重新清洗数据")
    public R<CleanedDataEntity> recleanData(@PathVariable Long cleanedDataId) {
        return R.success(dataCleaningService.recleanData(cleanedDataId));
    }

    @PostMapping("/match")
    @Operation(summary = "单条数据分类匹配与清洗")
    public R<CleanedDataEntity> matchAndClean(@RequestParam Long tempDataId,
                                               @RequestParam(required = false) Long extraDataTitleId,
                                               @RequestParam(required = false) Long parseRuleId,
                                               @RequestParam(required = false) Boolean useAi) {
        return R.success(dataCleaningService.matchAndClean(tempDataId, extraDataTitleId, parseRuleId, useAi));
    }

    @PostMapping("/ai-classify-check")
    @Operation(summary = "AI 辅助分类检测", description = "将已清洗数据的分类结果与 main_data_category 标准库比对，给出准确性评分；useAi=true 且已配置 AI 时调用大模型，否则用规则校验。不执行 AI 部分可由 useAi=false 控制。")
    public R<Map<String, Object>> aiClassifyCheck(@RequestParam Long titleId,
                                                   @RequestParam(required = false, defaultValue = "false") Boolean useAi) {
        return R.success(dataCleaningService.aiClassifyCheck(titleId, useAi));
    }

    @PostMapping("/classify-text")
    @Operation(summary = "文本分类识别", description = "对一段物料描述文字复用 AI 辅助分类检测逻辑进行 AI 识别，返回推荐的分类名称、分类编码与理由。useAi 默认 true（未配置 AI 时退化为关键词匹配）。")
    public R<Map<String, Object>> classifyText(@RequestParam String text,
                                                @RequestParam(required = false, defaultValue = "true") Boolean useAi) {
        return R.success(dataCleaningService.classifyText(text, useAi));
    }

    @PostMapping("/ai-classify-check-async")
    @Operation(summary = "AI 辅助分类检测（异步，带进度）", description = "异步执行分类检测，通过 WebSocket 主题 /topic/ai-classify-check/{titleId} 实时推送进度（start/progress/complete/error）与每条明细，避免同步阻塞导致页面无响应。")
    public R<String> aiClassifyCheckAsync(@RequestParam Long titleId,
                                          @RequestParam(required = false, defaultValue = "false") Boolean useAi) {
        return R.success(dataCleaningService.aiClassifyCheckAsync(titleId, useAi));
    }

    @PostMapping("/apply-classify-fix")
    @Operation(summary = "应用分类修正", description = "将指定清洗数据的分类替换为推荐的标准分类编码（targetCode）并保存，替换后按标准库规则重新评分。")
    public R<Map<String, Object>> applyClassifyFix(@RequestParam Long id,
                                                    @RequestParam String targetCode) {
        return R.success(dataCleaningService.applyClassifyFix(id, targetCode));
    }

    @PostMapping("/apply-classify-fix-batch")
    @Operation(summary = "批量应用分类修正", description = "对一组 {id, code} 逐条将清洗数据的分类替换为推荐的标准分类编码并保存。请求体为 JSON 数组，例如 [{\"id\":1,\"code\":\"100105\"}]。")
    public R<Map<String, Object>> applyClassifyFixBatch(@RequestParam Long titleId,
                                                         @RequestBody(required = false) List<Map<String, Object>> items) {
        return R.success(dataCleaningService.applyClassifyFixBatch(titleId, items));
    }

    // ==================== 字段映射 ====================

    @PostMapping("/auto-map-fields")
    @Operation(summary = "自动映射字段（根据清洗数据的分类编码自动匹配标准字段表头）")
    public R<List<FieldMappingAuditEntity>> autoMapFields(@RequestParam Long tempDataTitleId,
                                                          @RequestParam(required = false) Long extraDataTitleId,
                                                          @RequestParam(required = false) Long standardTitleId) {
        return R.success(dataCleaningService.autoMapFields(tempDataTitleId, extraDataTitleId, standardTitleId));
    }

    @PutMapping("/field-mapping/{mappingId}")
    @Operation(summary = "更新字段映射")
    public R<FieldMappingAuditEntity> updateFieldMapping(@PathVariable Long mappingId,
                                                          @RequestParam String targetField) {
        return R.success(dataCleaningService.updateFieldMapping(mappingId, targetField));
    }

    @GetMapping("/field-mappings")
    @Operation(summary = "获取字段映射列表")
    public R<List<FieldMappingAuditEntity>> getFieldMappings(@RequestParam(required = false) Long standardTitleId,
                                                              @RequestParam Long tempDataTitleId,
                                                              @RequestParam(required = false) Long extraDataTitleId) {
        return R.success(dataCleaningService.getFieldMappings(standardTitleId, tempDataTitleId, extraDataTitleId));
    }

    @PostMapping("/field-mappings/batch")
    @Operation(summary = "批量保存手动字段映射（覆盖该标准表头+数据文件组合下的旧映射）")
    public R<List<FieldMappingAuditEntity>> saveManualMappings(@RequestParam Long standardTitleId,
                                                                @RequestParam Long tempDataTitleId,
                                                                @RequestParam(required = false) Long extraDataTitleId,
                                                                @RequestBody List<Map<String, Object>> mappings) {
        return R.success(dataCleaningService.saveManualMappings(standardTitleId, tempDataTitleId, extraDataTitleId, mappings));
    }

    // ==================== 结果数据填充 ====================

    @PostMapping("/fill-result")
    @Operation(summary = "填充结果数据")
    public R<List<ResultDataEntity>> fillResultData(@RequestParam Long standardTitleId,
                                                     @RequestParam Long tempDataTitleId,
                                                     @RequestParam(required = false) Long extraDataTitleId) {
        return R.success(dataCleaningService.fillResultData(standardTitleId, tempDataTitleId, extraDataTitleId));
    }

    @PostMapping("/fill-result/start")
    @Operation(summary = "异步填充结果数据（带 WebSocket 实时进度）")
    public R<String> startFill(@RequestParam Long standardTitleId,
                                @RequestParam Long tempDataTitleId,
                                @RequestParam(required = false) Long extraDataTitleId) {
        return R.success(dataCleaningService.startFill(standardTitleId, tempDataTitleId, extraDataTitleId));
    }

    @PostMapping("/fill-result/fill-all")
    @Operation(summary = "批量填充所有标准字段表头的映射结果数据")
    public R<String> fillAllStandardTitles(@RequestParam Long tempDataTitleId,
                                            @RequestParam(required = false) Long extraDataTitleId) {
        return R.success(dataCleaningService.fillAllStandardTitles(tempDataTitleId, extraDataTitleId));
    }

    @PutMapping("/result-data/{resultDataId}")
    @Operation(summary = "更新结果数据")
    public R<ResultDataEntity> updateResultData(@PathVariable Long resultDataId,
                                                 @RequestParam int colIndex,
                                                 @RequestParam String value) {
        return R.success(dataCleaningService.updateResultData(resultDataId, colIndex, value));
    }

    @PutMapping("/result-data/{resultDataId}/status")
    @Operation(summary = "更新结果数据状态（审核）")
    public R<Void> updateResultDataStatus(@PathVariable Long resultDataId,
                                           @RequestParam String status,
                                           @RequestParam(required = false) String comment) {
        dataCleaningService.updateResultDataStatus(resultDataId, status, comment);
        return R.success();
    }

    @PostMapping("/result-data/search")
    @Operation(summary = "搜索结果数据")
    public R<List<ResultDataEntity>> searchResultData(@RequestBody SearchCondition condition) {
        return R.success(dataCleaningService.searchResultData(condition));
    }

    @PostMapping("/result-data/count")
    @Operation(summary = "统计结果数据数量")
    public R<Long> countResultData(@RequestBody SearchCondition condition) {
        return R.success(dataCleaningService.countResultData(condition));
    }

    @GetMapping("/failed-results")
    @Operation(summary = "查询填充失败的结果数据", description = "查询因未匹配标准字段表头（standard_title_id 非空约束）而跳过并记录的数据；不传 titleId 时返回全部")
    public R<List<FailedResultDataEntity>> getFailedResults(@RequestParam(required = false) Long titleId) {
        return R.success(dataCleaningService.getFailedResults(titleId));
    }

    @GetMapping("/dashboard-statistics")
    @Operation(summary = "获取看板统计信息", description = "返回量化指标（文件数、总条数、成功/失败、分类匹配/不匹配）及饼图所需的分布数据；不传 titleId 时统计全部")
    public R<Map<String, Object>> getDashboardStatistics(@RequestParam(required = false) Long titleId) {
        return R.success(dataCleaningService.getDashboardStatistics(titleId));
    }

    @GetMapping("/unmatched-classify")
    @Operation(summary = "查询分类不匹配的清洗数据", description = "返回 match_source = UNMATCHED 的清洗数据，用于失败明细下钻；不传 titleId 时返回全部（限 500 条）")
    public R<List<CleanedDataEntity>> getUnmatchedClassify(@RequestParam(required = false) Long titleId) {
        return R.success(dataCleaningService.getUnmatchedClassify(titleId));
    }

    // ==================== 查询 ====================

    @PostMapping("/cleaned-data/search")
    @Operation(summary = "搜索清洗数据")
    public R<List<CleanedDataEntity>> searchCleanedData(@RequestBody SearchCondition condition) {
        return R.success(dataCleaningService.searchCleanedData(condition));
    }

    @GetMapping("/cleaned-data/by-title/{tempDataTitleId}")
    @Operation(summary = "根据原始数据表头ID获取清洗数据信息（用于字段映射）")
    public R<CleanedDataEntity> getCleanedDataByTempDataTitleId(@PathVariable Long tempDataTitleId) {
        return R.success(dataCleaningService.getCleanedDataByTempDataTitleId(tempDataTitleId));
    }

    @GetMapping("/standard-title-id/by-title/{tempDataTitleId}")
    @Operation(summary = "根据原始数据表头ID获取对应的标准字段表头ID（与autoMapFields使用相同的查询路径）")
    public R<Long> getStandardTitleIdByTempDataTitleId(@PathVariable Long tempDataTitleId) {
        return R.success(dataCleaningService.getStandardTitleIdByTempDataTitleId(tempDataTitleId));
    }

    @PostMapping("/cleaned-data/count")
    @Operation(summary = "统计清洗数据数量")
    public R<Long> countCleanedData(@RequestBody SearchCondition condition) {
        return R.success(dataCleaningService.countCleanedData(condition));
    }

    @GetMapping("/temp-data/{titleId}")
    @Operation(summary = "获取原始数据列表")
    public R<List<TempDataEntity>> getTempDataList(@PathVariable Long titleId) {
        return R.success(dataCleaningService.getTempDataList(titleId));
    }

    @GetMapping("/temp-data/{titleId}/page")
    @Operation(summary = "分页获取原始数据")
    public R<Map<String, Object>> getTempDataPage(@PathVariable Long titleId,
                                                   @RequestParam(defaultValue = "1") int page,
                                                   @RequestParam(defaultValue = "20") int pageSize) {
        return R.success(dataCleaningService.getTempDataPage(titleId, page, pageSize));
    }

    @GetMapping("/temp-data/by-id/{id}")
    @Operation(summary = "根据ID获取单条原始数据（含表头）", description = "用于结果数据页面查看某条结果对应的原始数据")
    public R<Map<String, Object>> getTempDataById(@PathVariable Long id) {
        return R.success(dataCleaningService.getTempDataById(id));
    }

    @GetMapping("/extra-data/{extraDataTitleId}")
    @Operation(summary = "获取补充数据列表")
    public R<List<ExtraDataEntity>> getExtraDataList(@PathVariable Long extraDataTitleId) {
        return R.success(dataCleaningService.getExtraDataList(extraDataTitleId));
    }

    @GetMapping("/extra-titles")
    @Operation(summary = "获取补充数据表头列表")
    public R<List<ExtraDataTitleEntity>> getExtraDataTitles() {
        return R.success(dataCleaningService.getExtraDataTitles());
    }

    @GetMapping("/standard-titles")
    @Operation(summary = "获取标准字段表头列表")
    public R<List<StandardTitleEntity>> getAllStandardTitles() {
        return R.success(dataCleaningService.getAllStandardTitles());
    }

    @GetMapping("/standard-titles/page")
    @Operation(summary = "分页查询标准字段表头", description = "支持按分类编码关键字搜索，用于标准列表页面分页展示")
    public R<IPage<StandardTitleEntity>> pageStandardTitles(@RequestParam(defaultValue = "1") long page,
                                                            @RequestParam(defaultValue = "10") long size,
                                                            @RequestParam(required = false) String keyword) {
        return R.success(dataCleaningService.pageStandardTitles(page, size, keyword));
    }

    @GetMapping("/standard-titles/by-title")
    @Operation(summary = "按数据文件查询其关联的标准字段表头（结果数据下拉框用）")
    public R<List<StandardTitleEntity>> getStandardTitlesByTitle(@RequestParam Long tempDataTitleId) {
        return R.success(dataCleaningService.getStandardTitlesByTitleId(tempDataTitleId));
    }

    @GetMapping("/standard-title/{id}")
    @Operation(summary = "获取标准字段表头详情")
    public R<StandardTitleEntity> getStandardTitleById(@PathVariable Long id) {
        return R.success(dataCleaningService.getStandardTitleById(id));
    }

    @PostMapping("/standard-title")
    @Operation(summary = "创建标准字段表头")
    public R<StandardTitleEntity> createStandardTitle(@RequestBody StandardTitleEntity entity) {
        return R.success(dataCleaningService.createStandardTitle(entity));
    }

    @PutMapping("/standard-title/{id}")
    @Operation(summary = "更新标准字段表头")
    public R<StandardTitleEntity> updateStandardTitle(@PathVariable Long id, @RequestBody StandardTitleEntity entity) {
        entity.setId(id);
        return R.success(dataCleaningService.updateStandardTitle(entity));
    }

    @DeleteMapping("/standard-title/{id}")
    @Operation(summary = "删除标准字段表头")
    public R<Void> deleteStandardTitle(@PathVariable Long id) {
        dataCleaningService.deleteStandardTitle(id);
        return R.success();
    }

    // ==================== 统计 ====================

    @GetMapping("/statistics")
    @Operation(summary = "获取清洗统计信息")
    public R<Map<String, Object>> getCleaningStatistics(@RequestParam(required = false) Long titleId) {
        return R.success(dataCleaningService.getCleaningStatistics(titleId));
    }

    @GetMapping("/quality-report")
    @Operation(summary = "获取质量报告")
    public R<Map<String, Object>> getQualityReport(@RequestParam(required = false) Long titleId) {
        return R.success(dataCleaningService.getQualityReport(titleId));
    }

    // ==================== 未映射结果 ====================

    @GetMapping("/unmapped-results")
    @Operation(summary = "查询未映射的清洗结果", description = "查询已清洗但尚未被映射填充到结果数据中的记录")
    public R<List<CleanedDataEntity>> getUnmappedResults(@RequestParam Long titleId) {
        return R.success(dataCleaningService.getUnmappedResults(titleId));
    }

    @GetMapping("/unmapped-results/count")
    @Operation(summary = "统计未映射结果数量")
    public R<Long> countUnmappedResults(@RequestParam Long titleId) {
        return R.success(dataCleaningService.countUnmappedResults(titleId));
    }
}
