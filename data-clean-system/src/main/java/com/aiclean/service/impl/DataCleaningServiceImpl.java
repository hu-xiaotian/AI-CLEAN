package com.aiclean.service.impl;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson.JSON;
import com.aiclean.entity.*;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.aiclean.entity.enums.DataStatus;
import com.aiclean.mapper.*;
import com.aiclean.match.*;
import com.aiclean.model.ParseRule;
import com.aiclean.model.SearchCondition;
import com.aiclean.service.DataCleaningService;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 数据清洗服务实现类
 */
@Slf4j
@Service
public class DataCleaningServiceImpl implements DataCleaningService {

    @Autowired private TempDataTitleMapper tempDataTitleMapper;
    @Autowired private TempDataMapper tempDataMapper;
    @Autowired private CleanedDataMapper cleanedDataMapper;
    @Autowired private CategoryMapper categoryMapper;
    @Autowired private ReviewTaskMapper reviewTaskMapper;
    @Autowired private ExtraDataTitleMapper extraDataTitleMapper;
    @Autowired private ExtraDataMapper extraDataMapper;
    @Autowired private StandardTitleMapper standardTitleMapper;
    @Autowired private ResultDataMapper resultDataMapper;
    @Autowired private FailedResultDataMapper failedResultDataMapper;
    @Autowired private ParseRuleMapper parseRuleMapper;
    @Autowired private FieldMappingAuditMapper fieldMappingAuditMapper;
    @Autowired private TitleStandardTitleMapper titleStandardTitleMapper;
    @Autowired private CategorySynonymMapper categorySynonymMapper;
    @Autowired private CategoryMatcher categoryMatcher;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private SimpMessagingTemplate messagingTemplate;

    @Value("${app.file.upload-path}") private String uploadPath;
    @Value("${app.data-cleaning.batch-size}") private int batchSize;
    @Value("${app.data-cleaning.quality-score.threshold-review}") private double thresholdReview;
    @Value("${app.data-cleaning.quality-score.threshold-export}") private double thresholdExport;

    // ==================== Excel导入 ====================

    @Override
    @Transactional
    public TempDataTitleEntity importExcel(MultipartFile file) {
        log.info("开始导入Excel文件: {}", file.getOriginalFilename());
        try {
            String fileName = saveUploadFile(file);
            TempDataTitleEntity titleEntity = new TempDataTitleEntity();
            titleEntity.setFileName(file.getOriginalFilename());
            titleEntity.setUploadTime(LocalDateTime.now().toString());
            titleEntity.setStatus(DataStatus.DRAFT);
            titleEntity.setTotalRows(0);

            Workbook workbook = WorkbookFactory.create(file.getInputStream());
            Sheet sheet = workbook.getSheetAt(0);

            Row headerRow = sheet.getRow(0);
            if (headerRow == null) {
                throw new RuntimeException("Excel文件第一行（表头）为空");
            }

            int colCount = Math.min(headerRow.getLastCellNum(), 10);
            for (int i = 0; i < colCount; i++) {
                Cell cell = headerRow.getCell(i);
                String header = cell != null ? cell.toString().trim() : "col" + (i + 1);
                titleEntity.setColTitle(i + 1, header);
                if (header.contains("全描述") || header.contains("完整描述")) {
                    titleEntity.setFullDescCol(header);
                }
                if (header.contains("类别") || header.contains("分类")) {
                    titleEntity.setCategoryCol(header);
                }
            }

            tempDataTitleMapper.insert(titleEntity);
            log.info("表头保存成功，ID: {}", titleEntity.getId());

            List<TempDataEntity> dataList = new ArrayList<>();
            int rowCount = 0;

            for (int rowIndex = 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
                Row dataRow = sheet.getRow(rowIndex);
                if (dataRow == null) continue;

                TempDataEntity tempData = new TempDataEntity();
                tempData.setTempDataTitleId(titleEntity.getId());
                tempData.setRowIndex(rowIndex + 1);
                tempData.setStatus(DataStatus.DRAFT);

                for (int colIndex = 0; colIndex < colCount && colIndex < 10; colIndex++) {
                    Cell cell = dataRow.getCell(colIndex);
                    String cellValue = getCellValue(cell);
                    tempData.setColData(colIndex + 1, cellValue);
                }

                dataList.add(tempData);
                rowCount++;

                if (dataList.size() >= batchSize) {
                    tempDataMapper.insertBatch(dataList);
                    dataList.clear();
                }
            }

            if (!dataList.isEmpty()) {
                tempDataMapper.insertBatch(dataList);
            }

            titleEntity.setTotalRows(rowCount);
            tempDataTitleMapper.updateById(titleEntity);
            workbook.close();

            log.info("Excel文件导入成功: {}行，表头ID: {}", rowCount, titleEntity.getId());
            return titleEntity;
        } catch (Exception e) {
            log.error("导入Excel文件失败", e);
            throw new RuntimeException("导入Excel文件失败: " + e.getMessage());
        }
    }

    @Override
    @Transactional
    public void deleteImportTitle(Long titleId) {
        log.info("开始级联删除导入数据，表头ID: {}", titleId);

        TempDataTitleEntity titleEntity = tempDataTitleMapper.selectById(titleId);
        if (titleEntity == null) {
            throw new RuntimeException("导入数据不存在: " + titleId);
        }

        // 1. 删除结果数据 (result_data)
        int resultCount = resultDataMapper.deleteByTitleId(titleId);
        log.info("删除结果数据: {} 条", resultCount);

        // 2. 删除字段映射审核 (field_mapping_audit)
        int mappingCount = fieldMappingAuditMapper.deleteByTitleId(titleId);
        log.info("删除字段映射: {} 条", mappingCount);

        // 3. 删除清洗数据 (cleaned_data)
        int cleanedCount = cleanedDataMapper.deleteByTitleId(titleId);
        log.info("删除清洗数据: {} 条", cleanedCount);

        // 4. 删除补充数据详情 (extra_data) - 通过子查询一步到位
        int extraDataCount = extraDataMapper.deleteByTempDataTitleId(titleId);
        log.info("删除补充数据详情: {} 条", extraDataCount);

        // 5. 删除补充数据表头 (extra_data_title)
        int extraTitleCount = extraDataTitleMapper.deleteByTempDataTitleId(titleId);
        log.info("删除补充数据表头: {} 条", extraTitleCount);

        // 6. 删除原始数据行 (temp_data)
        int tempDataCount = tempDataMapper.deleteByTitleId(titleId);
        log.info("删除原始数据行: {} 条", tempDataCount);

        // 7. 删除原始数据表头 (temp_data_title)
        tempDataTitleMapper.deleteById(titleId);
        log.info("删除原始数据表头: ID={}, 文件名={}", titleId, titleEntity.getFileName());

        log.info("级联删除完成，表头ID: {}", titleId);
    }

    // ==================== 解析规则管理 ====================

    @Override
    @Transactional
    public ParseRuleEntity createParseRule(ParseRuleEntity rule) {
        parseRuleMapper.insert(rule);
        return rule;
    }

    @Override
    @Transactional
    public ParseRuleEntity updateParseRule(ParseRuleEntity rule) {
        parseRuleMapper.updateById(rule);
        return rule;
    }

    @Override
    @Transactional
    public void deleteParseRule(Long ruleId) {
        parseRuleMapper.deleteById(ruleId);
    }

    @Override
    public ParseRuleEntity getParseRuleById(Long ruleId) {
        return parseRuleMapper.selectById(ruleId);
    }

    @Override
    public List<ParseRuleEntity> getActiveParseRules() {
        return parseRuleMapper.selectActiveRules();
    }

    // ==================== 全描述解析 ====================

    @Override
    @Transactional
    public ExtraDataTitleEntity extractExtraData(Long titleId, Long parseRuleId) {
        log.info("开始提取全描述属性，表头ID: {}, 规则ID: {}", titleId, parseRuleId);
        TempDataTitleEntity titleEntity = tempDataTitleMapper.selectById(titleId);
        if (titleEntity == null) throw new RuntimeException("表头不存在: " + titleId);

        ParseRuleEntity ruleEntity = parseRuleMapper.selectById(parseRuleId);
        if (ruleEntity == null) throw new RuntimeException("解析规则不存在: " + parseRuleId);
        ParseRule parseRule = ruleEntity.toParseRule();

        String fullDescCol = titleEntity.getFullDescCol();
        int fullDescIndex = findFullDescColIndex(titleEntity, fullDescCol);
        if (fullDescIndex <= 0) {
            throw new RuntimeException("未找到全描述列");
        }

        List<TempDataEntity> tempDataList = tempDataMapper.selectByTitleId(titleId);
        log.info("待解析数据量: {}", tempDataList.size());

        // 收集所有key作为extra_data_title的列
        Set<String> allKeys = new LinkedHashSet<>();
        List<Map<String, String>> allParsed = new ArrayList<>();

        for (TempDataEntity tempData : tempDataList) {
            String fullDesc = tempData.getColData(fullDescIndex);
            if (StrUtil.isNotBlank(fullDesc)) {
                Map<String, String> parsed = parseRule.parse(fullDesc);
                allParsed.add(parsed);
                allKeys.addAll(parsed.keySet());
            } else {
                allParsed.add(new LinkedHashMap<>());
            }
        }

        // 限制最多20个属性
        List<String> keyList = new ArrayList<>(allKeys);
        if (keyList.size() > 20) {
            keyList = keyList.subList(0, 20);
        }

        // 创建extra_data_title
        ExtraDataTitleEntity extraTitle = new ExtraDataTitleEntity();
        extraTitle.setTempDataTitleId(titleId);
        extraTitle.setParseRuleId(parseRuleId);
        for (int i = 0; i < keyList.size(); i++) {
            extraTitle.setColTitle(i + 1, keyList.get(i));
        }
        extraDataTitleMapper.insert(extraTitle);

        // 创建extra_data
        List<ExtraDataEntity> extraDataList = new ArrayList<>();
        for (int i = 0; i < tempDataList.size() && i < allParsed.size(); i++) {
            TempDataEntity tempData = tempDataList.get(i);
            Map<String, String> parsed = allParsed.get(i);
            ExtraDataEntity extraData = new ExtraDataEntity();
            extraData.setExtraDataTitleId(extraTitle.getId());
            extraData.setTempDataId(tempData.getId());

            for (int j = 0; j < keyList.size(); j++) {
                extraData.setColData(j + 1, parsed.getOrDefault(keyList.get(j), ""));
            }
            extraDataList.add(extraData);

            if (extraDataList.size() >= batchSize) {
                extraDataMapper.insertBatch(extraDataList);
                extraDataList.clear();
            }
        }
        if (!extraDataList.isEmpty()) {
            extraDataMapper.insertBatch(extraDataList);
        }

        log.info("全描述属性提取完成，提取属性数: {}, 数据量: {}", keyList.size(), tempDataList.size());
        return extraTitle;
    }

