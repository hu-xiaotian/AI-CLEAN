package com.aiclean.service.impl;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.DigestUtil;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.aiclean.entity.*;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.aiclean.entity.enums.DataStatus;
import com.aiclean.mapper.*;
import com.aiclean.ai.AiClientService;
import com.aiclean.agent.ShardingAgent;
import com.aiclean.match.*;
import com.aiclean.model.ParseRule;
import com.aiclean.model.SearchCondition;
import com.aiclean.service.CategoryStandardLibrary;
import com.aiclean.service.DataCleaningService;
import com.aiclean.dto.CategoryDataCount;
import com.aiclean.dto.ClassifyCheckDetail;
import com.aiclean.dto.StatusCount;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
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
    @Autowired private ActiveLearningSampleMapper activeLearningSampleMapper;
    @Autowired private CategoryMatcher categoryMatcher;
    @Autowired private ShardingAgent shardingAgent;
    @Autowired @Qualifier("cleaningExecutor") private ThreadPoolTaskExecutor cleaningExecutor;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private SimpMessagingTemplate messagingTemplate;
    @Autowired private AiClientService aiClientService;
    @Autowired private CategoryStandardLibrary stdLib;

    @Value("${app.file.upload-path}") private String uploadPath;
    @Value("${app.data-cleaning.batch-size}") private int batchSize;
    @Value("${app.data-cleaning.quality-score.threshold-review}") private double thresholdReview;
    @Value("${app.data-cleaning.quality-score.threshold-export}") private double thresholdExport;

    /** 阈值自适应：依据整批评分分布动态计算 review/export 阈值（而非固定 60/80） */
    @Value("${app.data-cleaning.quality-score.adaptive:false}") private boolean adaptiveThreshold;
    @Value("${app.data-cleaning.quality-score.adaptive-review-percentile:30}") private double adaptiveReviewPercentile;
    @Value("${app.data-cleaning.quality-score.adaptive-export-percentile:70}") private double adaptiveExportPercentile;
    @Value("${app.data-cleaning.quality-score.adaptive-review-min:40}") private double adaptiveReviewMin;
    @Value("${app.data-cleaning.quality-score.adaptive-review-max:75}") private double adaptiveReviewMax;
    @Value("${app.data-cleaning.quality-score.adaptive-export-min:65}") private double adaptiveExportMin;
    @Value("${app.data-cleaning.quality-score.adaptive-export-max:95}") private double adaptiveExportMax;

    /** 多智能体并行清洗（Sharding Agent）配置：是否启用并行分片 */
    @Value("${app.data-cleaning.sharding.enabled:true}") private boolean shardingEnabled;
    /** 分片策略：HASH / CATEGORY_TREE（按一级分类分片） */
    @Value("${app.data-cleaning.sharding.strategy:CATEGORY_TREE}") private String shardingStrategy;
    /** 目标并行度（分片数上限） */
    @Value("${app.data-cleaning.sharding.parallelism:4}") private int shardingParallelism;

    /** AI 提取系统提示词（可配置，见 application.yml -> app.ai.system-prompt） */
    @Value("${app.ai.system-prompt:你是一个专业的数据属性提取助手。你的任务是根据用户给定的一组目标字段，从一段物料/商品的描述文本中提取对应的值。你必须只返回一个 JSON 对象：键为目标字段名（严格使用用户提供的字段名），值为提取到的内容字符串；未出现的字段填空字符串\"\"。不要输出任何解释或 Markdown 代码块，只输出纯 JSON。}")
    private String aiSystemPrompt;

    /** AI 提取用户提示词模板（支持占位符 {fields} 与 {fullDesc}，见 application.yml -> app.ai.user-prompt-template） */
    @Value("${app.ai.user-prompt-template:目标字段列表：\n{fields}\n\n待拆分的属性描述文本：\n{fullDesc}\n\n请按上述目标字段从描述文本中提取值，只返回 JSON 键值对。}")
    private String aiUserPromptTemplate;

    /** AI 辅助分类评分系统提示词 */
    @Value("${app.ai.classification-system-prompt:你是一名严谨的工业品物料数据质量审核专家，只输出要求的 JSON，不要输出其他内容。}")
    private String aiClassificationSystemPrompt;

    /** AI 辅助分类评分用户提示词模板（占位符见 application.yml -> app.ai.classification-score-prompt） */
    @Value("${app.ai.classification-score-prompt:请根据物料信息与标准分类库评估分类结果合理性并给出0~100评分。物料代码：{materialCode}，物料名称：{materialName}，规格：{specification}，牌号：{grade}，技术标准：{technicalStandard}；系统分类编码：{categoryCode}，分类名称：{categoryName}，路径：{categoryFullPath}；标准分类名称：{stdCategoryName}，路径：{stdCategoryFullPath}，说明：{stdDescription}，单位：{stdUnit}。只返回JSON：{\"score\":<0-100整数>,\"reason\":\"<简短说明>\"}。}")
    private String aiClassificationScorePrompt;

    /** AI 辅助分类检测系统提示词（多候选，基于 main_data_category 全表比对） */
    @Value("${app.ai.classification-detect-system-prompt:你是一名严谨的工业品物料数据质量审核专家，只输出要求的 JSON，不要输出其他内容。}")
    private String aiClassificationDetectSystemPrompt;

    /** AI 辅助分类检测用户提示词模板（占位符见 application.yml -> app.ai.classification-detect-prompt） */
    @Value("${app.ai.classification-detect-prompt:}")
    private String aiClassificationDetectPrompt;

    /** 标准库候选召回数量（top-K） */
    @Value("${app.data-cleaning.standard-library.candidate-top-k:10}")
    private int candidateTopK;

    /** AI 提取进度缓存（titleId -> 进度信息），供 WebSocket 与轮询兜底使用 */
    private final Map<Long, Map<String, Object>> aiExtractProgressMap = new ConcurrentHashMap<>();

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

        // 5.5 删除填充失败记录 (failed_result_data)
        // 必须放在删除 temp_data 之前，因 deleteByTitleId 依赖 temp_data 子查询定位
        int failedCount = failedResultDataMapper.deleteByTitleId(titleId);
        log.info("删除填充失败记录: {} 条", failedCount);

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

    // ==================== AI 智能提取 ====================

    @Override
    @Async
    public String startAiExtract(Long titleId) {
        log.info("开始 AI 属性提取，表头ID: {}", titleId);
        doStartAiExtract(titleId);
        return "ai_extract_task_" + titleId;
    }

    @Override
    public Map<String, Object> getAiExtractProgress(Long titleId) {
        Map<String, Object> progress = aiExtractProgressMap.get(titleId);
        if (progress == null) {
            Map<String, Object> empty = new LinkedHashMap<>();
            empty.put("type", "idle");
            empty.put("titleId", titleId);
            empty.put("current", 0);
            empty.put("total", 0);
            empty.put("successCount", 0);
            empty.put("errorCount", 0);
            empty.put("progressPercent", 0);
            return empty;
        }
        return progress;
    }

    /**
     * AI 属性提取核心逻辑（异步执行）：
     * 1. 逐行读取原始数据，按"指定分类列"或已清洗的分类编码确定该行的分类编码；
     * 2. 用分类编码查 standard_title 表得到标准字段（参数1）；
     * 3. 取"指定属性拆分列"（参数2）作为待拆分的描述文本；
     * 4. 调用大模型将描述文本按标准字段拆分，要求只返回 JSON 键值对；
     * 5. 解析 JSON，汇总所有键生成 extra_data_title 列，并写入 extra_data 行。
     */
    public void doStartAiExtract(Long titleId) {
        TempDataTitleEntity titleEntity = tempDataTitleMapper.selectById(titleId);
        if (titleEntity == null) {
            sendAiExtractProgress(titleId, "error", 0, 0, 0, 0, "表头不存在: " + titleId);
            return;
        }
        if (!aiClientService.isEnabled()) {
            sendAiExtractProgress(titleId, "error", 0, 0, 0, 0,
                    "AI 提取功能未启用，请在 application.yml 中配置 app.ai（base-url / api-key / model）");
            return;
        }

        // 列索引：分类列 & 属性拆分列（全描述列）
        int categoryColIndex = findColIndex(titleEntity, titleEntity.getCategoryCol());
        int fullDescIndex = findColIndex(titleEntity, titleEntity.getFullDescCol());

        List<TempDataEntity> tempDataList = tempDataMapper.selectByTitleId(titleId);
        final int total = tempDataList.size();
        if (total == 0) {
            sendAiExtractProgress(titleId, "error", 0, 0, 0, 0, "该文件没有可提取的数据");
            return;
        }

        // 预加载：分类（编码/名称 -> 实体）、已清洗数据（按原始数据ID）、标准表头（按分类编码缓存）
        List<CategoryEntity> allCategories = categoryMapper.selectList(null);
        Map<String, CategoryEntity> catByCode = new HashMap<>();
        Map<String, CategoryEntity> catByName = new HashMap<>();
        for (CategoryEntity c : allCategories) {
            if (c.getCategoryCode() != null) catByCode.put(c.getCategoryCode(), c);
            if (c.getCategoryName() != null) catByName.put(c.getCategoryName(), c);
        }
        Map<Long, CleanedDataEntity> cleanedByTempId = new HashMap<>();
        for (CleanedDataEntity cd : cleanedDataMapper.selectAllByTempDataTitleId(titleId)) {
            cleanedByTempId.put(cd.getTempDataId(), cd);
        }
        Map<String, StandardTitleEntity> stdCache = new HashMap<>();

        sendAiExtractProgress(titleId, "start", 0, total, 0, 0, null);

        // AI 调用（在事务外执行，避免长事务占用连接）
        List<Map<String, String>> allParsed = new ArrayList<>();
        Set<String> allKeys = new LinkedHashSet<>();
        int success = 0;
        int error = 0;

        for (int i = 0; i < tempDataList.size(); i++) {
            TempDataEntity tempData = tempDataList.get(i);
            Map<String, String> parsed = new LinkedHashMap<>();
            try {
                // 参数1：该行的标准字段（来自 standard_title）
                String categoryCode = resolveCategoryCode(tempData, categoryColIndex, cleanedByTempId, catByCode, catByName);
                if (categoryCode == null) {
                    log.debug("tempDataId {} 无法确定分类编码，跳过", tempData.getId());
                } else {
                    StandardTitleEntity stdTitle = stdCache.computeIfAbsent(
                            categoryCode, code -> standardTitleMapper.selectByCategoryCode(code));
                    if (stdTitle == null) {
                        log.debug("分类编码 {} 未找到标准字段表头，跳过", categoryCode);
                    } else {
                        List<String> fields = new ArrayList<>();
                        for (int c = 1; c <= 20; c++) {
                            String t = stdTitle.getColTitle(c);
                            if (StrUtil.isNotBlank(t)) fields.add(t);
                        }
                        // 参数2：属性拆分列文本
                        String fullDesc = fullDescIndex > 0 ? tempData.getColData(fullDescIndex) : "";
                        if (!fields.isEmpty() && StrUtil.isNotBlank(fullDesc)) {
                            String aiText = aiClientService.chat(buildAiSystemPrompt(), buildAiUserPrompt(fields, fullDesc));
                            parsed = parseAiJson(aiText, fields);
                            success++;
                        }
                    }
                }
            } catch (Exception e) {
                error++;
                log.error("AI 提取失败，tempDataId: {}", tempData.getId(), e);
            }
            allParsed.add(parsed);
            allKeys.addAll(parsed.keySet());
            sendAiExtractProgress(titleId, "progress", i + 1, total, success, error, null);
        }

        // 汇总列（最多 20 个）
        List<String> keyList = new ArrayList<>(allKeys);
        if (keyList.size() > 20) keyList = keyList.subList(0, 20);

        if (keyList.isEmpty()) {
            sendAiExtractProgress(titleId, "complete", total, total, success, error,
                    "未提取到任何属性，请确认：文件已设置分类列或已执行智能分类，且对应分类编码在标准字段表头中存在，且属性拆分列有内容");
            log.warn("AI 提取未产生任何属性，文件 {}", titleId);
            return;
        }

        // 入库（独立事务）
        final List<String> columnKeys = keyList;
        transactionTemplate.executeWithoutResult(status -> {
            ExtraDataTitleEntity extraTitle = new ExtraDataTitleEntity();
            extraTitle.setTempDataTitleId(titleId);
            extraTitle.setParseRuleId(null);
            for (int i = 0; i < columnKeys.size(); i++) {
                extraTitle.setColTitle(i + 1, columnKeys.get(i));
            }
            extraDataTitleMapper.insert(extraTitle);

            List<ExtraDataEntity> extraDataList = new ArrayList<>();
            for (int i = 0; i < tempDataList.size() && i < allParsed.size(); i++) {
                TempDataEntity td = tempDataList.get(i);
                Map<String, String> parsed = allParsed.get(i);
                ExtraDataEntity ed = new ExtraDataEntity();
                ed.setExtraDataTitleId(extraTitle.getId());
                ed.setTempDataId(td.getId());
                for (int j = 0; j < columnKeys.size(); j++) {
                    ed.setColData(j + 1, parsed.getOrDefault(columnKeys.get(j), ""));
                }
                extraDataList.add(ed);
                if (extraDataList.size() >= batchSize) {
                    extraDataMapper.insertBatch(extraDataList);
                    extraDataList.clear();
                }
            }
            if (!extraDataList.isEmpty()) extraDataMapper.insertBatch(extraDataList);
        });

        sendAiExtractProgress(titleId, "complete", total, total, success, error,
                "AI 属性提取完成，共提取 " + keyList.size() + " 个属性");
        log.info("AI 属性提取完成，文件 {}，属性数 {}，数据量 {}", titleId, keyList.size(), tempDataList.size());
    }

    /**
     * 确定某行数据的分类编码：
     * 优先使用已清洗数据中的分类编码；否则尝试用"指定分类列"的值匹配分类编码或分类名称。
     */
    private String resolveCategoryCode(TempDataEntity tempData, int categoryColIndex,
                                       Map<Long, CleanedDataEntity> cleanedByTempId,
                                       Map<String, CategoryEntity> catByCode,
                                       Map<String, CategoryEntity> catByName) {
        CleanedDataEntity cd = cleanedByTempId.get(tempData.getId());
        if (cd != null && StrUtil.isNotBlank(cd.getCategoryCode())) {
            return cd.getCategoryCode();
        }
        if (categoryColIndex > 0) {
            String val = tempData.getColData(categoryColIndex);
            if (StrUtil.isNotBlank(val)) {
                if (catByCode.containsKey(val)) return val;
                CategoryEntity byName = catByName.get(val);
                if (byName != null) return byName.getCategoryCode();
                String trimmed = val.trim();
                if (catByCode.containsKey(trimmed)) return trimmed;
                for (Map.Entry<String, CategoryEntity> e : catByName.entrySet()) {
                    if (e.getKey() != null && e.getKey().trim().equals(trimmed)) {
                        return e.getValue().getCategoryCode();
                    }
                }
            }
        }
        return null;
    }

    private int findColIndex(TempDataTitleEntity title, String colName) {
        if (title == null || StrUtil.isBlank(colName)) return -1;
        for (int i = 1; i <= 10; i++) {
            if (colName.equals(title.getColTitle(i))) return i;
        }
        return -1;
    }

    private String buildAiSystemPrompt() {
        return aiSystemPrompt;
    }

    private String buildAiUserPrompt(List<String> fields, String fullDesc) {
        return aiUserPromptTemplate
                .replace("{fields}", String.join("、", fields))
                .replace("{fullDesc}", fullDesc);
    }

    /**
     * 解析 AI 返回的 JSON 文本，并将其键映射到标准字段名。
     * 兼容模型可能返回的 Markdown 代码块包裹、前后多余文字等情况。
     */
    private Map<String, String> parseAiJson(String aiText, List<String> fields) {
        Map<String, String> result = new LinkedHashMap<>();
        if (StrUtil.isBlank(aiText)) return result;
        String text = aiText.trim();

        // 去掉可能的 ```json ... ``` 代码块包裹
        if (text.startsWith("```")) {
            int firstNL = text.indexOf('\n');
            if (firstNL >= 0) text = text.substring(firstNL + 1);
            int lastFence = text.lastIndexOf("```");
            if (lastFence >= 0) text = text.substring(0, lastFence);
            text = text.trim();
        }
        // 截取第一个 { 到最后一个 } 之间的内容
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start >= 0 && end > start) {
            text = text.substring(start, end + 1);
        }

        try {
            JSONObject obj = JSON.parseObject(text);
            Map<String, String> fieldNorm = new HashMap<>();
            for (String f : fields) fieldNorm.put(normalize(f), f);

            for (String key : obj.keySet()) {
                String val = obj.getString(key);
                if (val == null) val = "";
                String stdField = fieldNorm.get(normalize(key));
                if (stdField == null) {
                    // 模糊匹配：包含关系
                    String nk = normalize(key);
                    for (String f : fields) {
                        String nf = normalize(f);
                        if (nf.equals(nk) || nf.contains(nk) || nk.contains(nf)) {
                            stdField = f;
                            break;
                        }
                    }
                }
                if (stdField != null) {
                    result.put(stdField, val);
                }
            }
        } catch (Exception e) {
            log.warn("AI 返回内容解析为 JSON 失败，原文: {}", aiText);
        }
        return result;
    }

    private String normalize(String s) {
        return s == null ? "" : s.trim().toLowerCase().replaceAll("\\s+", "");
    }

    private void sendAiExtractProgress(Long titleId, String type, int current, int total,
                                       int success, int error, String message) {
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("type", type);
        msg.put("titleId", titleId);
        msg.put("current", current);
        msg.put("total", total);
        msg.put("successCount", success);
        msg.put("errorCount", error);
        msg.put("progressPercent", total > 0 ? (int) ((double) current / total * 100) : 0);
        msg.put("timestamp", System.currentTimeMillis());
        if (message != null) msg.put("message", message);
        aiExtractProgressMap.put(titleId, msg);
        try {
            messagingTemplate.convertAndSend("/topic/ai-extract/" + titleId, JSON.toJSONString(msg));
        } catch (Exception e) {
            log.warn("WebSocket 推送 AI 提取进度失败: {}", e.getMessage());
        }
    }

    // ==================== 分类匹配与清洗 ====================

    @Override
    @Transactional
    public CleanedDataEntity matchAndClean(Long tempDataId, Long extraDataTitleId, Long parseRuleId, Boolean useAi) {
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
        fillCategoryInfo(cleanedData, matchedCategory);

        // 记录导入时指定的"属性拆分列"原始文本，供 AI 打分匹配使用；并计算行指纹用于数据血缘/去重
        cleanedData.setFullDescription(fullDescription);
        cleanedData.setSourceRowHash(computeSourceRowHash(cleanedData));
        // 单条清洗不跨行去重，置为 0
        cleanedData.setIsDuplicate(0);

        // 提取核心字段
        cleanedData.setMaterialCode(getColByTitle(titleEntity, "物料代码", tempData, 3));
        cleanedData.setMaterialName(extraAttrs.getOrDefault("物资名称", extraAttrs.getOrDefault("物资简称", "")));
        cleanedData.setSpecification(extraAttrs.getOrDefault("规格", ""));
        cleanedData.setTechnicalStandard(extraAttrs.getOrDefault("技术标准号", ""));
        cleanedData.setGrade(extraAttrs.getOrDefault("牌号", ""));
        cleanedData.setUnit(extraAttrs.getOrDefault("计量单位", getColByTitle(titleEntity, "计量单位", tempData, 6)));

        // 质量评分（启用 AI 辅助评分且 AI 可用时，用 AI 评分替代原有规则评分）
        cleanedData.setCompletenessScore(cleanedData.calculateCompleteness());
        double qualityScore = computeQualityScore(cleanedData, matchedCategory, useAi);
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
    public String startCleaning(Long titleId, Long parseRuleId, Boolean useAi) {
        log.info("开始数据清洗，表头ID: {}, 规则ID: {}, useAi: {}", titleId, parseRuleId, useAi);
        doStartCleaning(titleId, parseRuleId, useAi);
        return "cleaning_task_" + titleId;
    }

    /**
     * 实际的清洗执行逻辑，由 startCleaning 异步调用
     * 使用 TransactionTemplate 确保在异步线程中事务正确生效
     */
    public void doStartCleaning(Long titleId, Long parseRuleId, Boolean useAi) {
        // 0. 如果该批次已清洗过，先清理旧数据，确保重新生成。
        // 注意：清理必须在并行清洗之前、且在独立事务（自动提交）中提交，
        // 否则外层事务会持有 cleaned_data 的间隙锁(gap lock)，与并行 Worker 的 INSERT 相互阻塞，
        // 导致 "Lock wait timeout exceeded"。因此此处放在外层 transactionTemplate 之前执行。
        cleanPreviousCleaningData(titleId);

        // 标记表头为"清洗中"并立即提交（独立自动提交，不在外层事务内），
        // 使"智能分类"页在长时间 AI 清洗期间能即时看到进行中状态（否则会一直显示草稿直到整批结束）。
        TempDataTitleEntity titleForStatus = tempDataTitleMapper.selectById(titleId);
        if (titleForStatus != null) {
            titleForStatus.setStatus(DataStatus.PROCESSING);
            tempDataTitleMapper.updateById(titleForStatus);
        }

        transactionTemplate.executeWithoutResult(status -> {
            try {
                log.info("异步清洗任务开始执行，表头ID: {}, 规则ID: {}", titleId, parseRuleId);

                // 1. 预加载共享数据，避免循环内重复查询
                // 说明：全描述属性提取（extractExtraData）已拆分为独立步骤，
                // 不再在智能分类时自动执行，需由调用方（如一键数据清洗）显式触发。
                TempDataTitleEntity titleEntity = tempDataTitleMapper.selectById(titleId);

                ParseRuleEntity ruleEntity = parseRuleMapper.selectById(parseRuleId);
                ParseRule parseRule = ruleEntity != null ? ruleEntity.toParseRule() : getDefaultParseRule();
                List<CategoryEntity> allCategories = categoryMapper.selectList(null);
                List<CategorySynonymEntity> synonyms = categorySynonymMapper.selectList(null);
                log.info("预加载完成，分类总数: {}, 同义词数: {}, 待清洗数据: {}", allCategories.size(), synonyms.size(), tempDataMapper.countByTitleId(titleId));

                // 2. 批量匹配清洗（多智能体编排：Orchestrator + Sharding Agent + 并行 Worker）
                List<TempDataEntity> tempDataList = tempDataMapper.selectByTitleId(titleId);
                // 数据血缘：同文件内行指纹集合（并发安全），用于标记重复数据
                Set<String> seenHashes = ConcurrentHashMap.newKeySet();
                final int totalCount = tempDataList.size();

                // 2.1 Sharding Agent：把待清洗数据按策略分片
                ShardingAgent.ShardStrategy strategy = !shardingEnabled ? ShardingAgent.ShardStrategy.HASH
                        : ("HASH".equalsIgnoreCase(shardingStrategy) ? ShardingAgent.ShardStrategy.HASH
                                : ShardingAgent.ShardStrategy.CATEGORY_TREE);
                Map<Long, String> categoryTreeKeys = Collections.emptyMap();
                if (strategy == ShardingAgent.ShardStrategy.CATEGORY_TREE) {
                    // 预匹配一级分类作为分片 key（仅用于调度，清洗阶段仍独立匹配）
                    categoryTreeKeys = computeCategoryTreeKeys(tempDataList, parseRule, titleEntity, allCategories, synonyms);
                }
                int parallelism = shardingEnabled ? Math.max(1, shardingParallelism) : 1;
                List<List<TempDataEntity>> shards = shardingAgent.shard(tempDataList, strategy, parallelism, categoryTreeKeys);
                log.info("Sharding Agent 分片完成，策略: {}, 并行度: {}, 分片数: {}", strategy, parallelism, shards.size());

                // 2.2 并行 Worker：每个 shard 由独立线程在各自事务中清洗，状态延迟打标（deferStatus=true）
                List<CleanedDataEntity> allCleaned = Collections.synchronizedList(new ArrayList<>());
                List<Double> allScores = Collections.synchronizedList(new ArrayList<>());
                AtomicInteger successCount = new AtomicInteger(0);
                AtomicInteger errorCount = new AtomicInteger(0);
                AtomicBoolean stopped = new AtomicBoolean(false);

                sendCleaningProgress(titleId, "start", 0, totalCount, null, 0, 0);

                ExecutorService es = cleaningExecutor.getThreadPoolExecutor();
                List<Callable<Void>> tasks = new ArrayList<>();
                // 分片内分批提交：每批独立事务，避免单个大事务长时间持有锁，并缩小故障爆炸半径
                final int CLEAN_BATCH_SIZE = 100;
                for (List<TempDataEntity> shard : shards) {
                    tasks.add(() -> {
                        if (stopped.get()) return null;
                        AtomicInteger localErr = new AtomicInteger(0);
                        int batchStart = 0;
                        while (batchStart < shard.size() && !stopped.get()) {
                            int batchEnd = Math.min(batchStart + CLEAN_BATCH_SIZE, shard.size());
                            List<TempDataEntity> batch = shard.subList(batchStart, batchEnd);
                            transactionTemplate.executeWithoutResult(s -> {
                                for (TempDataEntity td : batch) {
                                    if (stopped.get()) break;
                                    try {
                                        CleanedDataEntity cleaned = matchAndCleanInternal(td, null, parseRule,
                                                titleEntity, allCategories, synonyms, useAi, seenHashes, true);
                                        allCleaned.add(cleaned);
                                        allScores.add(cleaned.getQualityScore() != null ? cleaned.getQualityScore() : 0.0);
                                        int cur = successCount.incrementAndGet();
                                        sendCleaningProgress(titleId, "progress", cur, totalCount, cleaned, cur, errorCount.get());
                                    } catch (Exception e) {
                                        localErr.incrementAndGet();
                                        int cur = errorCount.incrementAndGet();
                                        log.error("并行清洗失败，tempDataId: {}", td.getId(), e);
                                        sendCleaningProgress(titleId, "progress", successCount.get() + cur, totalCount, null, successCount.get(), cur);
                                        if (localErr.get() >= 5) {
                                            stopped.set(true);
                                            log.error("分片内连续失败 {} 次，停止后续清洗。已成功: {}, 失败: {}", localErr.get(), successCount.get(), errorCount.get());
                                            break;
                                        }
                                    }
                                }
                            });
                            batchStart = batchEnd;
                        }
                        return null;
                    });
                }
                try {
                    List<Future<Void>> futures = es.invokeAll(tasks);
                    for (Future<Void> f : futures) {
                        try { f.get(); } catch (Exception e) { log.error("分片执行异常", e); }
                    }
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    stopped.set(true);
                }
                int done = successCount.get() + errorCount.get();
                sendCleaningProgress(titleId, "progress", done, totalCount, null, successCount.get(), errorCount.get());

                // 2.3 第二阶段：阈值自适应统一打标（初始清洗也启用自适应阈值）+ 建审核任务
                double[] thr = resolveThresholds(allScores);
                double review = thr[0], export = thr[1];
                for (CleanedDataEntity cd : allCleaned) {
                    double score = cd.getQualityScore() != null ? cd.getQualityScore() : 0.0;
                    applyStatus(cd, score, review, export);
                    if (score < review) {
                        createReviewTask(cd, "质量评分过低: " + score);
                    }
                    cleanedDataMapper.updateById(cd);
                }

                // 2.4 低置信样本沉淀（主动学习）：初始清洗阶段若启用 AI，则把低分/未匹配样本沉淀为 LOW_CONFIDENCE
                if (Boolean.TRUE.equals(useAi) && aiClientService.isEnabled()) {
                    for (CleanedDataEntity cd : allCleaned) {
                        double score = cd.getQualityScore() != null ? cd.getQualityScore() : 0.0;
                        boolean matched = cd.getCategoryId() != null && !"UNMATCHED".equals(cd.getMatchSource());
                        if (score < review && !matched) {
                            persistLowConfidenceSample(cd, score);
                        }
                    }
                }

                // 更新表头状态
                titleEntity.setStatus(DataStatus.COMPLETED);
                tempDataTitleMapper.updateById(titleEntity);

                // 发送完成消息
                sendCleaningProgress(titleId, "complete", totalCount, totalCount, null, successCount.get(), errorCount.get());

                log.info("数据清洗完成，成功: {}, 失败: {}", successCount.get(), errorCount.get());
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

    /** 为每条原始数据预匹配一级分类，作为 CATEGORY_TREE 分片 key（仅用于调度，清洗阶段仍独立匹配） */
    private Map<Long, String> computeCategoryTreeKeys(List<TempDataEntity> list, ParseRule parseRule,
                                                      TempDataTitleEntity titleEntity,
                                                      List<CategoryEntity> allCategories,
                                                      List<CategorySynonymEntity> synonyms) {
        Map<Long, String> map = new HashMap<>();
        for (TempDataEntity td : list) {
            try {
                String fullDescription = "";
                if (titleEntity != null && StrUtil.isNotBlank(titleEntity.getFullDescCol())) {
                    int idx = findFullDescColIndex(titleEntity, titleEntity.getFullDescCol());
                    if (idx > 0) fullDescription = td.getColData(idx);
                }
                Map<String, String> extraAttrs = parseRule.parse(fullDescription);
                CategoryMatchOutcome outcome = categoryMatcher.match(
                        buildMatchContext(td, extraAttrs, titleEntity, allCategories, synonyms));
                if (outcome.getCategory() != null && StrUtil.isNotBlank(outcome.getCategory().getFullPath())) {
                    String fp = outcome.getCategory().getFullPath();
                    map.put(td.getId(), fp.split("/")[0]);
                } else {
                    map.put(td.getId(), "UNMATCHED");
                }
            } catch (Exception e) {
                map.put(td.getId(), "UNKNOWN");
            }
        }
        return map;
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
                                                     List<CategorySynonymEntity> synonyms, Boolean useAi,
                                                     Set<String> seenHashes, boolean deferStatus) {
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
        fillCategoryInfo(cleanedData, matchedCategory);

        // 记录导入时指定的"属性拆分列"原始文本，供 AI 打分匹配使用；并计算行指纹用于数据血缘/去重
        cleanedData.setFullDescription(fullDescription);
        String rowHash = computeSourceRowHash(cleanedData);
        cleanedData.setSourceRowHash(rowHash);
        // 数据血缘/去重：同文件内指纹重复标记，便于后续增量清洗与重复数据下钻
        if (seenHashes != null && rowHash != null && !seenHashes.add(rowHash)) {
            cleanedData.setIsDuplicate(1);
        } else {
            cleanedData.setIsDuplicate(0);
        }

        // 提取核心字段
        cleanedData.setMaterialCode(getColByTitle(titleEntity, "物料代码", tempData, 3));
        cleanedData.setMaterialName(extraAttrs.getOrDefault("物资名称", extraAttrs.getOrDefault("物资简称", "")));
        cleanedData.setSpecification(extraAttrs.getOrDefault("规格", ""));
        cleanedData.setTechnicalStandard(extraAttrs.getOrDefault("技术标准号", ""));
        cleanedData.setGrade(extraAttrs.getOrDefault("牌号", ""));
        cleanedData.setUnit(extraAttrs.getOrDefault("计量单位", getColByTitle(titleEntity, "计量单位", tempData, 6)));

        // 质量评分（启用 AI 辅助评分且 AI 可用时，用 AI 评分替代原有规则评分）
        cleanedData.setCompletenessScore(cleanedData.calculateCompleteness());
        double qualityScore = computeQualityScore(cleanedData, matchedCategory, useAi);
        cleanedData.setQualityScore(qualityScore);
        cleanedData.setAccuracyScore(qualityScore * 0.8);

        // 延迟打标（deferStatus=true）：初始清洗并行分片阶段使用，先占位为待审核，
        // 最终状态由编排器按整批评分分布（阈值自适应）在第二阶段统一判定，避免逐条用固定阈值导致分布失衡。
        if (deferStatus) {
            cleanedData.setStatus(DataStatus.NEEDS_REVIEW);
        } else {
            if (qualityScore < thresholdReview) {
                cleanedData.setStatus(DataStatus.NEEDS_REVIEW);
            } else if (qualityScore >= thresholdExport) {
                cleanedData.setStatus(DataStatus.EXPORT_READY);
            } else {
                cleanedData.setStatus(DataStatus.APPROVED);
            }
        }

        // 分类未命中三级：不赋一/二级编码，标记为待审核（无效数据页统计）
        if (matchedCategory == null
                || "UNMATCHED".equals(matchResult.getSource())) {
            cleanedData.setStatus(DataStatus.NEEDS_REVIEW);
        }

        cleanedDataMapper.insert(cleanedData);

        // 审核任务延迟至第二阶段（仅当质量分低于自适应阈值时创建），避免延迟模式下重复创建
        if (!deferStatus && qualityScore < thresholdReview) {
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
                extraTitle != null ? extraTitle.getId() : null, null, null);
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
        // targetField -> mapping 索引，避免逐行逐列线性遍历
        Map<String, FieldMappingAuditEntity> mappingByTarget = new HashMap<>();
        for (FieldMappingAuditEntity m : mappings) {
            if (m.getTargetField() != null && !mappingByTarget.containsKey(m.getTargetField())) {
                mappingByTarget.put(m.getTargetField(), m);
            }
            if (m.getSuggestedTargetField() != null && !mappingByTarget.containsKey(m.getSuggestedTargetField())) {
                mappingByTarget.put(m.getSuggestedTargetField(), m);
            }
        }

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
        // 补充表头列定义（整个文件恒定），只查一次，消除 findMappedValue 内的 N+1 查询
        ExtraDataTitleEntity extraTitle = extraDataTitleId != null ? extraDataTitleMapper.selectById(extraDataTitleId) : null;

        // 缓存：categoryCode -> StandardTitleEntity，避免重复DB查询
        Map<String, StandardTitleEntity> standardTitleCache = new HashMap<>();

        List<ResultDataEntity> resultList = new ArrayList<>();
        List<ResultDataEntity> batch = new ArrayList<>();
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

                String value = findMappedValue(mappingByTarget, standardColTitle,
                        titleEntity, tempData, extraData, extraTitle);
                if (StrUtil.isNotBlank(value)) {
                    result.setColData(colIdx, value);
                }
            }

            // 按 batchSize 批量插入，减少 DB 往返
            batch.add(result);
            resultList.add(result);
            if (batch.size() >= batchSize) {
                resultDataMapper.insertBatch(batch);
                batch.clear();
            }
        }
        if (!batch.isEmpty()) {
            resultDataMapper.insertBatch(batch);
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

        // 一次性加载本文件所需的全部基础数据，在所有标准表头间共享，避免每个标准表头重复全量查询
        FillContext ctx = buildFillContext(tempDataTitleId, extraDataTitleId);
        if (ctx.tempDataList == null || ctx.tempDataList.isEmpty()) {
            return "没有可填充的原始数据";
        }

        // 仅遍历「本文件存在字段映射」的标准表头，而非系统全部标准表头
        List<Long> relatedStandardIds = fieldMappingAuditMapper.selectDistinctStandardTitleIds(tempDataTitleId);
        if (relatedStandardIds == null || relatedStandardIds.isEmpty()) {
            log.info("数据文件 {} 无字段映射，无需填充", tempDataTitleId);
        } else {
            for (Long standardTitleId : relatedStandardIds) {
                StandardTitleEntity st = standardTitleMapper.selectById(standardTitleId);
                if (st == null) continue;
                log.info("触发填充标准表头: {}", standardTitleId);
                // 批量填充模式：不允许兜底，否则“找不到标准表头”的数据会被复制进每个表头，造成结果条数膨胀
                doStartFill(standardTitleId, ctx, false);
            }
        }

        // 批量填充结束后，将“分类编码找不到对应标准表头”的数据统一填充为一条空值结果记录
        // （standardTitleId=null），只填充一次，避免被复制进各标准表头导致膨胀，同时满足“可填充空值”。
        fillNoTitleResults(tempDataTitleId, extraDataTitleId, ctx);

        return "fill_all_completed";
    }

    /**
     * 单表头填充（异步入口）：自行加载上下文后填充。
     */
    public void doStartFill(Long standardTitleId, Long tempDataTitleId, Long extraDataTitleId, boolean allowFallback) {
        FillContext ctx = buildFillContext(tempDataTitleId, extraDataTitleId);
        doStartFill(standardTitleId, ctx, allowFallback);
    }

    /**
     * 实际的填充执行逻辑（核心）。
     * 复用外部已加载的 FillContext，避免在每个标准表头重复全量查询；
     * 结果数据按 batchSize 批量插入（useGeneratedKeys 回填主键用于进度回传）。
     */
    public void doStartFill(Long standardTitleId, FillContext ctx, boolean allowFallback) {
        transactionTemplate.executeWithoutResult(status -> {
            try {
                Long tempDataTitleId = ctx.tempDataTitleId;
                Long extraDataTitleId = ctx.extraDataTitleId;
                TempDataTitleEntity titleEntity = ctx.titleEntity;
                ExtraDataTitleEntity extraTitle = ctx.extraTitle;
                List<TempDataEntity> tempDataList = ctx.tempDataList;
                Map<Long, CleanedDataEntity> cleanedByTempId = ctx.cleanedByTempId;
                Map<Long, ExtraDataEntity> extraByTempId = ctx.extraByTempId;
                Map<String, StandardTitleEntity> standardTitleCache = ctx.standardTitleCache;

                log.info("异步填充任务开始执行，默认标准表头ID: {}", standardTitleId);

                // 默认标准表头（作为兜底）
                StandardTitleEntity defaultStandardTitle = standardTitleMapper.selectById(standardTitleId);
                if (defaultStandardTitle == null) throw new RuntimeException("标准表头不存在: " + standardTitleId);

                // 记录该数据文件关联的标准字段表头（供结果数据下拉框快速查询）
                recordTitleStandardTitle(tempDataTitleId, standardTitleId);

                // targetField -> mapping 索引，避免逐行逐列线性遍历 mappings（原每次调用 O(mappings)）
                List<FieldMappingAuditEntity> mappings = fieldMappingAuditMapper.selectByStandardAndTitle(standardTitleId, tempDataTitleId);
                Map<String, FieldMappingAuditEntity> mappingByTarget = new HashMap<>();
                for (FieldMappingAuditEntity m : mappings) {
                    if (m.getTargetField() != null && !mappingByTarget.containsKey(m.getTargetField())) {
                        mappingByTarget.put(m.getTargetField(), m);
                    }
                    if (m.getSuggestedTargetField() != null && !mappingByTarget.containsKey(m.getSuggestedTargetField())) {
                        mappingByTarget.put(m.getSuggestedTargetField(), m);
                    }
                }

                // 清除该标准表头+数据文件下已有的填充结果，实现覆盖而非跳过
                int deletedCount = resultDataMapper.deleteByStandardAndTitle(standardTitleId, tempDataTitleId);
                log.info("清除已有填充结果 {} 条，准备重新填充", deletedCount);

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
                int sentCount = 0;           // 已发送进度的累计行数（成功+失败），保证每条进度 current 唯一

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

                List<ResultDataEntity> batch = new ArrayList<>();

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

                            String value = findMappedValue(mappingByTarget, standardColTitle,
                                    titleEntity, tempData, extraData, extraTitle);
                            if (StrUtil.isNotBlank(value)) {
                                result.setColData(colIdx, value);
                                filledCount++;
                            }
                        }

                        batch.add(result);
                        successCount++;
                        // 达到批量阈值时落库，并回传进度（useGeneratedKeys 已回填 resultId）
                        if (batch.size() >= batchSize) {
                            sentCount = flushFillBatch(standardTitleId, batch, sentCount,
                                    totalCount, successCount, errorCount);
                            batch.clear();
                        }
                    } catch (Exception e) {
                        errorCount++;
                        sentCount++;
                        log.error("填充结果数据失败，tempDataId: {}", tempData.getId(), e);
                        // 推送错误进度
                        Map<String, Object> errMsg = buildFillProgressMsg("progress", sentCount, totalCount,
                                successCount, errorCount, tempData.getId(), null, null, null, 0);
                        messagingTemplate.convertAndSend("/topic/fill/" + standardTitleId, JSON.toJSONString(errMsg));
                    }
                }

                // 落库剩余批次并回传进度
                if (!batch.isEmpty()) {
                    sentCount = flushFillBatch(standardTitleId, batch, sentCount,
                            totalCount, successCount, errorCount);
                    batch.clear();
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
     * 将当前批次的结果数据批量落库，并为每条数据回传一条进度消息。
     * 依赖 result_data 批量插入的 useGeneratedKeys 回填主键，进度中的 resultId 才有效。
     *
     * @return 更新后的已发送进度计数（每条数据 +1，保证 current 唯一）
     */
    private int flushFillBatch(Long standardTitleId, List<ResultDataEntity> batch,
                               int sentCount, int totalCount, int successCount, int errorCount) {
        if (batch.isEmpty()) return sentCount;
        resultDataMapper.insertBatch(batch);
        for (ResultDataEntity r : batch) {
            sentCount++;
            Map<String, Object> msg = buildFillProgressMsg("progress", sentCount, totalCount,
                    successCount, errorCount, r.getTempDataId(), r.getCleanedDataId(),
                    r.getId(), r.getStatus(), countFilled(r));
            messagingTemplate.convertAndSend("/topic/fill/" + standardTitleId, JSON.toJSONString(msg));
        }
        return sentCount;
    }

    /**
     * 构造一条填充进度消息（与历史消息结构保持一致）。
     */
    private Map<String, Object> buildFillProgressMsg(String type, int current, int total,
                                                     int successCount, int errorCount,
                                                     Long tempDataId, Long cleanedDataId,
                                                     Long resultId, String status, int filledCount) {
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("type", type);
        msg.put("current", current);
        msg.put("total", total);
        msg.put("successCount", successCount);
        msg.put("errorCount", errorCount);
        msg.put("progressPercent", total > 0 ? (int) ((double) current / total * 100) : 100);
        msg.put("timestamp", System.currentTimeMillis());
        Map<String, Object> dataMap = new LinkedHashMap<>();
        dataMap.put("resultId", resultId);
        dataMap.put("tempDataId", tempDataId);
        dataMap.put("cleanedDataId", cleanedDataId);
        dataMap.put("status", status);
        dataMap.put("filledCount", filledCount);
        msg.put("data", dataMap);
        return msg;
    }

    /**
     * 统计结果数据已填充的字段数（col1..col20 中非空的数量）。
     */
    private int countFilled(ResultDataEntity r) {
        int n = 0;
        for (int i = 1; i <= 20; i++) {
            if (StrUtil.isNotBlank(r.getColData(i))) n++;
        }
        return n;
    }

    /**
     * 一次性加载某个数据文件在「属性补全」阶段所需的全部基础数据，
     * 供多个标准表头的 doStartFill / fillNoTitleResults 共享，避免每个标准表头重复全量查询
     * （原实现每个标准表头都重查一遍 temp_data / cleaned_data / extra_data）。
     */
    private FillContext buildFillContext(Long tempDataTitleId, Long extraDataTitleId) {
        FillContext ctx = new FillContext();
        ctx.tempDataTitleId = tempDataTitleId;
        ctx.extraDataTitleId = extraDataTitleId;
        ctx.titleEntity = tempDataTitleMapper.selectById(tempDataTitleId);
        ctx.tempDataList = tempDataMapper.selectByTitleId(tempDataTitleId);

        // 预加载清洗数据（按 temp_data_id 索引），避免主循环内逐行查询
        ctx.cleanedByTempId = new HashMap<>();
        for (CleanedDataEntity cd : cleanedDataMapper.selectAllByTempDataTitleId(tempDataTitleId)) {
            ctx.cleanedByTempId.put(cd.getTempDataId(), cd);
        }

        // 预加载补充表头列定义（整个文件恒定，只查一次，消除 findMappedValue 内的 N+1 查询）
        // 以及补充数据行（按 temp_data_id 索引）
        if (extraDataTitleId != null) {
            ctx.extraTitle = extraDataTitleMapper.selectById(extraDataTitleId);
            ctx.extraByTempId = new HashMap<>();
            for (ExtraDataEntity ed : extraDataMapper.selectByExtraDataTitleId(extraDataTitleId)) {
                ctx.extraByTempId.put(ed.getTempDataId(), ed);
            }
        }

        // 预解析「分类编码 -> 标准表头」，在整个 fill-all 过程中只解析一次，
        // 供 doStartFill / fillNoTitleResults 共享，避免每个标准表头重复解析
        ctx.standardTitleCache = new HashMap<>();
        for (CleanedDataEntity cd : ctx.cleanedByTempId.values()) {
            if (cd != null && StrUtil.isNotBlank(cd.getCategoryCode())) {
                ctx.standardTitleCache.computeIfAbsent(
                        cd.getCategoryCode(), code -> standardTitleMapper.selectByCategoryCode(code));
            }
        }
        return ctx;
    }

    /**
     * 属性补全所需的预加载数据上下文。由 buildFillContext 一次性加载，
     * 在多个标准表头的 doStartFill / fillNoTitleResults 之间共享，避免重复全量查询。
     */
    private static class FillContext {
        Long tempDataTitleId;
        Long extraDataTitleId;
        TempDataTitleEntity titleEntity;
        ExtraDataTitleEntity extraTitle;                   // 补充表头列定义（列名->列序号），整个文件恒定
        List<TempDataEntity> tempDataList;
        Map<Long, CleanedDataEntity> cleanedByTempId;
        Map<Long, ExtraDataEntity> extraByTempId;         // 无补充表头时为 null
        Map<String, StandardTitleEntity> standardTitleCache; // 分类编码 -> 标准表头
    }

    /**
     * 将“分类编码找不到对应标准表头”的数据，统一填充为一条空值结果记录（standardTitleId=null）。
     * 仅在批量填充（fillAllStandardTitles）结束后调用一次，避免这类数据被复制进每个标准表头造成膨胀，
     * 同时满足“找不到对应分类代码时可填充空值”的需求。
     */
    private void fillNoTitleResults(Long tempDataTitleId, Long extraDataTitleId, FillContext ctx) {
        // 清除该文件下旧的填充失败记录，实现覆盖而非累加
        failedResultDataMapper.deleteByTitleId(tempDataTitleId);

        List<TempDataEntity> tempDataList = ctx.tempDataList;
        if (tempDataList == null || tempDataList.isEmpty()) return;

        // 复用 buildFillContext 已预加载的清洗数据 map 与标准表头缓存，避免逐行 selectByTempDataId 的 N+1 查询
        Map<Long, CleanedDataEntity> cleanedByTempId = ctx.cleanedByTempId;
        Map<String, StandardTitleEntity> cache = ctx.standardTitleCache;
        List<FailedResultDataEntity> failedList = new ArrayList<>();
        for (TempDataEntity td : tempDataList) {
            CleanedDataEntity cd = cleanedByTempId.get(td.getId());
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
    public Map<String, Object> getTempDataById(Long id) {
        Map<String, Object> result = new HashMap<>();
        TempDataEntity data = tempDataMapper.selectById(id);
        if (data == null) {
            result.put("title", null);
            result.put("data", null);
            return result;
        }
        TempDataTitleEntity title = tempDataTitleMapper.selectById(data.getTempDataTitleId());
        result.put("title", title);
        result.put("data", data);
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
        if (titleId != null) return failedResultDataMapper.selectByTitleId(titleId);
        return failedResultDataMapper.selectAll();
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

    @Override
    public Map<String, Object> getDashboardStatistics(Long titleId) {
        Map<String, Object> stats = new HashMap<>();
        boolean scoped = titleId != null;

        long fileCount = nullToZero(tempDataTitleMapper.selectCount(null));
        stats.put("fileCount", fileCount);

        long totalCleaned = scoped
                ? nullToZero(cleanedDataMapper.countByTitleId(titleId))
                : nullToZero(cleanedDataMapper.selectCount(null));
        stats.put("totalCleaned", totalCleaned);

        int totalRows = scoped
                ? nullToZeroInt(tempDataTitleMapper.sumTotalRowsByTitleId(titleId))
                : nullToZeroInt(tempDataTitleMapper.sumTotalRows());
        stats.put("totalRows", totalRows);

        long unmatch = scoped
                ? nullToZero(cleanedDataMapper.countUnmatchedByTitleId(titleId))
                : nullToZero(cleanedDataMapper.countUnmatched());
        long match = totalCleaned - unmatch;
        stats.put("matchCount", match < 0 ? 0 : match);
        stats.put("unmatchCount", unmatch);

        long success = scoped
                ? nullToZero(cleanedDataMapper.countFilledByTitleId(titleId))
                : nullToZero(cleanedDataMapper.countFilled());
        long failure = scoped
                ? nullToZero(failedResultDataMapper.countByTitleId(titleId))
                : nullToZero(failedResultDataMapper.selectCount(null));
        stats.put("successCount", success);
        stats.put("failureCount", failure);

        Double avg = scoped ? cleanedDataMapper.avgScoreByTitleId(titleId) : cleanedDataMapper.avgScore();
        stats.put("avgScore", avg != null ? Math.round(avg * 10) / 10.0 : 0);

        List<StatusCount> statusDist = scoped
                ? cleanedDataMapper.countByStatusByTitleId(titleId)
                : cleanedDataMapper.countByStatus();
        stats.put("statusDistribution", statusDist != null ? statusDist : new ArrayList<>());

        List<CategoryDataCount> catDist = scoped
                ? cleanedDataMapper.countByCategoryByTitleId(titleId)
                : cleanedDataMapper.countByCategoryTop();
        stats.put("categoryDistribution", catDist != null ? catDist : new ArrayList<>());

        List<Map<String, Object>> matchDist = new ArrayList<>();
        Map<String, Object> m1 = new HashMap<>(); m1.put("name", "分类匹配"); m1.put("value", stats.get("matchCount"));
        Map<String, Object> m2 = new HashMap<>(); m2.put("name", "分类不匹配"); m2.put("value", stats.get("unmatchCount"));
        matchDist.add(m1); matchDist.add(m2);
        stats.put("matchDistribution", matchDist);

        List<Map<String, Object>> fillDist = new ArrayList<>();
        Map<String, Object> f1 = new HashMap<>(); f1.put("name", "填充成功"); f1.put("value", success);
        Map<String, Object> f2 = new HashMap<>(); f2.put("name", "填充失败"); f2.put("value", failure);
        fillDist.add(f1); fillDist.add(f2);
        stats.put("fillDistribution", fillDist);

        if (scoped) {
            TempDataTitleEntity title = tempDataTitleMapper.selectById(titleId);
            if (title != null) stats.put("fileName", title.getFileName());
            stats.put("scope", "file");
            stats.put("fileId", titleId);
        } else {
            stats.put("scope", "all");
        }

        // 重复数据（数据血缘/去重）与低置信样本（主动学习）统计，供看板下钻
        long duplicate = scoped
                ? nullToZero(cleanedDataMapper.countDuplicatesByTitleId(titleId))
                : nullToZero(cleanedDataMapper.countDuplicates());
        stats.put("duplicateCount", duplicate);

        long lowConf = scoped
                ? nullToZero(activeLearningSampleMapper.countLowConfidenceByTitleId(titleId))
                : nullToZero(activeLearningSampleMapper.countLowConfidence());
        stats.put("lowConfidenceCount", lowConf);

        return stats;
    }

    @Override
    public List<CleanedDataEntity> getUnmatchedClassify(Long titleId) {
        if (titleId != null) {
            return cleanedDataMapper.selectUnmatchedByTitleId(titleId);
        }
        return cleanedDataMapper.selectUnmatchedAll();
    }

    @Override
    public List<CleanedDataEntity> getDuplicateData(Long titleId) {
        if (titleId != null) {
            return cleanedDataMapper.selectDuplicatesByTitleId(titleId);
        }
        return cleanedDataMapper.selectDuplicatesAll();
    }

    @Override
    public List<ActiveLearningSampleEntity> getLowConfidenceSamples(Long titleId) {
        if (titleId != null) {
            return activeLearningSampleMapper.selectLowConfidenceByTitleId(titleId);
        }
        return activeLearningSampleMapper.selectLowConfidenceAll();
    }

    private long nullToZero(Number v) { return v != null ? v.longValue() : 0L; }
    private int nullToZeroInt(Integer v) { return v != null ? v : 0; }

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

    /** 将匹配到的分类信息（含分类名称）填充到清洗数据 */
    private void fillCategoryInfo(CleanedDataEntity cleanedData, CategoryEntity matchedCategory) {
        if (matchedCategory != null) {
            cleanedData.setCategoryId(matchedCategory.getId());
            cleanedData.setCategoryCode(matchedCategory.getCategoryCode());
            cleanedData.setCategoryLevel(matchedCategory.getLevel());
            cleanedData.setCategoryFullPath(matchedCategory.getFullPath());
            cleanedData.setCategoryName(matchedCategory.getCategoryName());
        }
    }

    /**
     * 计算质量评分（即“与标准库对比的准确性评分”）：
     *  - 启用 AI 且 AI 可用时，调用大模型把系统分类与 main_data_category 标准库（召回的候选子集）对比，给出评分；
     *  - 否则用规则校验（确定性地把系统分类与标准库比对）。
     */
    private double computeQualityScore(CleanedDataEntity cleanedData, CategoryEntity matchedCategory, Boolean useAi) {
        if (Boolean.TRUE.equals(useAi) && aiClientService.isEnabled()) {
            List<CategoryStandardLibrary.Candidate> candidates = stdLib.retrieveCandidates(cleanedData, candidateTopK);
            return aiScoreClassification(cleanedData, matchedCategory, candidates);
        }
        return stdLib.ruleBasedAccuracy(cleanedData, matchedCategory);
    }

    /**
     * 调用大模型对分类结果进行 AI 质量评分：将分类结果与 main_data_category 标准库（召回候选）对比，给出 0~100 评分。
     */
    private double aiScoreClassification(CleanedDataEntity cleanedData, CategoryEntity matchedCategory,
                                          List<CategoryStandardLibrary.Candidate> candidates) {
        AiDetectResult result = aiDetect(cleanedData, matchedCategory, candidates);
        // 记录 AI 分类的理由描述，供智能分类页展示
        cleanedData.setAiReason(result.reason);
        return result.score;
    }

    /**
     * 调用大模型做分类检测：给定物料信息、系统分类与标准库候选子集，返回评分/一致性/最合理标准编码/说明。
     * 异常时回退到规则校验结果。
     */
    private AiDetectResult aiDetect(CleanedDataEntity cleanedData, CategoryEntity matchedCategory,
                                    List<CategoryStandardLibrary.Candidate> candidates) {
        AiDetectResult result = new AiDetectResult();
        try {
            StringBuilder sb = new StringBuilder();
            for (CategoryStandardLibrary.Candidate c : candidates) {
                CategoryEntity cat = c.getCategory();
                sb.append("- 编码:").append(StrUtil.nullToEmpty(cat.getCategoryCode()))
                        .append("，名称:").append(StrUtil.nullToEmpty(cat.getCategoryName()))
                        .append("，路径:").append(StrUtil.nullToEmpty(cat.getFullPath()))
                        .append("，单位:").append(StrUtil.nullToEmpty(cat.getUnit()))
                        .append("，说明:").append(truncate(cat.getDescription(), 200))
                        .append("\n");
            }
            String prompt = aiClassificationDetectPrompt
                    .replace("{materialCode}", StrUtil.nullToEmpty(cleanedData.getMaterialCode()))
                    .replace("{materialName}", StrUtil.nullToEmpty(cleanedData.getMaterialName()))
                    .replace("{specification}", StrUtil.nullToEmpty(cleanedData.getSpecification()))
                    .replace("{grade}", StrUtil.nullToEmpty(cleanedData.getGrade()))
                    .replace("{technicalStandard}", StrUtil.nullToEmpty(cleanedData.getTechnicalStandard()))
                    .replace("{unit}", StrUtil.nullToEmpty(cleanedData.getUnit()))
                    .replace("{categoryCode}", StrUtil.nullToEmpty(cleanedData.getCategoryCode()))
                    .replace("{categoryName}", StrUtil.nullToEmpty(cleanedData.getCategoryName()))
                    .replace("{categoryFullPath}", StrUtil.nullToEmpty(cleanedData.getCategoryFullPath()))
                    .replace("{fullDescription}", StrUtil.nullToEmpty(cleanedData.getFullDescription()))
                    .replace("{candidates}", sb.toString());
            // 反向校验提示：系统编码是否真实存在于标准库，直接告诉模型，避免其盲目“确认”
            boolean sysInLib = StrUtil.isNotBlank(cleanedData.getCategoryCode())
                    && stdLib.getByCode(cleanedData.getCategoryCode()) != null;
            String sysNote = sysInLib
                    ? "\n\n[反向校验提示] 系统分类编码 " + cleanedData.getCategoryCode() + " 存在于标准库中，但仍须依据物料内容独立判断其是否正确，不得仅因存在就认定 matched=true。"
                    : "\n\n[反向校验提示] 系统分类编码 " + cleanedData.getCategoryCode() + " 不在标准库中（标准库无此编码），因此系统分类必定有误，请从候选中选出正确编码作为 bestMatchCode。";
            String aiText = aiClientService.chat(aiClassificationDetectSystemPrompt, prompt + sysNote);
            return parseAiDetect(aiText);
        } catch (Exception e) {
            log.warn("AI 分类检测失败，回退规则校验，tempDataId: {}", cleanedData.getTempDataId(), e);
            try {
                CategoryStandardLibrary.RuleCheck rc = stdLib.ruleCheck(cleanedData, matchedCategory);
                result.score = rc.getScore();
                result.matched = rc.isConsistent();
                result.bestMatchCode = rc.getBestMatchCode();
                result.bestMatchName = rc.getBestMatchName();
                result.reason = "AI 失败，" + rc.getReason();
            } catch (Exception ex2) {
                // 最终兜底：绝不抛出，给保守分，避免单条数据中断整批清洗
                result.score = 0;
                result.matched = false;
                result.reason = "AI 与规则校验均失败：" + e.getMessage();
                log.warn("AI 与规则校验均失败，使用保守分 0，tempDataId: {}", cleanedData.getTempDataId(), ex2);
            }
            return result;
        }
    }

    /** 解析 AI 检测返回：{score, matched, bestMatchCode, reason} */
    private AiDetectResult parseAiDetect(String aiText) {
        AiDetectResult r = new AiDetectResult();
        if (StrUtil.isBlank(aiText)) throw new RuntimeException("AI 返回为空");
        String text = aiText.trim();
        if (text.startsWith("```")) {
            int nl = text.indexOf('\n');
            if (nl >= 0) text = text.substring(nl + 1);
            int fence = text.lastIndexOf("```");
            if (fence >= 0) text = text.substring(0, fence);
            text = text.trim();
        }
        int s = text.indexOf('{');
        int e = text.lastIndexOf('}');
        if (s < 0 || e <= s) throw new RuntimeException("AI 返回无 JSON");
        String json = text.substring(s, e + 1);
        JSONObject obj;
        try {
            obj = JSON.parseObject(json);
        } catch (Exception ex) {
            // 自愈：AI 可能返回非法 JSON 转义（如牌号 "45\C" 中的 \C），修正后重试一次
            log.warn("AI JSON 解析失败，尝试修正非法转义后重试: {}", ex.getMessage());
            obj = JSON.parseObject(sanitizeInvalidJsonEscape(json));
        }
        Object scoreObj = obj.get("score");
        if (scoreObj != null) r.score = Math.max(0, Math.min(Double.parseDouble(scoreObj.toString()), 100));
        Object matchedObj = obj.get("matched");
        if (matchedObj != null) r.matched = Boolean.parseBoolean(matchedObj.toString());
        Object bestObj = obj.get("bestMatchCode");
        if (bestObj != null) r.bestMatchCode = bestObj.toString().trim();
        Object reasonObj = obj.get("reason");
        if (reasonObj != null) r.reason = reasonObj.toString();
        if (StrUtil.isNotBlank(r.bestMatchCode)) {
            CategoryEntity best = stdLib.getByCode(r.bestMatchCode);
            if (best != null) r.bestMatchName = best.getCategoryName();
        }
        return r;
    }

    /**
     * 修正非法 JSON 转义：反斜杠后若不是合法转义字符（" \ / b f n r t u）时，
     * 将反斜杠转义为 "\\"，保留后续字符。用于兼容大模型偶尔输出的非法转义（如 "45\C"）。
     */
    private static String sanitizeInvalidJsonEscape(String json) {
        if (json == null) return null;
        StringBuilder sb = new StringBuilder(json.length() + 16);
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '\\') {
                if (i + 1 < json.length()) {
                    char n = json.charAt(i + 1);
                    if ("\"\\/bfnrtu".indexOf(n) >= 0) {
                        sb.append(c).append(n); // 合法转义，原样保留
                    } else {
                        sb.append("\\\\").append(n); // 非法转义，转义反斜杠并保留后续字符
                    }
                    i++;
                } else {
                    sb.append("\\\\"); // 行尾孤立反斜杠
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max);
    }

    /**
     * 计算原始行数据指纹（数据血缘/去重/增量清洗用）。
     * 基于分类编码、物料代码、规格、牌号与属性拆分列组合，MD5 后返回。
     */
    private String computeSourceRowHash(CleanedDataEntity cd) {
        if (cd == null) return null;
        String raw = StrUtil.nullToEmpty(cd.getCategoryCode()) + "|"
                + StrUtil.nullToEmpty(cd.getMaterialCode()) + "|"
                + StrUtil.nullToEmpty(cd.getSpecification()) + "|"
                + StrUtil.nullToEmpty(cd.getGrade()) + "|"
                + StrUtil.nullToEmpty(cd.getFullDescription());
        return DigestUtil.md5Hex(raw);
    }

    /** 解析 AI 返回的评分：兼容 JSON {"score":N} 或纯数字；解析失败抛出异常由调用方回退 */
    private double parseScoreFromAi(String aiText) {
        if (StrUtil.isBlank(aiText)) throw new RuntimeException("AI 返回为空");
        String text = aiText.trim();
        if (text.startsWith("```")) {
            int nl = text.indexOf('\n');
            if (nl >= 0) text = text.substring(nl + 1);
            int fence = text.lastIndexOf("```");
            if (fence >= 0) text = text.substring(0, fence);
            text = text.trim();
        }
        int s = text.indexOf('{');
        int e = text.lastIndexOf('}');
        if (s >= 0 && e > s) {
            JSONObject obj = JSON.parseObject(text.substring(s, e + 1));
            Object scoreObj = obj.get("score");
            if (scoreObj != null) return Double.parseDouble(scoreObj.toString());
        }
        // 纯数字兜底
        return Double.parseDouble(text.replaceAll("[^0-9.]", ""));
    }

    /**
     * AI 辅助分类检测（基于 main_data_category 标准库比对）。
     * 对文件下已清洗的数据重新评分：AI 模式调用大模型比对标准库候选，否则用规则校验；
     * 更新 quality_score / accuracy_score / status，并返回汇总与逐条明细。
     */
    /**
     * 文本分类识别：复用「AI 辅助分类检测」的核心检测逻辑（detectSingle），
     * 但把待识别内容替换为用户传入的任意文字（构造一个只有物料描述的临时清洗实体）。
     * AI 模式下由大模型在候选标准分类中选出最合理编码；未启用 AI 时退化为关键词召回的 top 候选。
     * 返回推荐的分类名称、分类编码与理由。
     */
    @Override
    public Map<String, Object> classifyText(String text, Boolean useAi) {
        if (StrUtil.isBlank(text)) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("message", "请输入待分类的物料描述文字");
            return r;
        }
        stdLib.ensureLoaded();
        boolean aiOn = Boolean.TRUE.equals(useAi) && aiClientService.isEnabled();

        // 构造临时清洗实体：仅填入用户文字作为物料名称与规格，无系统分类编码
        CleanedDataEntity cd = new CleanedDataEntity();
        cd.setMaterialName(text);
        cd.setSpecification(text);
        // 用户文字本身即"属性拆分列"内容，AI 打分依据它而非仅属性名称列
        cd.setFullDescription(text);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("input", text);
        result.put("useAi", aiOn);

        if (aiOn) {
            // 复用与 aiClassifyCheck 完全相同的单条检测逻辑（含 AI 识别与反向校验兜底）
            ClassifyCheckDetail d = detectSingle(cd, true);
            result.put("recommendedCode", d.getBestMatchCode());
            result.put("recommendedName", d.getBestMatchName());
            result.put("reason", d.getReason());
            result.put("score", d.getScore());
            result.put("candidateCount", d.getCandidateCount());
        } else {
            // 未启用 AI：无系统分类编码，退化为基于关键词召回的 top 候选作为推荐
            List<CategoryStandardLibrary.Candidate> candidates = stdLib.retrieveCandidates(cd, candidateTopK);
            if (candidates.isEmpty()) {
                result.put("recommendedCode", null);
                result.put("recommendedName", null);
                result.put("reason", "标准库中未找到与输入相关的分类，请补充更明确的物料描述");
                result.put("score", null);
            } else {
                CategoryStandardLibrary.Candidate top = candidates.get(0);
                result.put("recommendedCode", top.getCategory().getCategoryCode());
                result.put("recommendedName", top.getCategory().getCategoryName());
                result.put("reason", "基于关键词匹配，从标准库召回 " + candidates.size() + " 个候选，推荐相关性最高的分类（相关性 " + top.getRelevance() + "）");
                result.put("score", null);
            }
            result.put("candidateCount", candidates.size());
        }
        return result;
    }

    @Override
    public Map<String, Object> aiClassifyCheck(Long titleId, Boolean useAi) {
        stdLib.ensureLoaded();
        List<CleanedDataEntity> list = cleanedDataMapper.selectAllByTempDataTitleId(titleId);
        Map<String, Object> result = new LinkedHashMap<>();
        if (list == null || list.isEmpty()) {
            result.put("message", "该文件暂无清洗数据，请先执行数据清洗");
            result.put("total", 0);
            return result;
        }
        boolean aiOn = Boolean.TRUE.equals(useAi) && aiClientService.isEnabled();
        int total = list.size();
        double sum = 0;
        int matchedCount = 0;
        int mismatchCount = 0;
        List<ClassifyCheckDetail> details = new ArrayList<>();
        List<Double> scores = new ArrayList<>();
        // 第一轮：逐条检测，收集评分
        for (CleanedDataEntity cd : list) {
            ClassifyCheckDetail d = detectSingle(cd, aiOn);
            details.add(d);
            scores.add(d.getScore() != null ? d.getScore() : 0);
        }
        // 阈值自适应：依据整批分布计算 review/export 阈值并统一打标
        double[] thr = resolveThresholds(scores);
        double review = thr[0], export = thr[1];
        for (int i = 0; i < list.size(); i++) {
            CleanedDataEntity cd = list.get(i);
            ClassifyCheckDetail d = details.get(i);
            double score = scores.get(i);
            applyStatus(cd, score, review, export);
            if (aiOn && score < review && !Boolean.TRUE.equals(d.getMatched())) {
                persistLowConfidenceSample(cd, score);
            }
            cleanedDataMapper.updateById(cd);
            sum += score;
            if (Boolean.TRUE.equals(d.getMatched())) matchedCount++;
            else mismatchCount++;
        }
        result.put("total", total);
        result.put("reviewThreshold", review);
        result.put("exportThreshold", export);
        result.put("avgScore", total > 0 ? Math.round(sum / total * 10) / 10.0 : 0);
        result.put("matchedCount", matchedCount);
        result.put("mismatchCount", mismatchCount);
        result.put("useAi", aiOn);
        result.put("details", details);
        return result;
    }

    /**
     * 异步执行 AI 辅助分类检测：逐条检测并通过 WebSocket 推送进度与明细，
     * 避免同步阻塞（尤其 AI 模式下逐行调用大模型耗时很长）导致前端无响应、看不到数据。
     */
    @Override
    @Async
    public String aiClassifyCheckAsync(Long titleId, Boolean useAi) {
        log.info("开始 AI 辅助分类检测（异步），表头ID: {}, useAi: {}", titleId, useAi);
        try {
            stdLib.ensureLoaded();
            List<CleanedDataEntity> list = cleanedDataMapper.selectAllByTempDataTitleId(titleId);
            boolean aiOn = Boolean.TRUE.equals(useAi) && aiClientService.isEnabled();

            if (list == null || list.isEmpty()) {
                sendAiClassifyComplete(titleId, 0, 0, 0, 0, aiOn, "该文件暂无清洗数据，请先执行数据清洗");
                return "ai_classify_check_task_" + titleId;
            }

            int total = list.size();
            double sum = 0;
            int matchedCount = 0;
            int mismatchCount = 0;

            // 开始消息
            Map<String, Object> startMsg = new LinkedHashMap<>();
            startMsg.put("type", "start");
            startMsg.put("titleId", titleId);
            startMsg.put("total", total);
            startMsg.put("matchedCount", 0);
            startMsg.put("mismatchCount", 0);
            startMsg.put("progressPercent", 0);
            startMsg.put("useAi", aiOn);
            startMsg.put("timestamp", System.currentTimeMillis());
            sendAiClassifyMessage(titleId, startMsg);

            int current = 0;
            // 第一轮：逐条检测，收集评分（状态暂不落库，待自适应阈值统一打标）
            List<ClassifyCheckDetail> details = new ArrayList<>();
            List<Double> scores = new ArrayList<>();
            for (CleanedDataEntity cd : list) {
                current++;
                ClassifyCheckDetail d;
                try {
                    d = detectSingle(cd, aiOn);
                    cleanedDataMapper.updateById(cd);
                } catch (Exception e) {
                    log.error("AI 分类检测单条失败，cleanedDataId: {}", cd.getId(), e);
                    d = new ClassifyCheckDetail();
                    d.setId(cd.getId());
                    d.setMaterialCode(cd.getMaterialCode());
                    d.setMaterialName(cd.getMaterialName());
                    d.setCategoryCode(cd.getCategoryCode());
                    d.setCategoryName(cd.getCategoryName());
                    d.setScore(0.0);
                    d.setMatched(false);
                    d.setReason("检测异常: " + e.getMessage());
                }
                details.add(d);
                scores.add(d.getScore() != null ? d.getScore() : 0);
                sendAiClassifyProgress(titleId, "progress", current, total, d, matchedCount, mismatchCount, null);
            }

            // 阈值自适应：依据整批分布统一打标，并沉淀低置信样本（主动学习）
            double[] thr = resolveThresholds(scores);
            double review = thr[0], export = thr[1];
            for (int i = 0; i < list.size(); i++) {
                CleanedDataEntity cd = list.get(i);
                ClassifyCheckDetail d = details.get(i);
                double score = scores.get(i);
                applyStatus(cd, score, review, export);
                if (aiOn && score < review && !Boolean.TRUE.equals(d.getMatched())) {
                    persistLowConfidenceSample(cd, score);
                }
                cleanedDataMapper.updateById(cd);
                sum += score;
                if (Boolean.TRUE.equals(d.getMatched())) matchedCount++;
                else mismatchCount++;
            }

            double avg = total > 0 ? Math.round(sum / total * 10) / 10.0 : 0;
            sendAiClassifyComplete(titleId, total, matchedCount, mismatchCount, avg, aiOn, null);
            log.info("AI 辅助分类检测完成，表头ID: {}, 总数: {}, 一致: {}, 不一致: {}", titleId, total, matchedCount, mismatchCount);
        } catch (Exception e) {
            log.error("AI 辅助分类检测任务失败，表头ID: {}", titleId, e);
            Map<String, Object> errMsg = new LinkedHashMap<>();
            errMsg.put("type", "error");
            errMsg.put("titleId", titleId);
            errMsg.put("message", e.getMessage());
            errMsg.put("timestamp", System.currentTimeMillis());
            sendAiClassifyMessage(titleId, errMsg);
        }
        return "ai_classify_check_task_" + titleId;
    }

    private void sendAiClassifyComplete(Long titleId, int total, int matchedCount, int mismatchCount,
                                        double avgScore, boolean aiOn, String message) {
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("type", "complete");
        msg.put("titleId", titleId);
        msg.put("total", total);
        msg.put("matchedCount", matchedCount);
        msg.put("mismatchCount", mismatchCount);
        msg.put("avgScore", avgScore);
        msg.put("useAi", aiOn);
        msg.put("progressPercent", 100);
        msg.put("timestamp", System.currentTimeMillis());
        if (message != null) msg.put("message", message);
        sendAiClassifyMessage(titleId, msg);
    }

    private void sendAiClassifyProgress(Long titleId, String type, int current, int total,
                                        ClassifyCheckDetail detail, int matchedCount, int mismatchCount, String message) {
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("type", type);
        msg.put("titleId", titleId);
        msg.put("current", current);
        msg.put("total", total);
        msg.put("matchedCount", matchedCount);
        msg.put("mismatchCount", mismatchCount);
        msg.put("progressPercent", total > 0 ? (int) ((double) current / total * 100) : 0);
        msg.put("timestamp", System.currentTimeMillis());
        if (detail != null) msg.put("detail", detail);
        if (message != null) msg.put("message", message);
        sendAiClassifyMessage(titleId, msg);
    }

    private void sendAiClassifyMessage(Long titleId, Map<String, Object> msg) {
        try {
            messagingTemplate.convertAndSend("/topic/ai-classify-check/" + titleId, JSON.toJSONString(msg));
        } catch (Exception e) {
            log.warn("WebSocket 推送 AI 分类检测消息失败: {}", e.getMessage());
        }
    }

    /**
     * 依据整批评分分布计算 review/export 阈值（阈值自适应）。
     * 样本不足或关闭自适应时回退到固定配置阈值；结果按配置上下限钳制，且保证 export > review。
     */
    private double[] resolveThresholds(List<Double> scores) {
        if (!adaptiveThreshold || scores == null || scores.size() < 10) {
            return new double[]{thresholdReview, thresholdExport};
        }
        List<Double> sorted = new ArrayList<>(scores);
        sorted.sort(Double::compareTo);
        double review = percentile(sorted, adaptiveReviewPercentile / 100.0);
        double export = percentile(sorted, adaptiveExportPercentile / 100.0);
        review = Math.max(adaptiveReviewMin, Math.min(adaptiveReviewMax, review));
        export = Math.max(adaptiveExportMin, Math.min(adaptiveExportMax, export));
        if (export <= review) export = Math.min(adaptiveExportMax, review + 5);
        return new double[]{review, export};
    }

    /** 线性插值百分位数（sorted 为升序） */
    private double percentile(List<Double> sorted, double p) {
        if (sorted.isEmpty()) return 0;
        int n = sorted.size();
        double idx = (n - 1) * Math.max(0, Math.min(1, p));
        int lo = (int) Math.floor(idx);
        int hi = (int) Math.ceil(idx);
        if (lo == hi) return sorted.get(lo);
        return sorted.get(lo) + (idx - lo) * (sorted.get(hi) - sorted.get(lo));
    }

    /** 依据阈值对单条清洗数据打状态（NEEDS_REVIEW / APPROVED / EXPORT_READY） */
    private void applyStatus(CleanedDataEntity cd, double score, double review, double export) {
        if (score < review) cd.setStatus(DataStatus.NEEDS_REVIEW);
        else if (score >= export) cd.setStatus(DataStatus.EXPORT_READY);
        else cd.setStatus(DataStatus.APPROVED);
    }

    /** 沉淀低置信/未匹配样本（主动学习），同一清洗数据仅沉淀一次 */
    private void persistLowConfidenceSample(CleanedDataEntity cd, double score) {
        if (cd == null || cd.getId() == null) return;
        long exist = activeLearningSampleMapper.selectCount(
                new LambdaQueryWrapper<ActiveLearningSampleEntity>()
                        .eq(ActiveLearningSampleEntity::getEntityId, cd.getId())
                        .eq(ActiveLearningSampleEntity::getSampleType, "LOW_CONFIDENCE"));
        if (exist > 0) return;
        ActiveLearningSampleEntity s = new ActiveLearningSampleEntity();
        s.setSampleType("LOW_CONFIDENCE");
        s.setEntityId(cd.getId());
        s.setSourceText(cd.getFullDescription());
        s.setSourceCategoryName(cd.getCategoryName());
        s.setSourceCategoryCode(cd.getCategoryCode());
        s.setTargetCategoryId(cd.getCategoryId());
        s.setTargetCategoryCode(cd.getCategoryCode());
        s.setTargetCategoryName(cd.getCategoryName());
        s.setConfidence(cd.getMatchConfidence());
        s.setScore(score);
        s.setReason("AI 分类检测低置信/未匹配");
        s.setStatus("pending");
        activeLearningSampleMapper.insert(s);
    }

    /** 单条检测：评分 + 一致性判定 + 更新 cleanedData 的评分；状态由调用方按阈值统一打标 */
    private ClassifyCheckDetail detectSingle(CleanedDataEntity cd, boolean aiOn) {
        CategoryEntity matched = StrUtil.isNotBlank(cd.getCategoryCode())
                ? stdLib.getByCode(cd.getCategoryCode()) : null;
        List<CategoryStandardLibrary.Candidate> candidates = stdLib.retrieveCandidates(cd, candidateTopK);

        double score;
        boolean matchedFlag;
        String bestMatchCode;
        String bestMatchName;
        String reason;

        if (aiOn) {
            AiDetectResult r = aiDetect(cd, matched, candidates);
            score = r.score;
            bestMatchCode = r.bestMatchCode;
            bestMatchName = r.bestMatchName;
            reason = r.reason;
            // 确定性判定：用 AI 给出的最合理标准编码与系统编码比对，而非盲信 AI 的 matched 布尔值
            CategoryEntity sysCat = stdLib.getByCode(cd.getCategoryCode());
            CategoryEntity bestCat = StrUtil.isNotBlank(bestMatchCode) ? stdLib.getByCode(bestMatchCode) : null;
            if (sysCat == null) {
                matchedFlag = false; // 系统无有效分类，必为不一致
            } else if (bestCat == null) {
                matchedFlag = Boolean.TRUE.equals(r.matched); // AI 未给出可识别编码，退而信任其判断
            } else {
                matchedFlag = sysCat.getId().equals(bestCat.getId());
            }
            // 反向校验兜底：系统编码不在标准库 -> 必为不一致，并从候选中给出建议编码
            if (sysCat == null && StrUtil.isBlank(bestMatchCode) && !candidates.isEmpty()) {
                bestMatchCode = candidates.get(0).getCategory().getCategoryCode();
                bestMatchName = candidates.get(0).getCategory().getCategoryName();
            }
        } else {
            CategoryStandardLibrary.RuleCheck rc = stdLib.ruleCheck(cd, matched);
            score = rc.getScore();
            matchedFlag = rc.isConsistent();
            bestMatchCode = rc.getBestMatchCode();
            bestMatchName = rc.getBestMatchName();
            reason = rc.getReason();
        }

        cd.setQualityScore(score);
        cd.setAccuracyScore(score * 0.8);
        // 状态判定延迟到调用方：依据整批评分分布计算自适应阈值（resolveThresholds）后统一打标

        // 若建议编码与系统编码相同，并非“更好的建议”，不展示，避免“判不一致却建议同一编码”的歧义
        if (StrUtil.isNotBlank(bestMatchCode) && StrUtil.isNotBlank(cd.getCategoryCode())) {
            CategoryEntity sug = stdLib.getByCode(bestMatchCode);
            CategoryEntity sys = stdLib.getByCode(cd.getCategoryCode());
            if (sug != null && sys != null && sug.getId().equals(sys.getId())) {
                bestMatchCode = null;
                bestMatchName = null;
            }
        }

        ClassifyCheckDetail d = new ClassifyCheckDetail();
        d.setId(cd.getId());
        d.setMaterialCode(cd.getMaterialCode());
        d.setMaterialName(cd.getMaterialName());
        d.setCategoryCode(cd.getCategoryCode());
        d.setCategoryName(cd.getCategoryName());
        d.setScore(score);
        d.setMatched(matchedFlag);
        d.setBestMatchCode(matchedFlag ? null : bestMatchCode);
        d.setBestMatchName(matchedFlag ? null : bestMatchName);
        d.setReason(reason);
        d.setCandidateCount(candidates.size());
        return d;
    }

    /**
     * 应用分类修正：将指定清洗数据的分类字段替换为推荐的标准分类（按编码从标准库查找），
     * 替换后按标准库规则重新评分并保存。
     */
    @Override
    public Map<String, Object> applyClassifyFix(Long id, String targetCode) {
        return doApplyFix(id, targetCode, true);
    }

    @Override
    public Map<String, Object> applyClassifyFixBatch(Long titleId, List<Map<String, Object>> items) {
        stdLib.ensureLoaded();
        int applied = 0, skipped = 0, failed = 0;
        List<Map<String, Object>> results = new ArrayList<>();
        if (items != null) {
            for (Map<String, Object> it : items) {
                Object idObj = it.get("id");
                Object codeObj = it.get("code");
                if (idObj == null || codeObj == null) { skipped++; continue; }
                Long id = Long.valueOf(idObj.toString());
                String code = codeObj.toString();
                try {
                    results.add(doApplyFix(id, code, false));
                    applied++;
                } catch (Exception e) {
                    failed++;
                    Map<String, Object> err = new LinkedHashMap<>();
                    err.put("id", id);
                    err.put("code", code);
                    err.put("error", e.getMessage());
                    results.add(err);
                }
            }
        }
        // 批量修正完成后统一热重载标准库，使本次回流的同义词立即生效
        if (applied > 0) stdLib.reload();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("titleId", titleId);
        out.put("applied", applied);
        out.put("skipped", skipped);
        out.put("failed", failed);
        out.put("items", results);
        return out;
    }

    /** 将指定清洗数据的分类替换为推荐的标准分类（按编码从标准库查找），替换后重新评分并保存 */
    private Map<String, Object> doApplyFix(Long id, String targetCode, boolean reloadStdLib) {
        CleanedDataEntity cd = cleanedDataMapper.selectById(id);
        if (cd == null) throw new RuntimeException("清洗数据不存在: " + id);
        CategoryEntity target = stdLib.getByCode(targetCode);
        if (target == null) throw new RuntimeException("标准库中不存在编码: " + targetCode);
        // 记录修正前的(错误)分类，用于知识回流与主动学习样本沉淀
        String originalName = cd.getCategoryName();
        String originalCode = cd.getCategoryCode();
        // 用推荐的标准分类替换错误分类
        cd.setCategoryId(target.getId());
        cd.setCategoryCode(target.getCategoryCode());
        cd.setCategoryName(target.getCategoryName());
        cd.setCategoryLevel(target.getLevel());
        cd.setCategoryFullPath(target.getFullPath());
        // 替换后按标准库规则重新评分（编码已与标准一致，评分应较高）
        double score = stdLib.ruleBasedAccuracy(cd, target);
        cd.setQualityScore(score);
        cd.setAccuracyScore(score * 0.8);
        if (score < thresholdReview) cd.setStatus(DataStatus.NEEDS_REVIEW);
        else if (score >= thresholdExport) cd.setStatus(DataStatus.EXPORT_READY);
        else cd.setStatus(DataStatus.APPROVED);
        cleanedDataMapper.updateById(cd);
        // 自学习闭环：把人工修正沉淀为同义词（知识回流）+ 主动学习样本
        feedbackFromCorrection(id, originalName, originalCode, target, cd, score);
        if (reloadStdLib) stdLib.reload();
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("id", id);
        r.put("categoryCode", cd.getCategoryCode());
        r.put("categoryName", cd.getCategoryName());
        r.put("categoryFullPath", cd.getCategoryFullPath());
        r.put("score", score);
        r.put("status", cd.getStatus());
        return r;
    }

    /**
     * 修正非法 JSON 转义：反斜杠后若不是合法转义字符（" \ / b f n r t u）时，
     * 将反斜杠转义为 "\\"，保留后续字符。用于兼容大模型偶尔输出的非法转义（如 "45\C"）。
     */
    /**
     * 分类修正后的自学习闭环：
     * 1) 若原分类名称非空且不同于目标标准名称，将其作为同义词写回标准库（知识回流），
     *    下次同类数据可直接被 matchBySynonym 命中；
     * 2) 沉淀一条 CORRECTION 主动学习样本，用于后续训练/微调或规则优化。
     */
    private void feedbackFromCorrection(Long cleanedDataId, String originalName, String originalCode,
                                        CategoryEntity target, CleanedDataEntity cd, double score) {
        if (target == null) return;
        if (StrUtil.isNotBlank(originalName) && !originalName.trim().equals(target.getCategoryName())) {
            long exist = categorySynonymMapper.selectCount(
                    new LambdaQueryWrapper<CategorySynonymEntity>()
                            .eq(CategorySynonymEntity::getSynonymName, originalName.trim())
                            .eq(CategorySynonymEntity::getCategoryId, target.getId()));
            if (exist == 0) {
                CategorySynonymEntity syn = new CategorySynonymEntity();
                syn.setCategoryId(target.getId());
                syn.setSynonymName(originalName.trim());
                syn.setDescription("分类修正自动回流(" + StrUtil.nullToEmpty(originalCode) + "->" + target.getCategoryCode() + ")");
                categorySynonymMapper.insert(syn);
            }
        }
        ActiveLearningSampleEntity sample = new ActiveLearningSampleEntity();
        sample.setSampleType("CORRECTION");
        sample.setEntityId(cleanedDataId);
        sample.setSourceText(cd.getFullDescription());
        sample.setSourceCategoryName(originalName);
        sample.setSourceCategoryCode(originalCode);
        sample.setTargetCategoryId(target.getId());
        sample.setTargetCategoryCode(target.getCategoryCode());
        sample.setTargetCategoryName(target.getCategoryName());
        sample.setConfidence(cd.getMatchConfidence());
        sample.setScore(score);
        sample.setReason("人工修正分类");
        sample.setStatus("pending");
        activeLearningSampleMapper.insert(sample);
    }

    /** AI 检测结果 */
    private static class AiDetectResult {
        double score = 0;
        Boolean matched;
        String bestMatchCode;
        String bestMatchName;
        String reason;
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
                dataMap.put("categoryName", cleanedData.getCategoryName());
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

    private String findMappedValue(Map<String, FieldMappingAuditEntity> mappingByTarget, String standardColTitle,
                                    TempDataTitleEntity title, TempDataEntity tempData, ExtraDataEntity extraData,
                                    ExtraDataTitleEntity extraTitle) {
        FieldMappingAuditEntity mapping = mappingByTarget.get(standardColTitle);
        if (mapping == null) return null;
        String sourceField = mapping.getSourceField();
        if ("temp_data".equals(mapping.getSourceType()) && title != null && tempData != null) {
            for (int i = 1; i <= 10; i++) {
                if (sourceField.equals(title.getColTitle(i))) return tempData.getColData(i);
            }
        } else if ("extra_data".equals(mapping.getSourceType()) && extraData != null) {
            // extraTitle 已在 buildFillContext / fillResultData 中预加载一次，整个文件恒定，无需逐行查询
            if (extraTitle != null) {
                for (int i = 1; i <= 20; i++) {
                    if (sourceField.equals(extraTitle.getColTitle(i))) return extraData.getColData(i);
                }
            }
        }
        return null;
    }
}
