package com.aiclean.service;

import com.aiclean.entity.*;
import com.aiclean.model.ParseRule;
import com.aiclean.model.SearchCondition;
import com.baomidou.mybatisplus.core.metadata.IPage;
import org.springframework.web.multipart.MultipartFile;

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
    ExtraDataTitleEntity extractExtraData(Long titleId, Long parseRuleId);
    void deleteExtraTitle(Long extraTitleId);

    // ===== 分类匹配 =====
    CleanedDataEntity matchAndClean(Long tempDataId, Long extraDataTitleId, Long parseRuleId);

    // ===== 数据清洗 =====
    String startCleaning(Long titleId, Long parseRuleId);
    Map<String, Object> getCleaningProgress(Long titleId);
    void stopCleaning(Long titleId);
    CleanedDataEntity recleanData(Long cleanedDataId);

    // ===== 字段映射 =====
    List<FieldMappingAuditEntity> autoMapFields(Long tempDataTitleId, Long extraDataTitleId, Long standardTitleId);
    FieldMappingAuditEntity updateFieldMapping(Long mappingId, String targetField);
    List<FieldMappingAuditEntity> getFieldMappings(Long standardTitleId, Long tempDataTitleId, Long extraDataTitleId);
    List<FieldMappingAuditEntity> saveManualMappings(Long standardTitleId, Long tempDataTitleId, Long extraDataTitleId, List<Map<String, Object>> mappings);

    // ===== 结果数据填充 =====
    List<ResultDataEntity> fillResultData(Long standardTitleId, Long tempDataTitleId, Long extraDataTitleId);
    String startFill(Long standardTitleId, Long tempDataTitleId, Long extraDataTitleId);
    String fillAllStandardTitles(Long tempDataTitleId, Long extraDataTitleId);
    ResultDataEntity updateResultData(Long resultDataId, int colIndex, String value);
    void updateResultDataStatus(Long resultDataId, String status, String comment);

    // ===== 查询 =====
    List<CleanedDataEntity> searchCleanedData(SearchCondition condition);
    long countCleanedData(SearchCondition condition);
    List<TempDataEntity> getTempDataList(Long titleId);
    Map<String, Object> getTempDataPage(Long titleId, int page, int pageSize);
    List<ExtraDataEntity> getExtraDataList(Long extraDataTitleId);
    List<ExtraDataTitleEntity> getExtraDataTitles();
    CleanedDataEntity getCleanedDataByTempDataTitleId(Long tempDataTitleId);
    Long getStandardTitleIdByTempDataTitleId(Long tempDataTitleId);
    // ===== 标准字段表头管理 =====
    StandardTitleEntity createStandardTitle(StandardTitleEntity entity);
    StandardTitleEntity updateStandardTitle(StandardTitleEntity entity);
    void deleteStandardTitle(Long id);
    StandardTitleEntity getStandardTitleById(Long id);
    List<StandardTitleEntity> getAllStandardTitles();
    IPage<StandardTitleEntity> pageStandardTitles(long page, long size, String keyword);

    // ===== 数据文件-标准表头关联 =====
    /** 记录某数据文件关联了某个标准字段表头（幂等，已存在则跳过） */
    void recordTitleStandardTitle(Long tempDataTitleId, Long standardTitleId);
    /** 查询某数据文件关联的标准字段表头（首次查询时从已填充结果懒回填） */
    List<StandardTitleEntity> getStandardTitlesByTitleId(Long tempDataTitleId);

    List<ResultDataEntity> searchResultData(SearchCondition condition);
    long countResultData(SearchCondition condition);

    /** 查询某数据文件下填充失败的结果数据（未匹配标准表头） */
    List<FailedResultDataEntity> getFailedResults(Long titleId);

    // ===== 统计 =====
    Map<String, Object> getCleaningStatistics(Long titleId);
    Map<String, Object> getQualityReport(Long titleId);

    // ===== 未映射结果 =====
    List<CleanedDataEntity> getUnmappedResults(Long titleId);
    long countUnmappedResults(Long titleId);
}