    @Override
    @Transactional
    public void deleteExtraTitle(Long extraTitleId) {
        log.info("开始删除全描述提取结果，extraTitleId: {}", extraTitleId);

        ExtraDataTitleEntity extraTitle = extraDataTitleMapper.selectById(extraTitleId);
        if (extraTitle == null) {
            throw new RuntimeException("全描述提取结果不存在: " + extraTitleId);
        }

        // 1. 删除补充数据详情 (extra_data)
        int extraDataCount = extraDataMapper.deleteByExtraDataTitleId(extraTitleId);
        log.info("删除补充数据详情: {} 条", extraDataCount);

        // 2. 删除补充数据表头 (extra_data_title)
        extraDataTitleMapper.deleteById(extraTitleId);
        log.info("删除补充数据表头: ID={}", extraTitleId);

        log.info("全描述提取结果删除完成，extraTitleId: {}", extraTitleId);
    }

    // ==================== 分类匹配与清洗 ====================

    @Override
    @Transactional
    public CleanedDataEntity matchAndClean(Long tempDataId, Long extraDataTitleId, Long parseRuleId) {
        TempDataEntity tempData = tempDataMapper.selectById(tempDataId);
        if (tempData == null) throw new RuntimeException("原始数据不存在: " + tempDataId);

        TempDataTitleEntity titleEntity = tempDataTitleMapper.selectById(tempData.getTempDataTitleId());
        ParseRuleEntity ruleEntity = parseRuleMapper.selectById(parseRuleId);
        ParseRule parseRule = ruleEntity != null ? ruleEntity.toParseRule() : getDefaultParseRule();

        // 获取extra_data
        ExtraDataEntity extraData = null;
        if (extraDataTitleId != null) {
            extraData = extraDataMapper.selectByTempDataId(tempDataId, extraDataTitleId);
        }

        // 解析全描述
        String fullDescription = "";
        if (titleEntity != null && StrUtil.isNotBlank(titleEntity.getFullDescCol())) {
            int idx = findFullDescColIndex(titleEntity, titleEntity.getFullDescCol());
            if (idx > 0) {
                fullDescription = tempData.getColData(idx);
            }
        }
        Map<String, String> extraAttrs = parseRule.parse(fullDescription);

        // 分类匹配（独立匹配模块，未命中三级则不赋值）
        CategoryMatchOutcome matchResult = matchCategory(tempData, extraAttrs);
        CategoryEntity matchedCategory = matchResult.getCategory();

        // 创建清洗数据
        CleanedDataEntity cleanedData = new CleanedDataEntity();
        cleanedData.setTempDataId(tempData.getId());
        cleanedData.setMatchSource(matchResult.getSource());
        cleanedData.setMatchConfidence(matchResult.getConfidence());
        if (matchedCategory != null) {
            cleanedData.setCategoryId(matchedCategory.getId());
            cleanedData.setCategoryCode(matchedCategory.getCategoryCode());
            cleanedData.setCategoryLevel(matchedCategory.getLevel());
            cleanedData.setCategoryFullPath(matchedCategory.getFullPath());
        }

        // 提取核心字段
        cleanedData.setMaterialCode(getColByTitle(titleEntity, "物料代码", tempData, 3));
        cleanedData.setMaterialName(extraAttrs.getOrDefault("物资名称", extraAttrs.getOrDefault("物资简称", "")));
        cleanedData.setSpecification(extraAttrs.getOrDefault("规格", ""));
        cleanedData.setTechnicalStandard(extraAttrs.getOrDefault("技术标准号", ""));
        cleanedData.setGrade(extraAttrs.getOrDefault("牌号", ""));
        cleanedData.setUnit(extraAttrs.getOrDefault("计量单位", getColByTitle(titleEntity, "计量单位", tempData, 6)));

        // 质量评分
        cleanedData.setCompletenessScore(cleanedData.calculateCompleteness());
        double qualityScore = calculateQuality(cleanedData);
        cleanedData.setQualityScore(qualityScore);
        cleanedData.setAccuracyScore(qualityScore * 0.8);

        if (qualityScore < thresholdReview) {
            cleanedData.setStatus(DataStatus.NEEDS_REVIEW);
        } else if (qualityScore >= thresholdExport) {
            cleanedData.setStatus(DataStatus.EXPORT_READY);
        } else {
            cleanedData.setStatus(DataStatus.APPROVED);
        }

        // 分类未命中三级：不赋一/二级编码，标记为待审核（无效数据页统计）
        if (matchedCategory == null
                || "UNMATCHED".equals(matchResult.getSource())) {
            cleanedData.setStatus(DataStatus.NEEDS_REVIEW);
        }

        cleanedDataMapper.insert(cleanedData);

        // 入库后再创建审核任务，确保能拿到自增主键 entity_id
        if (qualityScore < thresholdReview) {
            createReviewTask(cleanedData, "质量评分过低: " + qualityScore);
        }

        // 更新原始数据状态
        tempData.setStatus(DataStatus.PROCESSED);
        tempDataMapper.updateById(tempData);

        return cleanedData;
    }

    // ==================== 批量数据清洗 ====================

    @Override
    @Async
    public String startCleaning(Long titleId, Long parseRuleId) {
        log.info("开始数据清洗，表头ID: {}, 规则ID: {}", titleId, parseRuleId);
        doStartCleaning(titleId, parseRuleId);
        return "cleaning_task_" + titleId;
    }

    /**
     * 实际的清洗执行逻辑，由 startCleaning 异步调用
     * 使用 TransactionTemplate 确保在异步线程中事务正确生效
     */
    public void doStartCleaning(Long titleId, Long parseRuleId) {
        transactionTemplate.executeWithoutResult(status -> {
            try {
                log.info("异步清洗任务开始执行，表头ID: {}, 规则ID: {}", titleId, parseRuleId);

                // 0. 如果该批次已清洗过，先清理旧数据，确保重新生成
                cleanPreviousCleaningData(titleId);

                // 1. 预加载共享数据，避免循环内重复查询
                // 说明：全描述属性提取（extractExtraData）已拆分为独立步骤，
                // 不再在智能分类时自动执行，需由调用方（如一键数据清洗）显式触发。
                TempDataTitleEntity titleEntity = tempDataTitleMapper.selectById(titleId);
                ParseRuleEntity ruleEntity = parseRuleMapper.selectById(parseRuleId);
                ParseRule parseRule = ruleEntity != null ? ruleEntity.toParseRule() : getDefaultParseRule();
                List<CategoryEntity> allCategories = categoryMapper.selectList(null);
                List<CategorySynonymEntity> synonyms = categorySynonymMapper.selectList(null);
                log.info("预加载完成，分类总数: {}, 同义词数: {}, 待清洗数据: {}", allCategories.size(), synonyms.size(), tempDataMapper.countByTitleId(titleId));

                // 2. 批量匹配清洗
                List<TempDataEntity> tempDataList = tempDataMapper.selectByTitleId(titleId);
                int successCount = 0;
                int errorCount = 0;
                int consecutiveErrors = 0;
                final int maxConsecutiveErrors = 5;
                final int totalCount = tempDataList.size();

                // 发送开始消息
                sendCleaningProgress(titleId, "start", 0, totalCount, null, successCount, errorCount);

                for (int i = 0; i < tempDataList.size(); i++) {
                    try {
                        CleanedDataEntity cleaned = matchAndCleanInternal(tempDataList.get(i), null, parseRule,
                                titleEntity, allCategories, synonyms);
                        successCount++;
                        consecutiveErrors = 0;

                        // 每清洗一条数据，通过 WebSocket 推送进度
                        sendCleaningProgress(titleId, "progress", i + 1, totalCount, cleaned, successCount, errorCount);
                    } catch (Exception e) {
                        errorCount++;
                        consecutiveErrors++;
                        log.error("清洗数据失败，tempDataId: {}", tempDataList.get(i).getId(), e);

                        // 推送错误信息
                        sendCleaningProgress(titleId, "progress", i + 1, totalCount, null, successCount, errorCount);

                        if (consecutiveErrors >= maxConsecutiveErrors) {
                            log.error("连续失败 {} 次，停止清洗任务。已处理: {}/{}, 成功: {}, 失败: {}",
                                    maxConsecutiveErrors, i + 1, totalCount, successCount, errorCount);
                            sendCleaningProgress(titleId, "error", i + 1, totalCount, null, successCount, errorCount);
                            throw new RuntimeException(
                                    String.format("数据清洗异常终止：连续 %d 次失败（已处理 %d/%d 条）",
                                            maxConsecutiveErrors, i + 1, totalCount));
                        }
                    }
                }

                // 更新表头状态
                titleEntity.setStatus(DataStatus.COMPLETED);
                tempDataTitleMapper.updateById(titleEntity);

                // 发送完成消息
                sendCleaningProgress(titleId, "complete", totalCount, totalCount, null, successCount, errorCount);

                log.info("数据清洗完成，成功: {}, 失败: {}", successCount, errorCount);
            } catch (Exception e) {
                log.error("数据清洗任务执行失败，表头ID: {}", titleId, e);
                // 推送错误消息
                sendCleaningProgress(titleId, "error", 0, 0, null, 0, 0);
                try {
                    TempDataTitleEntity titleEntity = tempDataTitleMapper.selectById(titleId);
                    if (titleEntity != null) {
                        titleEntity.setStatus(DataStatus.REJECTED);
                        tempDataTitleMapper.updateById(titleEntity);
                    }
                } catch (Exception ex) {
                    log.error("更新失败状态时出错", ex);
                }
                status.setRollbackOnly();
            }
        });
    }

