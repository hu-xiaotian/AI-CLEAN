package com.aiclean.service;

import com.aiclean.entity.*;
import com.aiclean.model.ParseRule;
import com.aiclean.model.SearchCondition;
import com.baomidou.mybatisplus.core.metadata.IPage;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * 数据清洗服务接口
 */
public interface DataCleaningService {

    // ===== Excel导入 =====
    TempDataTitleEntity importExcel(MultipartFile file);
    void deleteImportTitle(Long titleId);

    // ===== 解析规则管理 =====
    ParseRuleEntity createParseRule(ParseRuleEntity rule);
    ParseRuleEntity updateParseRule(ParseRuleEntity rule);
    void deleteParseRule(Long ruleId);
    ParseRuleEntity getParseRuleById(Long ruleId);
    List<ParseRuleEntity> getActiveParseRules();

    // ===== 全描述解析 =====
    void deleteExtraTitle(Long extraTitleId);

    // ===== AI 智能提取 =====
    String startAiExtract(Long titleId, String customName);
    Map<String, Object> getAiExtractProgress(Long titleId);

    // ===== 分类匹配（固定 AI 分类，无规则分类开关） =====
    CleanedDataEntity matchAndClean(Long tempDataId, Long extraDataTitleId, Long parseRuleId);

    // ===== 文本分类识别（供 AI 聊天使用）：对一段物料描述文字进行 AI 识别，返回推荐分类/编码/理由 =====
    Map<String, Object> classifyText(String text);

    // ===== 数据清洗（固定 AI 分类，无 useAi 开关） =====
    String startCleaning(Long titleId, Long parseRuleId, Integer batchSize);
    Map<String, Object> getCleaningProgress(Long titleId);
    void stopCleaning(Long titleId);
    CleanedDataEntity recleanData(Long cleanedDataId);

    // ===== 智能分类人工修正 =====
    /** 修改单条清洗数据的分类（重新匹配标准库三级）与备注，状态置为已修改；分类为空则仅保存备注 */
    CleanedDataEntity updateCleanedDataCategory(Long id, String categoryCode, String categoryName, String remark);
    /** 模糊搜索标准分类（按名称/编码，供智能分类页双击分类名称下拉选择），返回三级分类 */
    List<Map<String, Object>> searchCategories(String keyword, int limit);

    // ===== 查询 =====
    List<CleanedDataEntity> searchCleanedData(SearchCondition condition);
    long countCleanedData(SearchCondition condition);
    List<TempDataEntity> getTempDataList(Long titleId);
    Map<String, Object> getTempDataPage(Long titleId, int page, int pageSize);
    Map<String, Object> getTempDataById(Long id);
    List<ExtraDataEntity> getExtraDataList(Long extraDataTitleId);
    List<ExtraDataTitleEntity> getExtraDataTitles();
    /** 属性提取结果层级视图：文件 -> 分类 -> 属性列表 */
    Map<String, Object> getExtractResultTree(Long tempDataTitleId, Long extraDataTitleId);
    /** AI 智能提取任务列表（文件名/行数/状态/起止时间/耗时） */
    List<Map<String, Object>> getAiExtractTaskList();
    /** 获取单条提取明细：源数据 + 提取属性 + 列标题，用于查看与修改 */
    Map<String, Object> getExtraRowDetail(Long extraDataId);
    /** 修改单条提取明细的提取属性（partial update col1~col20） */
    void updateExtraRow(Long extraDataId, java.util.Map<String, String> cols);
    CleanedDataEntity getCleanedDataByTempDataTitleId(Long tempDataTitleId);
    Long getStandardTitleIdByTempDataTitleId(Long tempDataTitleId);
    // ===== 标准字段表头管理 =====
    StandardTitleEntity createStandardTitle(StandardTitleEntity entity);
    StandardTitleEntity updateStandardTitle(StandardTitleEntity entity);
    void deleteStandardTitle(Long id);
    StandardTitleEntity getStandardTitleById(Long id);
    List<StandardTitleEntity> getAllStandardTitles();
    IPage<StandardTitleEntity> pageStandardTitles(long page, long size, String keyword, String sortOrder);

    List<ResultDataEntity> searchResultData(SearchCondition condition);
    long countResultData(SearchCondition condition);

    /** 导出（单 Sheet）结果数据：表头为 行号 + 原始数据列（前置）+ 结果属性列，合并为一个 .xlsx 字节流 */
    byte[] exportResultData(Long standardTitleId, int page, int pageSize) throws IOException;

    /** 查询某数据文件下填充失败的结果数据（未匹配标准表头） */
    List<FailedResultDataEntity> getFailedResults(Long titleId);

    // ===== 统计 =====
    Map<String, Object> getCleaningStatistics(Long titleId);
    Map<String, Object> getQualityReport(Long titleId);

    // ===== 看板统计 =====
    /** 看板量化指标统计：文件数、总条数、成功/失败、分类匹配/不匹配、状态与分类分布等 */
    Map<String, Object> getDashboardStatistics(Long titleId);
    /** 查询分类不匹配（match_source = UNMATCHED）的清洗数据，用于失败明细下钻 */
    List<CleanedDataEntity> getUnmatchedClassify(Long titleId);
    /** 查询重复数据（is_duplicate=1），用于看板下钻；不传 titleId 时返回全部 */
    List<CleanedDataEntity> getDuplicateData(Long titleId);
    /** 查询低置信样本（active_learning_sample sample_type=LOW_CONFIDENCE），用于看板下钻；不传 titleId 时返回全部 */
    List<ActiveLearningSampleEntity> getLowConfidenceSamples(Long titleId);

    // ===== 未映射结果 =====
    List<CleanedDataEntity> getUnmappedResults(Long titleId);
    long countUnmappedResults(Long titleId);
}