    /**
     * 清理该批次之前清洗产生的所有数据（result_data、cleaned_data、extra_data、extra_data_title），
     * 并重置 temp_data 状态，以便重新清洗时避免数据重复。
     */
    private void cleanPreviousCleaningData(Long titleId) {
        // 按依赖顺序清理：先清理下游数据，再清理上游数据
        int resultCount = resultDataMapper.deleteByTitleId(titleId);
        int reviewCount = reviewTaskMapper.deleteByCleanedDataTitleId(titleId);
        int cleanedCount = cleanedDataMapper.deleteByTitleId(titleId);
        int extraDataCount = extraDataMapper.deleteByTempDataTitleId(titleId);
        int extraTitleCount = extraDataTitleMapper.deleteByTempDataTitleId(titleId);

        // 重置原始数据状态为草稿（DRAFT），让前端看到的是"待处理"状态
        tempDataMapper.updateStatusByTitleId(titleId, DataStatus.DRAFT.getCode());

        if (cleanedCount > 0 || extraDataCount > 0) {
            log.info("清理旧清洗数据完成，表头ID: {}, 清洗数据: {}, 补充数据: {}, 补充表头: {}, 审核任务: {}, 结果数据: {}",
                    titleId, cleanedCount, extraDataCount, extraTitleCount, reviewCount, resultCount);
        } else {
            log.info("未发现旧清洗数据，表头ID: {}", titleId);
        }
    }

    /**
     * 内部清洗方法，接受预加载的缓存数据，避免循环内重复DB查询
     */
    private CleanedDataEntity matchAndCleanInternal(TempDataEntity tempData, Long extraDataTitleId,
                                                     ParseRule parseRule, TempDataTitleEntity titleEntity,
                                                      List<CategoryEntity> allCategories,
                                                      List<CategorySynonymEntity> synonyms) {
        // 获取extra_data
        ExtraDataEntity extraData = null;
        if (extraDataTitleId != null) {
            extraData = extraDataMapper.selectByTempDataId(tempData.getId(), extraDataTitleId);
        }

        // 解析全描述
        String fullDescription = "";
        if (titleEntity != null && StrUtil.isNotBlank(titleEntity.getFullDescCol())) {
            int idx = findFullDescColIndex(titleEntity, titleEntity.getFullDescCol());
            if (idx > 0) {
                fullDescription = tempData.getColData(idx);
            }
        }
        Map<String, String> extraAttrs = parseRule.parse(fullDescription);

        // 分类匹配（独立匹配模块，使用预加载缓存，未命中三级则不赋值）
        CategoryMatchOutcome matchResult = categoryMatcher.match(
                buildMatchContext(tempData, extraAttrs, titleEntity, allCategories, synonyms));
        CategoryEntity matchedCategory = matchResult.getCategory();

        // 创建清洗数据
        CleanedDataEntity cleanedData = new CleanedDataEntity();
        cleanedData.setTempDataId(tempData.getId());
        cleanedData.setMatchSource(matchResult.getSource());
        cleanedData.setMatchConfidence(matchResult.getConfidence());
        if (matchedCategory != null) {
            cleanedData.setCategoryId(matchedCategory.getId());
            cleanedData.setCategoryCode(matchedCategory.getCategoryCode());
            cleanedData.setCategoryLevel(matchedCategory.getLevel());
            cleanedData.setCategoryFullPath(matchedCategory.getFullPath());
        }

        // 提取核心字段
        cleanedData.setMaterialCode(getColByTitle(titleEntity, "物料代码", tempData, 3));
        cleanedData.setMaterialName(extraAttrs.getOrDefault("物资名称", extraAttrs.getOrDefault("物资简称", "")));
        cleanedData.setSpecification(extraAttrs.getOrDefault("规格", ""));
        cleanedData.setTechnicalStandard(extraAttrs.getOrDefault("技术标准号", ""));
        cleanedData.setGrade(extraAttrs.getOrDefault("牌号", ""));
        cleanedData.setUnit(extraAttrs.getOrDefault("计量单位", getColByTitle(titleEntity, "计量单位", tempData, 6)));

        // 质量评分
        cleanedData.setCompletenessScore(cleanedData.calculateCompleteness());
        double qualityScore = calculateQuality(cleanedData);
        cleanedData.setQualityScore(qualityScore);
        cleanedData.setAccuracyScore(qualityScore * 0.8);

        if (qualityScore < thresholdReview) {
            cleanedData.setStatus(DataStatus.NEEDS_REVIEW);
        } else if (qualityScore >= thresholdExport) {
            cleanedData.setStatus(DataStatus.EXPORT_READY);
        } else {
            cleanedData.setStatus(DataStatus.APPROVED);
        }

        // 分类未命中三级：不赋一/二级编码，标记为待审核（无效数据页统计）
        if (matchedCategory == null
                || "UNMATCHED".equals(matchResult.getSource())) {
            cleanedData.setStatus(DataStatus.NEEDS_REVIEW);
        }

        cleanedDataMapper.insert(cleanedData);

        // 入库后再创建审核任务，确保能拿到自增主键 entity_id
        if (qualityScore < thresholdReview) {
            createReviewTask(cleanedData, "质量评分过低: " + qualityScore);
        }

        // 更新原始数据状态
        tempData.setStatus(DataStatus.PROCESSED);
        tempDataMapper.updateById(tempData);

        return cleanedData;
    }

    @Override
    public Map<String, Object> getCleaningProgress(Long titleId) {
        Map<String, Object> result = new HashMap<>();
        TempDataTitleEntity title = tempDataTitleMapper.selectById(titleId);
        if (title == null) {
            result.put("error", "表头不存在");
            return result;
        }
        result.put("titleId", titleId);
        result.put("fileName", title.getFileName());
        result.put("status", title.getStatus());
        result.put("totalRows", title.getTotalRows());
        return result;
    }

    @Override
    public void stopCleaning(Long titleId) {
        log.info("停止清洗任务，表头ID: {}", titleId);
        TempDataTitleEntity title = tempDataTitleMapper.selectById(titleId);
        if (title != null && DataStatus.needsReview(title.getStatus())) {
            title.setStatus(DataStatus.REJECTED);
            tempDataTitleMapper.updateById(title);
        }
    }

    @Override
    @Transactional
    public CleanedDataEntity recleanData(Long cleanedDataId) {
        CleanedDataEntity oldData = cleanedDataMapper.selectById(cleanedDataId);
        if (oldData == null) throw new RuntimeException("清洗数据不存在: " + cleanedDataId);

        cleanedDataMapper.deleteById(cleanedDataId);

        ExtraDataTitleEntity extraTitle = extraDataTitleMapper.selectByTempDataTitleId(
                tempDataMapper.selectById(oldData.getTempDataId()).getTempDataTitleId());
        return matchAndClean(oldData.getTempDataId(),
                extraTitle != null ? extraTitle.getId() : null, null);
    }

    // ==================== 字段映射 ====================

    @Override
    @Transactional
    public List<FieldMappingAuditEntity> autoMapFields(Long tempDataTitleId, Long extraDataTitleId, Long standardTitleId) {
        log.info("自动映射字段，表头ID: {}, 补充表头ID: {}, 标准表头ID: {}", tempDataTitleId, extraDataTitleId, standardTitleId);

        List<FieldMappingAuditEntity> allMappings = new ArrayList<>();

        // 如果未指定标准表头，从清洗数据的所有分类编码自动确定
        if (standardTitleId == null) {
            List<String> categoryCodes = cleanedDataMapper.selectDistinctCategoryCodesByTitleId(tempDataTitleId);
            if (categoryCodes == null || categoryCodes.isEmpty()) {
                throw new RuntimeException("未找到对应的清洗数据或清洗数据中未包含分类编码信息，请先执行数据清洗");
            }

            // 为每个出现的分类编码都构建一套字段映射：每条原始数据只归属一个分类，
            // 各分类的标准表头互不相同，因此分别建映射不会重复。
            // 只有这样结果数据才能覆盖所有匹配到的分类——否则未被建映射的分类会既不在结果数据、
            // 也不在无效数据（它们已匹配到三级，不是 UNMATCHED）中，导致“结果 + 无效”对不上。
            List<StandardTitleEntity> matchedTitles = new ArrayList<>();
            Set<Long> addedTitleIds = new HashSet<>();
            for (String categoryCode : categoryCodes) {
                StandardTitleEntity autoTitle = standardTitleMapper.selectByCategoryCode(categoryCode);
                if (autoTitle == null) {
                    log.warn("未找到分类编码 [{}] 对应的标准字段表头，跳过该分类", categoryCode);
                    continue;
                }
                if (!addedTitleIds.add(autoTitle.getId())) {
                    continue; // 同一标准表头只建一次
                }
                matchedTitles.add(autoTitle);
                log.info("根据分类编码 [{}] 自动确定标准字段表头ID: {}", categoryCode, autoTitle.getId());
            }

            if (matchedTitles.isEmpty()) {
                throw new RuntimeException("清洗数据中的所有分类编码均未找到对应的标准字段表头，请先在标准字段管理中创建");
            }
            for (StandardTitleEntity title : matchedTitles) {
                allMappings.addAll(createFieldMappingsForStandardTitle(title.getId(), tempDataTitleId, extraDataTitleId));
            }
        } else {
            // 指定了标准表头，仅为该表头创建映射
            StandardTitleEntity standardTitle = standardTitleMapper.selectById(standardTitleId);
            if (standardTitle == null) {
                throw new RuntimeException("标准字段表头不存在: " + standardTitleId);
            }
            allMappings.addAll(createFieldMappingsForStandardTitle(standardTitleId, tempDataTitleId, extraDataTitleId));
        }

        log.info("字段映射完成，总映射数: {}", allMappings.size());
        return allMappings;
    }

    /**
     * 为指定的标准表头创建字段映射
     */
    private List<FieldMappingAuditEntity> createFieldMappingsForStandardTitle(Long standardTitleId,
                                                                               Long tempDataTitleId,
                                                                               Long extraDataTitleId) {
        StandardTitleEntity standardTitle = standardTitleMapper.selectById(standardTitleId);
        if (standardTitle == null) return new ArrayList<>();

        // 清除该组合下的旧映射数据
        fieldMappingAuditMapper.deleteByStandardAndTitle(standardTitleId, tempDataTitleId);

        List<FieldMappingAuditEntity> mappings = new ArrayList<>();

        // 从原始表头映射
        TempDataTitleEntity titleEntity = tempDataTitleMapper.selectById(tempDataTitleId);
        if (titleEntity != null) {
            for (int i = 1; i <= 10; i++) {
                String colTitle = titleEntity.getColTitle(i);
                if (StrUtil.isNotBlank(colTitle)) {
                    String bestMatch = findBestFieldMatch(colTitle, standardTitle);
                    FieldMappingAuditEntity mapping = new FieldMappingAuditEntity();
                    mapping.setStandardTitleId(standardTitleId);
                    mapping.setTempDataTitleId(tempDataTitleId);
                    mapping.setSourceType("temp_data");
                    mapping.setSourceField(colTitle);
                    mapping.setTargetField(bestMatch);
                    mapping.setMappingType("auto");
                    mapping.setConfidence(calculateConfidence(colTitle, bestMatch));
                    mapping.setStatus("pending");
                    fieldMappingAuditMapper.insert(mapping);
                    mappings.add(mapping);
                }
            }
        }

        // 从补充表头映射
        if (extraDataTitleId != null) {
            ExtraDataTitleEntity extraTitle = extraDataTitleMapper.selectById(extraDataTitleId);
            if (extraTitle != null) {
                for (int i = 1; i <= 20; i++) {
                    String colTitle = extraTitle.getColTitle(i);
                    if (StrUtil.isNotBlank(colTitle)) {
                        String bestMatch = findBestFieldMatch(colTitle, standardTitle);
                        FieldMappingAuditEntity mapping = new FieldMappingAuditEntity();
                        mapping.setStandardTitleId(standardTitleId);
                        mapping.setTempDataTitleId(tempDataTitleId);
                        mapping.setSourceType("extra_data");
                        mapping.setSourceField(colTitle);
                        mapping.setTargetField(bestMatch);
                        mapping.setMappingType("auto");
                        mapping.setConfidence(calculateConfidence(colTitle, bestMatch));
                        mapping.setStatus("pending");
                        fieldMappingAuditMapper.insert(mapping);
                        mappings.add(mapping);
                    }
                }
            }
        }

        log.info("标准表头ID {} 字段映射完成，映射数: {}", standardTitleId, mappings.size());
        return mappings;
    }

    @Override
    @Transactional
    public FieldMappingAuditEntity updateFieldMapping(Long mappingId, String targetField) {
        FieldMappingAuditEntity mapping = fieldMappingAuditMapper.selectById(mappingId);
        if (mapping == null) throw new RuntimeException("字段映射不存在: " + mappingId);
        mapping.setTargetField(targetField);
        mapping.setMappingType("manual");
        mapping.setStatus("approved");
        mapping.setReviewedAt(LocalDateTime.now());
        fieldMappingAuditMapper.updateById(mapping);
        return mapping;
    }

    @Override
    public List<FieldMappingAuditEntity> getFieldMappings(Long standardTitleId, Long tempDataTitleId, Long extraDataTitleId) {
        if (standardTitleId != null) {
            return fieldMappingAuditMapper.selectByStandardAndTitle(standardTitleId, tempDataTitleId);
        }
        return fieldMappingAuditMapper.selectByTitleId(tempDataTitleId);
    }

    @Override
    @Transactional
    public List<FieldMappingAuditEntity> saveManualMappings(Long standardTitleId, Long tempDataTitleId, Long extraDataTitleId,
                                                             List<Map<String, Object>> mappings) {
        log.info("手动保存字段映射（合并模式），standardTitleId: {}, tempDataTitleId: {}, extraDataTitleId: {}, 新映射数: {}",
                standardTitleId, tempDataTitleId, extraDataTitleId, mappings != null ? mappings.size() : 0);

        List<FieldMappingAuditEntity> result = new ArrayList<>();
        if (mappings == null || mappings.isEmpty()) {
            // 新映射为空时不做任何清理，保留已有映射
            return result;
        }

        // 收集本次映射的 (targetField, sourceType) 组合，精确匹配删除旧记录
        // 关键：弹窗中每个标准字段只有一行下拉，无法表示"多个 sourceType 同映射到一个 target"
        // 所以只删除有相同 (targetField + sourceType) 的旧记录，不同 sourceType 的旧映射保留
        Set<String> targetSourcePairsInNew = mappings.stream()
                .filter(m -> StrUtil.isNotBlank((String) m.get("targetField")))
                .map(m -> {
                    String tf = (String) m.get("targetField");
                    String st = (String) m.get("sourceType");
                    return tf + "||" + (StrUtil.isNotBlank(st) ? st : "temp_data");
                })
                .collect(Collectors.toSet());

        int deletedCount = 0;
        if (!targetSourcePairsInNew.isEmpty()) {
            // 先查询已有的映射关系
            List<FieldMappingAuditEntity> existingMappings =
                    fieldMappingAuditMapper.selectByStandardAndTitle(standardTitleId, tempDataTitleId);
            for (FieldMappingAuditEntity old : existingMappings) {
                if (old.getTargetField() == null) continue;
                String pair = old.getTargetField() + "||" + (old.getSourceType() != null ? old.getSourceType() : "temp_data");
                if (targetSourcePairsInNew.contains(pair)) {
                    // 仅删除 (targetField + sourceType) 精确匹配的旧记录
                    fieldMappingAuditMapper.deleteById(old.getId());
                    deletedCount++;
                }
            }
        }

        for (Map<String, Object> m : mappings) {
            String sourceField = (String) m.get("sourceField");
            String sourceType = (String) m.get("sourceType");
            String targetField = (String) m.get("targetField");

            if (StrUtil.isBlank(sourceField) || StrUtil.isBlank(targetField)) {
                continue;
            }

            FieldMappingAuditEntity entity = new FieldMappingAuditEntity();
            entity.setStandardTitleId(standardTitleId);
            entity.setTempDataTitleId(tempDataTitleId);
            entity.setSourceField(sourceField);
            entity.setSourceType(StrUtil.isNotBlank(sourceType) ? sourceType : "temp_data");
            entity.setTargetField(targetField);
            entity.setMappingType("manual");
            entity.setConfidence(1.0);
            entity.setStatus("approved");
            fieldMappingAuditMapper.insert(entity);
            result.add(entity);
        }

        log.info("手动字段映射保存完成（合并），新增/更新 {} 条，移除被覆盖的旧记录 {} 条",
                result.size(), deletedCount);
        return result;
    }

    // ==================== 结果数据填充 ====================

    @Override
    @Transactional
    public List<ResultDataEntity> fillResultData(Long standardTitleId, Long tempDataTitleId, Long extraDataTitleId) {
        log.info("填充结果数据，默认标准表头ID: {}", standardTitleId);

        // 未显式传入补充表头时，尝试按数据文件反查，确保补充数据字段也能被填充
        extraDataTitleId = resolveExtraDataTitleId(tempDataTitleId, extraDataTitleId);

        // 默认标准表头（作为兜底）
        StandardTitleEntity defaultStandardTitle = standardTitleMapper.selectById(standardTitleId);
        if (defaultStandardTitle == null) throw new RuntimeException("标准表头不存在: " + standardTitleId);

        // 记录该数据文件关联的标准字段表头（供结果数据下拉框快速查询）
        recordTitleStandardTitle(tempDataTitleId, standardTitleId);

        // 获取该标准表头下的字段映射关系
        List<FieldMappingAuditEntity> mappings = fieldMappingAuditMapper.selectByStandardAndTitle(standardTitleId, tempDataTitleId);

        // 获取原始数据和补充数据
        List<TempDataEntity> tempDataList = tempDataMapper.selectByTitleId(tempDataTitleId);
        TempDataTitleEntity titleEntity = tempDataTitleMapper.selectById(tempDataTitleId);

        // 预加载清洗数据与补充数据，避免逐行 N+1 查询
        Map<Long, CleanedDataEntity> cleanedByTempId = new HashMap<>();
        for (CleanedDataEntity cd : cleanedDataMapper.selectAllByTempDataTitleId(tempDataTitleId)) {
            cleanedByTempId.put(cd.getTempDataId(), cd);
        }
        Map<Long, ExtraDataEntity> extraByTempId = extraDataTitleId != null ? new HashMap<>() : null;
        if (extraByTempId != null) {
            for (ExtraDataEntity ed : extraDataMapper.selectByExtraDataTitleId(extraDataTitleId)) {
                extraByTempId.put(ed.getTempDataId(), ed);
            }
        }

        // 缓存：categoryCode -> StandardTitleEntity，避免重复DB查询
        Map<String, StandardTitleEntity> standardTitleCache = new HashMap<>();

        List<ResultDataEntity> resultList = new ArrayList<>();
        for (TempDataEntity tempData : tempDataList) {
            CleanedDataEntity cleanedData = cleanedByTempId.get(tempData.getId());
            // 跳过没有匹配到分类编码的数据
            if (cleanedData == null || StrUtil.isBlank(cleanedData.getCategoryCode())) {
                log.debug("tempDataId: {} 没有分类编码，跳过填充", tempData.getId());
                continue;
            }
            ExtraDataEntity extraData = extraByTempId != null ? extraByTempId.get(tempData.getId()) : null;

            // 根据清洗数据的分类编码，确定该条数据对应的标准字段表头
            StandardTitleEntity recordStandardTitle = resolveStandardTitle(
                    cleanedData, standardTitleCache, defaultStandardTitle);
            // 单表头填充模式：找不到对应标准表头时兜底到用户指定的默认表头（空值填充）
            if (recordStandardTitle == null) {
                recordStandardTitle = defaultStandardTitle;
            }

            ResultDataEntity result = new ResultDataEntity();
            result.setStandardTitleId(recordStandardTitle.getId());
            result.setTempDataId(tempData.getId());
            result.setCleanedDataId(cleanedData != null ? cleanedData.getId() : null);
            result.setStatus("draft");

            // 根据映射填充数据
            for (int colIdx = 1; colIdx <= 20; colIdx++) {
                String standardColTitle = recordStandardTitle.getColTitle(colIdx);
                if (StrUtil.isBlank(standardColTitle)) continue;

                String value = findMappedValue(mappings, standardColTitle,
                        titleEntity, tempData, extraData);
                if (StrUtil.isNotBlank(value)) {
                    result.setColData(colIdx, value);
                }
            }

            resultDataMapper.insert(result);
            resultList.add(result);

            if (resultList.size() >= batchSize) {
                log.info("已填充 {} 条结果数据", resultList.size());
            }
        }

        log.info("结果数据填充完成，共 {} 条", resultList.size());
        return resultList;
    }

    /**
     * 根据清洗数据的分类型编码，解析对应的标准字段表头。
     * 优先从缓存获取，缓存未命中时查DB并放入缓存。
     * 如果无法确定，回退到默认标准表头。
     */
    /**
     * 根据清洗数据的分类编码，解析对应的标准字段表头。
     * 优先从缓存获取，缓存未命中时查DB并放入缓存。
     *
     * 注意：本方法【不兜底】。找不到对应标准表头时返回 null。
     * 兜底逻辑交由调用方按场景决定（单表头填充可兜底到用户指定表头；
     * 批量填充不可兜底，否则“找不到标准表头”的数据会被复制进每个表头，导致结果条数膨胀）。
     */
    private StandardTitleEntity resolveStandardTitle(CleanedDataEntity cleanedData,
                                                      Map<String, StandardTitleEntity> cache,
                                                      StandardTitleEntity defaultTitle) {
        if (cleanedData != null && StrUtil.isNotBlank(cleanedData.getCategoryCode())) {
            StandardTitleEntity st = cache.computeIfAbsent(
                    cleanedData.getCategoryCode(),
                    code -> standardTitleMapper.selectByCategoryCode(code)
            );
            if (st != null) {
                return st;
            }
            log.warn("未找到分类编码 [{}] 对应的标准字段表头，该数据将不归属任何标准表头", cleanedData.getCategoryCode());
        }
        return null;
    }

    /**
     * 解析补充数据表头ID：若调用方未显式传入（为 null），则尝试根据数据文件ID反查其关联的补充表头。
     * 这样即使前端未选择补充表头，补充数据字段也能被正确填充。
     */
    private Long resolveExtraDataTitleId(Long tempDataTitleId, Long extraDataTitleId) {
        // 0 或 null 均视为未提供，尝试反查
        if (extraDataTitleId != null && extraDataTitleId != 0) return extraDataTitleId;
        if (tempDataTitleId != null) {
            ExtraDataTitleEntity et = extraDataTitleMapper.selectByTempDataTitleId(tempDataTitleId);
            if (et != null) {
                log.info("未传入补充表头ID，按数据文件 {} 反查得到补充表头ID: {}", tempDataTitleId, et.getId());
                return et.getId();
            }
        }
        return null;
    }

    @Override
    @Async
    public String startFill(Long standardTitleId, Long tempDataTitleId, Long extraDataTitleId) {
        log.info("开始异步填充结果数据，标准表头ID: {}, 数据表头ID: {}", standardTitleId, tempDataTitleId);
        // 未显式传入补充表头时，尝试按数据文件反查
        extraDataTitleId = resolveExtraDataTitleId(tempDataTitleId, extraDataTitleId);
        // 单表头填充模式：允许兜底（找不到标准表头时填充到用户指定的默认表头，值为空）
        doStartFill(standardTitleId, tempDataTitleId, extraDataTitleId, true);
        return "fill_task_" + standardTitleId;
    }

    @Override
    @Transactional
    public String fillAllStandardTitles(Long tempDataTitleId, Long extraDataTitleId) {
        log.info("批量填充所有标准字段表头，数据表头ID: {}, 补充表头ID: {}", tempDataTitleId, extraDataTitleId);
        // 未显式传入补充表头时，尝试按数据文件反查
        extraDataTitleId = resolveExtraDataTitleId(tempDataTitleId, extraDataTitleId);

        List<StandardTitleEntity> allStandards = standardTitleMapper.selectList(null);
        if (allStandards == null || allStandards.isEmpty()) {
            return "没有可用的标准字段表头";
        }

        for (StandardTitleEntity st : allStandards) {
            // 检查该标准表头是否有映射数据
            List<FieldMappingAuditEntity> mappings = fieldMappingAuditMapper.selectByStandardAndTitle(st.getId(), tempDataTitleId);
            if (mappings != null && !mappings.isEmpty()) {
                log.info("触发填充标准表头: {}", st.getId());
                // 批量填充模式：不允许兜底，否则“找不到标准表头”的数据会被复制进每个表头，造成结果条数膨胀
                doStartFill(st.getId(), tempDataTitleId, extraDataTitleId, false);
            } else {
                log.info("标准表头 {} 无映射数据，跳过填充", st.getId());
            }
        }

        // 批量填充结束后，将“分类编码找不到对应标准表头”的数据统一填充为一条空值结果记录
        // （standardTitleId=null），只填充一次，避免被复制进各标准表头导致膨胀，同时满足“可填充空值”。
        fillNoTitleResults(tempDataTitleId, extraDataTitleId);

        return "fill_all_completed";
    }

    /**
     * 实际的填充执行逻辑，由 startFill 异步调用
     */
    public void doStartFill(Long standardTitleId, Long tempDataTitleId, Long extraDataTitleId, boolean allowFallback) {
        transactionTemplate.executeWithoutResult(status -> {
            try {
                log.info("异步填充任务开始执行，默认标准表头ID: {}", standardTitleId);

                // 默认标准表头（作为兜底）
                StandardTitleEntity defaultStandardTitle = standardTitleMapper.selectById(standardTitleId);
                if (defaultStandardTitle == null) throw new RuntimeException("标准表头不存在: " + standardTitleId);

                // 记录该数据文件关联的标准字段表头（供结果数据下拉框快速查询）
                recordTitleStandardTitle(tempDataTitleId, standardTitleId);

                List<FieldMappingAuditEntity> mappings = fieldMappingAuditMapper.selectByStandardAndTitle(standardTitleId, tempDataTitleId);
                List<TempDataEntity> tempDataList = tempDataMapper.selectByTitleId(tempDataTitleId);
                TempDataTitleEntity titleEntity = tempDataTitleMapper.selectById(tempDataTitleId);

                // 预加载清洗数据与补充数据，避免主循环内逐行 N+1 查询
                // （大数据量下 N+1 会让请求远超连接超时，导致响应被截断、前端解析到空 body）
                Map<Long, CleanedDataEntity> cleanedByTempId = new HashMap<>();
                for (CleanedDataEntity cd : cleanedDataMapper.selectAllByTempDataTitleId(tempDataTitleId)) {
                    cleanedByTempId.put(cd.getTempDataId(), cd);
                }
                Map<Long, ExtraDataEntity> extraByTempId = extraDataTitleId != null ? new HashMap<>() : null;
                if (extraByTempId != null) {
                    for (ExtraDataEntity ed : extraDataMapper.selectByExtraDataTitleId(extraDataTitleId)) {
                        extraByTempId.put(ed.getTempDataId(), ed);
                    }
                }

                // 保存并填充：先清除该标准表头+数据文件下已有的填充结果，实现覆盖而非跳过
                int deletedCount = resultDataMapper.deleteByStandardAndTitle(standardTitleId, tempDataTitleId);
                log.info("清除已有填充结果 {} 条，准备重新填充", deletedCount);

                // 缓存：categoryCode -> StandardTitleEntity
                Map<String, StandardTitleEntity> standardTitleCache = new HashMap<>();

                // 进度总数 = 本标准表头实际可填充的行数（有清洗数据、含分类编码、且归属本标准表头），
                // 避免把未清洗/其它分类的 temp_data 行计入总数，导致“进度总数”虚高、与“数据总数”对不上
                int fillableTotal = 0;
                for (TempDataEntity td : tempDataList) {
                    CleanedDataEntity cd = cleanedByTempId.get(td.getId());
                    if (cd == null || !StrUtil.isNotBlank(cd.getCategoryCode())) continue;
                    StandardTitleEntity st = resolveStandardTitle(cd, standardTitleCache, defaultStandardTitle);
                    if (st != null && st.getId().equals(standardTitleId)) fillableTotal++;
                }
                final int totalCount = fillableTotal;
                int successCount = 0;
                int errorCount = 0;
                int skippedCount = 0;
                int skippedNoCleaned = 0;   // 没有清洗数据
                int skippedNotMatch = 0;    // 标准表头不匹配
                int skippedExisting = 0;    // 已有填充结果

                // 发送开始消息（WebSocket topic 仍然使用参数 standardTitleId 作为通道标识）
                Map<String, Object> startMsg = new LinkedHashMap<>();
                startMsg.put("type", "start");
                startMsg.put("current", 0);
                startMsg.put("total", totalCount);
                startMsg.put("successCount", 0);
                startMsg.put("errorCount", 0);
                startMsg.put("progressPercent", 0);
                startMsg.put("timestamp", System.currentTimeMillis());
                messagingTemplate.convertAndSend("/topic/fill/" + standardTitleId, JSON.toJSONString(startMsg));

                for (int i = 0; i < tempDataList.size(); i++) {
                    TempDataEntity tempData = tempDataList.get(i);
                    try {
                        CleanedDataEntity cleanedData = cleanedByTempId.get(tempData.getId());
                        // 跳过没有匹配到分类编码的数据
                        if (cleanedData == null || StrUtil.isBlank(cleanedData.getCategoryCode())) {
                            skippedCount++;
                            skippedNoCleaned++;
                            log.debug("tempDataId: {} 没有分类编码，跳过填充", tempData.getId());
                            continue;
                        }
                        ExtraDataEntity extraData = extraByTempId != null ? extraByTempId.get(tempData.getId()) : null;

                        // 根据清洗数据的分类编码，确定该条数据对应的标准字段表头
                        StandardTitleEntity recordStandardTitle = resolveStandardTitle(
                                cleanedData, standardTitleCache, defaultStandardTitle);

                        // 找不到对应标准表头时：
                        // - 单表头填充（allowFallback=true）：兜底到用户指定的默认表头，空值填充
                        // - 批量填充（allowFallback=false）：不兜底，跳过，避免被复制进每个表头造成膨胀
                        //   （这类数据由 fillAllStandardTitles 统一填充为一条空值记录）
                        if (recordStandardTitle == null) {
                            if (allowFallback) {
                                recordStandardTitle = defaultStandardTitle;
                            } else {
                                skippedCount++;
                                skippedNotMatch++;
                                continue;
                            }
                        }

                        // 只填充属于当前指定标准表头的数据
                        if (!recordStandardTitle.getId().equals(standardTitleId)) {
                            skippedCount++;
                            skippedNotMatch++;
                            continue;
                        }

                        ResultDataEntity result = new ResultDataEntity();
                        result.setStandardTitleId(recordStandardTitle.getId());
                        result.setTempDataId(tempData.getId());
                        result.setCleanedDataId(cleanedData != null ? cleanedData.getId() : null);
                        result.setStatus("draft");

                        int filledCount = 0;
                        for (int colIdx = 1; colIdx <= 20; colIdx++) {
                            String standardColTitle = recordStandardTitle.getColTitle(colIdx);
                            if (StrUtil.isBlank(standardColTitle)) continue;

                            String value = findMappedValue(mappings, standardColTitle,
                                    titleEntity, tempData, extraData);
                            if (StrUtil.isNotBlank(value)) {
                                result.setColData(colIdx, value);
                                filledCount++;
                            }
                        }

                        resultDataMapper.insert(result);
                        successCount++;

                        // 每填充一条数据，通过 WebSocket 推送进度
                        Map<String, Object> msg = new LinkedHashMap<>();
                        msg.put("type", "progress");
                        msg.put("current", i + 1);
                        msg.put("total", totalCount);
                        msg.put("successCount", successCount);
                        msg.put("errorCount", errorCount);
                        msg.put("progressPercent", (int) ((double) (i + 1) / totalCount * 100));
                        msg.put("timestamp", System.currentTimeMillis());

                        Map<String, Object> dataMap = new LinkedHashMap<>();
                        dataMap.put("resultId", result.getId());
                        dataMap.put("tempDataId", tempData.getId());
                        dataMap.put("cleanedDataId", cleanedData != null ? cleanedData.getId() : null);
                        dataMap.put("status", result.getStatus());
                        dataMap.put("filledCount", filledCount);
                        msg.put("data", dataMap);

                        messagingTemplate.convertAndSend("/topic/fill/" + standardTitleId, JSON.toJSONString(msg));
                    } catch (Exception e) {
                        errorCount++;
                        log.error("填充结果数据失败，tempDataId: {}", tempData.getId(), e);
                        // 推送错误进度
                        Map<String, Object> errMsg = new LinkedHashMap<>();
                        errMsg.put("type", "progress");
                        errMsg.put("current", i + 1);
                        errMsg.put("total", totalCount);
                        errMsg.put("successCount", successCount);
                        errMsg.put("errorCount", errorCount);
                        errMsg.put("progressPercent", (int) ((double) (i + 1) / totalCount * 100));
                        errMsg.put("timestamp", System.currentTimeMillis());
                        messagingTemplate.convertAndSend("/topic/fill/" + standardTitleId, JSON.toJSONString(errMsg));
                    }
                }

                // 发送完成消息
                int processedCount = successCount + errorCount + skippedCount;
                Map<String, Object> completeMsg = new LinkedHashMap<>();
                completeMsg.put("type", "complete");
                completeMsg.put("current", processedCount);
                completeMsg.put("total", totalCount);
                completeMsg.put("successCount", successCount);
                completeMsg.put("errorCount", errorCount);
                completeMsg.put("skippedCount", skippedCount);
                completeMsg.put("skippedNoCleaned", skippedNoCleaned);
                completeMsg.put("skippedNotMatch", skippedNotMatch);
                completeMsg.put("skippedExisting", skippedExisting);
                completeMsg.put("progressPercent", 100);
                completeMsg.put("timestamp", System.currentTimeMillis());
                messagingTemplate.convertAndSend("/topic/fill/" + standardTitleId, JSON.toJSONString(completeMsg));

                log.info("结果数据填充完成，成功: {}, 失败: {}, 跳过: {} (无清洗数据: {}, 标准表头不匹配: {}, 已有结果: {})",
                        successCount, errorCount, skippedCount, skippedNoCleaned, skippedNotMatch, skippedExisting);
            } catch (Exception e) {
                log.error("填充任务执行失败，标准表头ID: {}", standardTitleId, e);
                Map<String, Object> errMsg = new LinkedHashMap<>();
                errMsg.put("type", "error");
                errMsg.put("successCount", 0);
                errMsg.put("errorCount", 1);
                errMsg.put("progressPercent", 0);
                errMsg.put("timestamp", System.currentTimeMillis());
                messagingTemplate.convertAndSend("/topic/fill/" + standardTitleId, JSON.toJSONString(errMsg));
                status.setRollbackOnly();
            }
        });
    }

    /**
     * 将“分类编码找不到对应标准表头”的数据，统一填充为一条空值结果记录（standardTitleId=null）。
     * 仅在批量填充（fillAllStandardTitles）结束后调用一次，避免这类数据被复制进每个标准表头造成膨胀，
     * 同时满足“找不到对应分类代码时可填充空值”的需求。
     */
    private void fillNoTitleResults(Long tempDataTitleId, Long extraDataTitleId) {
        // 清除该文件下旧的填充失败记录，实现覆盖而非累加
        failedResultDataMapper.deleteByTitleId(tempDataTitleId);

        List<TempDataEntity> tempDataList = tempDataMapper.selectByTitleId(tempDataTitleId);
        if (tempDataList == null || tempDataList.isEmpty()) return;

        Map<String, StandardTitleEntity> cache = new HashMap<>();
        List<FailedResultDataEntity> failedList = new ArrayList<>();
        for (TempDataEntity td : tempDataList) {
            CleanedDataEntity cd = cleanedDataMapper.selectByTempDataId(td.getId());
            if (cd == null || StrUtil.isBlank(cd.getCategoryCode())) continue;
            // 能找到标准表头的数据已在各 doStartFill 中填充，这里只处理找不到的
            StandardTitleEntity st = resolveStandardTitle(cd, cache, null);
            if (st != null) continue;

            // 找不到对应标准字段表头：result_data.standard_title_id 为 NOT NULL，无法直接写入，
            // 因此跳过写入并记为“填充失败”，便于在页面展示失败列表，而不影响其余数据的正常填充。
            FailedResultDataEntity failed = new FailedResultDataEntity();
            failed.setTempDataId(td.getId());
            failed.setCleanedDataId(cd.getId());
            failed.setCategoryCode(cd.getCategoryCode());
            failed.setReason("未找到匹配的标准字段表头（分类编码: " + cd.getCategoryCode() + "）");
            failed.setStatus("FAILED");
            failed.setRawData(buildRawData(td));
            failedList.add(failed);
        }

        if (!failedList.isEmpty()) {
            failedResultDataMapper.insertBatch(failedList);
            log.warn("填充结果中存在 {} 条因未匹配标准表头而跳过的数据，已记录为填充失败", failedList.size());
        } else {
            log.info("无“未匹配标准表头”的数据，无需记录填充失败");
        }
    }

    /**
     * 将原始数据的 col1..col10 拼接为可读快照，用于填充失败记录的回看展示。
     */
    private String buildRawData(TempDataEntity td) {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= 10; i++) {
            if (i > 1) sb.append(" | ");
            sb.append(td.getColData(i));
        }
        return sb.toString();
    }

    @Override
    @Transactional
    public ResultDataEntity updateResultData(Long resultDataId, int colIndex, String value) {
        ResultDataEntity result = resultDataMapper.selectById(resultDataId);
        if (result == null) throw new RuntimeException("结果数据不存在: " + resultDataId);
        result.setColData(colIndex, value);
        result.setStatus("modified");
        resultDataMapper.updateById(result);
        return result;
    }

    @Override
    @Transactional
    public void updateResultDataStatus(Long resultDataId, String status, String comment) {
        ResultDataEntity result = resultDataMapper.selectById(resultDataId);
        if (result == null) throw new RuntimeException("结果数据不存在: " + resultDataId);
        result.setStatus(status);
        result.setReviewComment(comment);
        result.setReviewedBy("system");
        result.setReviewedAt(LocalDateTime.now());
        resultDataMapper.updateById(result);
    }

    // ==================== 查询 ====================

    @Override
    public List<CleanedDataEntity> searchCleanedData(SearchCondition condition) {
        return cleanedDataMapper.searchByConditions(condition);
    }

    @Override
    public long countCleanedData(SearchCondition condition) {
        Long count = cleanedDataMapper.countByConditions(condition);
        return count != null ? count : 0;
    }

    @Override
    public List<TempDataEntity> getTempDataList(Long titleId) {
        return tempDataMapper.selectByTitleId(titleId);
    }

    @Override
    public Map<String, Object> getTempDataPage(Long titleId, int page, int pageSize) {
        TempDataTitleEntity title = tempDataTitleMapper.selectById(titleId);
        int offset = (page - 1) * pageSize;
        List<TempDataEntity> list = tempDataMapper.selectByTitleIdPage(titleId, offset, pageSize);
        int total = tempDataMapper.countByTitleId(titleId);
        Map<String, Object> result = new HashMap<>();
        result.put("title", title);
        result.put("list", list);
        result.put("total", total);
        result.put("page", page);
        result.put("pageSize", pageSize);
        return result;
    }

    @Override
    public List<ExtraDataEntity> getExtraDataList(Long extraDataTitleId) {
        return extraDataMapper.selectByExtraDataTitleId(extraDataTitleId);
    }

    @Override
    public List<ExtraDataTitleEntity> getExtraDataTitles() {
        return extraDataTitleMapper.selectList(null);
    }

    @Override
    public CleanedDataEntity getCleanedDataByTempDataTitleId(Long tempDataTitleId) {
        return cleanedDataMapper.selectByTempDataTitleId(tempDataTitleId);
    }

    @Override
    public Long getStandardTitleIdByTempDataTitleId(Long tempDataTitleId) {
        CleanedDataEntity cleanedData = cleanedDataMapper.selectByTempDataTitleId(tempDataTitleId);
        if (cleanedData == null || StrUtil.isBlank(cleanedData.getCategoryCode())) {
            return null;
        }
        StandardTitleEntity standardTitle = standardTitleMapper.selectByCategoryCode(cleanedData.getCategoryCode());
        return standardTitle != null ? standardTitle.getId() : null;
    }

    // ==================== 标准字段表头管理 ====================

    @Override
    @Transactional
    public StandardTitleEntity createStandardTitle(StandardTitleEntity entity) {
        standardTitleMapper.insert(entity);
        return entity;
    }

    @Override
    @Transactional
    public StandardTitleEntity updateStandardTitle(StandardTitleEntity entity) {
        standardTitleMapper.updateById(entity);
        return entity;
    }

    @Override
    @Transactional
    public void deleteStandardTitle(Long id) {
        standardTitleMapper.deleteById(id);
        // 清理数据文件-标准表头关联，避免下拉框出现已删除的表头
        titleStandardTitleMapper.delete(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<TitleStandardTitleEntity>()
                        .eq(TitleStandardTitleEntity::getStandardTitleId, id));
    }

    @Override
    public StandardTitleEntity getStandardTitleById(Long id) {
        return standardTitleMapper.selectById(id);
    }

    @Override
    public List<StandardTitleEntity> getAllStandardTitles() {
        List<StandardTitleEntity> titles = standardTitleMapper.selectList(null);
        if (titles != null) {
            for (StandardTitleEntity title : titles) {
                if (StrUtil.isNotBlank(title.getCategoryCode())) {
                    CategoryEntity cat = categoryMapper.selectByCode(title.getCategoryCode());
                    if (cat != null) {
                        title.setCategoryName(cat.getCategoryName());
                    }
                }
            }
        }
        return titles;
    }

    @Override
    public IPage<StandardTitleEntity> pageStandardTitles(long page, long size, String keyword) {
        Page<StandardTitleEntity> pageReq = new Page<>(page, size);
        LambdaQueryWrapper<StandardTitleEntity> wrapper = new LambdaQueryWrapper<>();
        if (keyword != null && !keyword.trim().isEmpty()) {
            wrapper.like(StandardTitleEntity::getCategoryCode, keyword.trim());
        }
        wrapper.orderByDesc(StandardTitleEntity::getId);
        IPage<StandardTitleEntity> result = standardTitleMapper.selectPage(pageReq, wrapper);
        // 补全分类名称
        if (result.getRecords() != null) {
            for (StandardTitleEntity title : result.getRecords()) {
                if (StrUtil.isNotBlank(title.getCategoryCode())) {
                    CategoryEntity cat = categoryMapper.selectByCode(title.getCategoryCode());
                    if (cat != null) {
                        title.setCategoryName(cat.getCategoryName());
                    }
                }
            }
        }
        return result;
    }

    @Override
    public void recordTitleStandardTitle(Long tempDataTitleId, Long standardTitleId) {
        if (tempDataTitleId == null || standardTitleId == null) return;
        if (titleStandardTitleMapper.exists(tempDataTitleId, standardTitleId) != null) return;
        TitleStandardTitleEntity rel = new TitleStandardTitleEntity();
        rel.setTempDataTitleId(tempDataTitleId);
        rel.setStandardTitleId(standardTitleId);
        titleStandardTitleMapper.insert(rel);
        log.info("记录数据文件-标准表头关联: titleId={}, standardTitleId={}", tempDataTitleId, standardTitleId);
    }

    @Override
    public List<StandardTitleEntity> getStandardTitlesByTitleId(Long tempDataTitleId) {
        if (tempDataTitleId == null) return new ArrayList<>();
        List<StandardTitleEntity> list = titleStandardTitleMapper.selectStandardTitlesByTitleId(tempDataTitleId);
        // 懒回填：关联表为空时，从已填充的 result_data 反推该文件关联的标准表头，避免历史数据需重新清洗
        if (list == null || list.isEmpty()) {
            List<Long> stdIds = resultDataMapper.selectStandardTitleIdsByTitle(tempDataTitleId);
            if (stdIds != null) {
                for (Long sid : stdIds) {
                    recordTitleStandardTitle(tempDataTitleId, sid);
                }
            }
            list = titleStandardTitleMapper.selectStandardTitlesByTitleId(tempDataTitleId);
        }
        // 补全分类名称，与 getAllStandardTitles 保持一致
        if (list != null) {
            for (StandardTitleEntity title : list) {
                if (StrUtil.isNotBlank(title.getCategoryCode())) {
                    CategoryEntity cat = categoryMapper.selectByCode(title.getCategoryCode());
                    if (cat != null) title.setCategoryName(cat.getCategoryName());
                }
            }
        }
        return list == null ? new ArrayList<>() : list;
    }

    @Override
    public List<ResultDataEntity> searchResultData(SearchCondition condition) {
        return resultDataMapper.searchByConditions(condition);
    }

    @Override
    public long countResultData(SearchCondition condition) {
        Long count = resultDataMapper.countByConditions(condition);
        return count != null ? count : 0;
    }

    @Override
    public List<FailedResultDataEntity> getFailedResults(Long titleId) {
        if (titleId == null) return new ArrayList<>();
        return failedResultDataMapper.selectByTitleId(titleId);
    }

    // ==================== 未映射结果 ====================

    @Override
    public List<CleanedDataEntity> getUnmappedResults(Long titleId) {
        return cleanedDataMapper.selectUnmappedByTitleId(titleId);
    }

    @Override
    public long countUnmappedResults(Long titleId) {
        Long count = cleanedDataMapper.countUnmappedByTitleId(titleId);
        return count != null ? count : 0;
    }

    // ==================== 统计 ====================

    @Override
    public Map<String, Object> getCleaningStatistics(Long titleId) {
        Map<String, Object> statistics = new HashMap<>();
        if (titleId != null) {
            TempDataTitleEntity title = tempDataTitleMapper.selectById(titleId);
            if (title != null) {
                statistics.put("fileName", title.getFileName());
                statistics.put("totalRows", title.getTotalRows());
                statistics.put("status", title.getStatus());
            }
        } else {
            statistics.put("totalFiles", tempDataTitleMapper.selectCount(null));
            statistics.put("totalCleaned", cleanedDataMapper.selectCount(null));
        }
        return statistics;
    }

    @Override
    public Map<String, Object> getQualityReport(Long titleId) {
        Map<String, Object> report = new HashMap<>();
        SearchCondition condition = new SearchCondition();
        condition.setPage(1);
        condition.setPageSize(1000);
        List<CleanedDataEntity> dataList = searchCleanedData(condition);

        double totalScore = 0;
        int count = 0;
        for (CleanedDataEntity data : dataList) {
            if (data.getQualityScore() != null) {
                totalScore += data.getQualityScore();
                count++;
            }
        }
        report.put("totalCount", dataList.size());
        report.put("averageScore", count > 0 ? totalScore / count : 0);
        return report;
    }

    // ==================== 私有辅助方法 ====================

    private String getCellValue(Cell cell) {
        if (cell == null) return "";
        switch (cell.getCellType()) {
            case STRING: return cell.getStringCellValue().trim();
            case NUMERIC:
                if (DateUtil.isCellDateFormatted(cell)) return cell.getDateCellValue().toString();
                String val = String.valueOf(cell.getNumericCellValue());
                return val.endsWith(".0") ? val.substring(0, val.length() - 2) : val;
            case BOOLEAN: return String.valueOf(cell.getBooleanCellValue());
            case FORMULA:
                try { return cell.getStringCellValue(); }
                catch (Exception e) { return String.valueOf(cell.getNumericCellValue()); }
            default: return "";
        }
    }

    private String saveUploadFile(MultipartFile file) throws IOException {
        Path uploadDir = Paths.get(uploadPath);
        if (!Files.exists(uploadDir)) Files.createDirectories(uploadDir);
        String ext = FileUtil.extName(file.getOriginalFilename());
        String newName = System.currentTimeMillis() + "_" + UUID.randomUUID().toString().substring(0, 8) + "." + ext;
        Path filePath = uploadDir.resolve(newName);
        Files.copy(file.getInputStream(), filePath);
        return filePath.toString();
    }

    private int findFullDescColIndex(TempDataTitleEntity titleEntity, String fullDescCol) {
        if (StrUtil.isBlank(fullDescCol)) return -1;
        for (int i = 1; i <= 10; i++) {
            if (fullDescCol.equals(titleEntity.getColTitle(i))) return i;
        }
        return 5; // 默认第5列
    }

    private String getColByTitle(TempDataTitleEntity title, String keyword, TempDataEntity tempData, int defaultIdx) {
        if (title == null) return tempData.getColData(defaultIdx);
        for (int i = 1; i <= 10; i++) {
            String colTitle = title.getColTitle(i);
            if (colTitle != null && colTitle.contains(keyword)) {
                return tempData.getColData(i);
            }
        }
        return tempData.getColData(defaultIdx);
    }

    /**
     * 分类匹配：先全词匹配，再旧名称匹配
     */
    // ==================== 分类匹配（委托给独立匹配模块 com.aiclean.match） ====================

    /**
     * 单条数据分类匹配：加载分类树与同义词后，委托给独立 CategoryMatcher。
     * 匹配逻辑（分层定位、语义/模糊/全词/编码匹配、必须落到三级）已抽离到匹配模块，便于独立演进。
     */
    private CategoryMatchOutcome matchCategory(TempDataEntity tempData, Map<String, String> extraAttrs) {
        TempDataTitleEntity title = tempDataTitleMapper.selectById(tempData.getTempDataTitleId());
        List<CategoryEntity> allCategories = categoryMapper.selectList(null);
        List<CategorySynonymEntity> synonyms = categorySynonymMapper.selectList(null);
        return categoryMatcher.match(buildMatchContext(tempData, extraAttrs, title, allCategories, synonyms));
    }

    /**
     * 从原始数据中提取匹配所需的纯数据，构建与业务解耦的匹配上下文。
     * 使用 title 中指定的分类列（categoryCol）取出分类名称；第 1 列作为分类编码候选；
     * 全描述解析出的属性值作为辅助匹配信号。
     */
    private CategoryMatchContext buildMatchContext(TempDataEntity tempData, Map<String, String> extraAttrs,
                                                   TempDataTitleEntity title, List<CategoryEntity> allCategories,
                                                   List<CategorySynonymEntity> synonyms) {
        CategoryMatchContext ctx = new CategoryMatchContext();
        ctx.setAllCategories(allCategories);
        ctx.setSynonyms(synonyms);

        // 分类名称：优先用指定列，其次第 2 列
        String categoryName = null;
        if (title != null && StrUtil.isNotBlank(title.getCategoryCol())) {
            int catIdx = findFullDescColIndex(title, title.getCategoryCol());
            if (catIdx > 0) categoryName = tempData.getColData(catIdx);
        }
        if (StrUtil.isBlank(categoryName)) {
            categoryName = tempData.getCol2();
        }
        ctx.setCategoryName(categoryName);

        // 分类编码：第 1 列
        ctx.setCategoryCode(tempData.getCol1());

        // 额外属性值（辅助匹配信号）
        if (extraAttrs != null && !extraAttrs.isEmpty()) {
            ctx.setExtraValues(new ArrayList<>(extraAttrs.values()));
        }
        return ctx;
    }



    private double calculateQuality(CleanedDataEntity cleanedData) {
        double score = 100.0;
        if (StrUtil.isBlank(cleanedData.getMaterialCode())) score -= 30;
        if (StrUtil.isBlank(cleanedData.getMaterialName())) score -= 30;
        if (StrUtil.isBlank(cleanedData.getTechnicalStandard())) score -= 20;
        if (StrUtil.isBlank(cleanedData.getGrade())) score -= 10;
        if (StrUtil.isBlank(cleanedData.getUnit())) score -= 10;
        if (cleanedData.getCategoryId() == null) score -= 20;
        if (cleanedData.getCategoryId() != null) score += 10;
        if (cleanedData.getCompletenessScore() != null && cleanedData.getCompletenessScore() > 80) score += 10;
        return Math.max(0, Math.min(score, 100));
    }

    private void createReviewTask(CleanedDataEntity cleanedData, String reason) {
        ReviewTaskEntity task = new ReviewTaskEntity();
        task.setTaskType(com.aiclean.entity.enums.ReviewTaskType.DATA_VALIDATION);
        task.setEntityType("cleaned_data");
        task.setEntityId(cleanedData.getId());
        task.setTitle("数据质量审核 - " + cleanedData.getMaterialCode());
        task.setDescription("质量评分过低，需人工审核。原因: " + reason);
        task.setPriority(com.aiclean.entity.enums.TaskPriority.MEDIUM);
        task.setStatus("pending");
        reviewTaskMapper.insert(task);
    }

    private ParseRule getDefaultParseRule() {
        ParseRule rule = new ParseRule();
        rule.setKeyValueSeparator(" ");
        rule.setItemSeparator(";");
        rule.setTrimSpaces(true);
        rule.setIgnoreEmptyItems(true);
        return rule;
    }

    /**
     * 通过 WebSocket 推送清洗进度到前端
     */
    private void sendCleaningProgress(Long titleId, String type, int current, int total,
                                       CleanedDataEntity cleanedData, int successCount, int errorCount) {
        try {
            Map<String, Object> msg = new LinkedHashMap<>();
            msg.put("type", type);
            msg.put("titleId", titleId);
            msg.put("current", current);
            msg.put("total", total);
            msg.put("successCount", successCount);
            msg.put("errorCount", errorCount);
            msg.put("progressPercent", total > 0 ? (int) ((double) current / total * 100) : 0);
            msg.put("timestamp", System.currentTimeMillis());

            if (cleanedData != null) {
                Map<String, Object> dataMap = new LinkedHashMap<>();
                dataMap.put("id", cleanedData.getId());
                dataMap.put("materialCode", cleanedData.getMaterialCode());
                dataMap.put("materialName", cleanedData.getMaterialName());
                dataMap.put("specification", cleanedData.getSpecification());
                dataMap.put("qualityScore", cleanedData.getQualityScore());
                dataMap.put("status", cleanedData.getStatus() != null ? cleanedData.getStatus().name() : "");
                dataMap.put("categoryCode", cleanedData.getCategoryCode());
                dataMap.put("categoryFullPath", cleanedData.getCategoryFullPath());
                msg.put("data", dataMap);
            }

            messagingTemplate.convertAndSend("/topic/cleaning/" + titleId, JSON.toJSONString(msg));
        } catch (Exception e) {
            log.warn("WebSocket 推送清洗进度失败: {}", e.getMessage());
        }
    }

    private String findBestFieldMatch(String sourceField, StandardTitleEntity standardTitle) {
        if (sourceField == null) return null;
        String lowerSource = sourceField.toLowerCase().replaceAll("\\s+", "");

        // 尝试精确匹配
        for (int i = 1; i <= 20; i++) {
            String stdCol = standardTitle.getColTitle(i);
            if (stdCol != null && lowerSource.equals(stdCol.toLowerCase().replaceAll("\\s+", ""))) {
                return stdCol;
            }
        }

        // 包含匹配
        for (int i = 1; i <= 20; i++) {
            String stdCol = standardTitle.getColTitle(i);
            if (stdCol != null) {
                String lowerStd = stdCol.toLowerCase().replaceAll("\\s+", "");
                if (lowerSource.contains(lowerStd) || lowerStd.contains(lowerSource)) {
                    return stdCol;
                }
            }
        }

        return null;
    }

    private double calculateConfidence(String source, String target) {
        if (source == null || target == null) return 0.0;
        String s = source.toLowerCase().replaceAll("\\s+", "");
        String t = target.toLowerCase().replaceAll("\\s+", "");
        if (s.equals(t)) return 1.0;
        if (s.contains(t) || t.contains(s)) return 0.8;
        return 0.3;
    }

    private String findMappedValue(List<FieldMappingAuditEntity> mappings, String standardColTitle,
                                    TempDataTitleEntity title, TempDataEntity tempData, ExtraDataEntity extraData) {
        for (FieldMappingAuditEntity mapping : mappings) {
            if (standardColTitle.equals(mapping.getTargetField())
                    || (mapping.getSuggestedTargetField() != null
                    && standardColTitle.equals(mapping.getSuggestedTargetField()))) {
                String sourceField = mapping.getSourceField();
                if ("temp_data".equals(mapping.getSourceType()) && title != null && tempData != null) {
                    for (int i = 1; i <= 10; i++) {
                        if (sourceField.equals(title.getColTitle(i))) return tempData.getColData(i);
                    }
                } else if ("extra_data".equals(mapping.getSourceType()) && extraData != null) {
                    ExtraDataTitleEntity extraTitle = extraDataTitleMapper.selectByTempDataTitleId(
                            tempData.getTempDataTitleId());
                    if (extraTitle != null) {
                        for (int i = 1; i <= 20; i++) {
                            if (sourceField.equals(extraTitle.getColTitle(i))) return extraData.getColData(i);
                        }
                    }
                }
            }
        }
        return null;
    }
}
