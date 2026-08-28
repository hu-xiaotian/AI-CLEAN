package com.aiclean.service.impl;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.DigestUtil;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.aiclean.entity.*;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.aiclean.entity.enums.DataStatus;
import com.aiclean.mapper.*;
import com.aiclean.ai.AiClientService;
import com.aiclean.ai.BatchExtractPrompt;
import com.aiclean.agent.ShardingAgent;
import com.aiclean.match.*;
import com.aiclean.model.ParseRule;
import com.aiclean.model.SearchCondition;
import com.aiclean.service.BatchClassificationService;
import com.aiclean.service.CategoryStandardLibrary;
import com.aiclean.service.SemanticCategoryLibrary;
import com.aiclean.service.DataCleaningService;
import com.aiclean.dto.CategoryDataCount;
import com.aiclean.dto.StatusCount;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;
import javax.annotation.PostConstruct;

import java.io.ByteArrayOutputStream;
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
    @Autowired private CategorySynonymMapper categorySynonymMapper;
    @Autowired private ActiveLearningSampleMapper activeLearningSampleMapper;
    @Autowired private CategoryMatcher categoryMatcher;
    @Autowired private ShardingAgent shardingAgent;
    @Autowired @Qualifier("cleaningExecutor") private ThreadPoolTaskExecutor cleaningExecutor;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private PlatformTransactionManager transactionManager;

    /** 独立事务模板：每条清洗结果写入后立即提交，避免后续阶段异常触发外层事务回滚时把已入库数据一起冲掉 */
    private TransactionTemplate requiresNewTemplate;

    @PostConstruct
    public void initTransactionTemplates() {
        DefaultTransactionDefinition def = new DefaultTransactionDefinition();
        def.setPropagationBehavior(Propagation.REQUIRES_NEW.value());
        requiresNewTemplate = new TransactionTemplate(transactionManager, def);
    }
    @Autowired private SimpMessagingTemplate messagingTemplate;
    @Autowired private AiClientService aiClientService;
    @Autowired private CategoryStandardLibrary stdLib;
    @Autowired private SemanticCategoryLibrary semanticLib;
    @Autowired private BatchClassificationService batchClassificationService;

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

    /** AI 属性提取跑批：单次请求交给大模型的数据条数 */
    @Value("${app.ai.extract-batch-size:20}")
    private int aiExtractBatchSize;

    /**
     * AI 属性提取跑批提示词模板加载器（提示词独立存放于 extract-batch-prompt.properties，支持外部覆盖）。
     * 占位符：{categoryCode} {categoryName} {fields} {records} {count}
     */
    @Autowired private BatchExtractPrompt batchExtractPrompt;

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

    /** 每个清洗任务的停止信号，key = titleId，供 stopCleaning() 外部中止异步任务 */
    private final ConcurrentHashMap<Long, AtomicBoolean> cleaningStopFlags = new ConcurrentHashMap<>();

    /** 上次推送清洗进度的时间戳（用于 WebSocket 节流，避免每行都推送） */
    private final ConcurrentHashMap<Long, Long> lastProgressPushTime = new ConcurrentHashMap<>();
    private static final long PROGRESS_THROTTLE_MS = 500; // 每 500ms 最多推送一次进度
    /** 两阶段进度权重：第一阶段（入库）占总进度前 45%，第二阶段（大模型分类）占后 55%，避免进度倒退 */
    private static final double PHASE1_WEIGHT = 0.45;

    // ==================== Excel导入 ====================

    @Override
    @Transactional
    public TempDataTitleEntity importExcel(MultipartFile file) {
        String originalFilename = file.getOriginalFilename();
        log.info("开始导入文件: {}", originalFilename);
        String ext = FileUtil.extName(originalFilename);
        boolean isCsv = "csv".equalsIgnoreCase(ext);
        try {
            String fileName = saveUploadFile(file);
            TempDataTitleEntity titleEntity = new TempDataTitleEntity();
            titleEntity.setFileName(file.getOriginalFilename());
            titleEntity.setUploadTime(LocalDateTime.now().toString());
            titleEntity.setStatus(DataStatus.DRAFT);
            titleEntity.setTotalRows(0);

            // CSV 由 POI 依据文件扩展名识别，需以带 .csv 后缀的文件打开；Excel 仍走输入流
            Workbook workbook = isCsv
                    ? WorkbookFactory.create(new java.io.File(fileName))
                    : WorkbookFactory.create(file.getInputStream());
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

                boolean rowHasData = false;
                for (int colIndex = 0; colIndex < colCount && colIndex < 10; colIndex++) {
                    Cell cell = dataRow.getCell(colIndex);
                    String cellValue = getCellValue(cell);
                    if (StrUtil.isNotBlank(cellValue)) rowHasData = true;
                    tempData.setColData(colIndex + 1, cellValue);
                }
                // 空行（所有列均为空）不保留
                if (!rowHasData) continue;

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

        // 2.5 删除主动学习样本 (active_learning_sample)
        // 必须在删除 cleaned_data/temp_data 之前执行，因其通过 entity_id -> cleaned_data -> temp_data 子查询定位。
        int sampleCount = activeLearningSampleMapper.deleteByTitleId(titleId);
        log.info("删除主动学习样本: {} 条", sampleCount);

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
    public String startAiExtract(Long titleId, String customName) {
        log.info("开始 AI 属性提取，表头ID: {}", titleId);
        doStartAiExtract(titleId, customName);
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
        // 已完成超过 5 分钟的条目自动清理，防止内存泄漏
        Object completedAt = progress.get("_completedAt");
        if (completedAt != null && System.currentTimeMillis() - (Long) completedAt > 5 * 60 * 1000) {
            aiExtractProgressMap.remove(titleId);
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
     * AI 属性提取核心逻辑（异步执行，跑批模式）：
     * 1. 逐行确定该行的分类编码（优先已清洗结果，其次"指定分类列"）；
     * 2. 从数据库 standard_title 查询该分类编码对应的标准分类字段（参数1），按分类编码分组；
     * 3. 同一分类下的原始数据（含物料名称/规格/牌号/技术标准/全描述等）按 batchSize 切成批次，
     *    一次请求把"多条原始数据 + 标准字段清单"整体交给大模型识别填充；
     * 4. 模型返回 JSON 数组（每个元素 {"index":n, "attrs":{...}}），解析后按行回填；
     * 5. 全过程的输入提示词与输出原文写入 txt 调试日志（logs/ai-prompt/attr-extract/）；
     * 6. 汇总所有属性键生成 extra_data_title 列，并写入 extra_data 行。
     */
    public void doStartAiExtract(Long titleId, String customName) {
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

        // 预加载：分类（编码/名称 -> 实体）、已清洗数据（按原始数据ID）
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

        final long extractStartTs = System.currentTimeMillis();
        sendAiExtractProgress(titleId, "start", 0, total, 0, 0, null);

        // ---------- 步骤1：按分类编码分组（同一分类的标准字段一致，可合并跑批） ----------
        Map<String, List<Integer>> rowsByCategory = new LinkedHashMap<>();
        List<String> rowCategoryCode = new ArrayList<>(Collections.nCopies(total, null));
        int noCategoryCount = 0;
        for (int i = 0; i < total; i++) {
            String code = resolveCategoryCode(tempDataList.get(i), categoryColIndex,
                    cleanedByTempId, catByCode, catByName);
            rowCategoryCode.set(i, code);
            if (code == null) {
                noCategoryCount++;
                continue;
            }
            rowsByCategory.computeIfAbsent(code, k -> new ArrayList<>()).add(i);
        }

        // ---------- 步骤2：查询各分类的标准字段（数据库 standard_title） ----------
        Map<String, List<String>> fieldsByCategory = new LinkedHashMap<>();
        for (String code : rowsByCategory.keySet()) {
            StandardTitleEntity stdTitle = standardTitleMapper.selectByCategoryCode(code);
            if (stdTitle == null) continue;
            List<String> fields = new ArrayList<>();
            for (int c = 1; c <= 20; c++) {
                String t = stdTitle.getColTitle(c);
                if (StrUtil.isNotBlank(t)) fields.add(t);
            }
            if (!fields.isEmpty()) fieldsByCategory.put(code, fields);
        }

        // ---------- 步骤3~4：按分类 + 批次调用大模型 ----------
        int perBatch = aiExtractBatchSize > 0 ? aiExtractBatchSize : 20;
        List<Map<String, String>> allParsed = new ArrayList<>();
        for (int i = 0; i < total; i++) allParsed.add(new LinkedHashMap<>());
        Set<String> allKeys = new LinkedHashSet<>();
        int success = 0;
        int error = 0;
        int processed = 0;
        int batchNo = 0;

        for (Map.Entry<String, List<Integer>> entry : rowsByCategory.entrySet()) {
            String categoryCode = entry.getKey();
            List<Integer> rowIdx = entry.getValue();
            List<String> fields = fieldsByCategory.get(categoryCode);
            CategoryEntity cat = catByCode.get(categoryCode);
            String categoryName = cat != null ? cat.getCategoryName() : "";

            if (fields == null) {
                // 该分类没有标准字段，整组跳过（仍推进进度）
                processed += rowIdx.size();
                sendAiExtractProgress(titleId, "progress", processed, total, success, error, null);
                continue;
            }

            int totalBatch = (rowIdx.size() + perBatch - 1) / perBatch;
            for (int b = 0; b < totalBatch; b++) {
                int from = b * perBatch;
                int to = Math.min(from + perBatch, rowIdx.size());
                List<Integer> batchRows = rowIdx.subList(from, to);
                batchNo++;

                // 组装批次输入：每条数据带上序号与原始信息
                List<Map<String, String>> records = new ArrayList<>();
                for (int k = 0; k < batchRows.size(); k++) {
                    TempDataEntity td = tempDataList.get(batchRows.get(k));
                    records.add(buildExtractRecord(k + 1, td, titleEntity, fullDescIndex,
                            cleanedByTempId.get(td.getId())));
                }

                String systemPrompt = buildAiSystemPrompt();
                String userPrompt = buildAiBatchUserPrompt(categoryCode, categoryName, fields, records);
                String batchLabel = "分类 " + categoryCode + "(" + categoryName + ") 批次 "
                        + (b + 1) + "/" + totalBatch + "（" + batchRows.size() + " 条）";

                String aiText = null;
                try {
                    aiText = aiClientService.chat(systemPrompt, userPrompt);
                } catch (Exception e) {
                    log.error("AI 属性提取批次失败，{}", batchLabel, e);
                }

                if (aiText == null) {
                    error += batchRows.size();
                    processed += batchRows.size();
                    sendAiExtractProgress(titleId, "progress", processed, total, success, error, null);
                    continue;
                }

                // 解析批次返回：index -> 属性 Map
                Map<Integer, Map<String, String>> batchResult = parseAiBatchJson(aiText, fields, batchRows.size());
                for (int k = 0; k < batchRows.size(); k++) {
                    int globalIdx = batchRows.get(k);
                    Map<String, String> attrs = batchResult.get(k + 1);
                    if (attrs != null && !attrs.isEmpty()) {
                        allParsed.set(globalIdx, attrs);
                        allKeys.addAll(attrs.keySet());
                        success++;
                    } else {
                        error++;
                    }
                }

                processed += batchRows.size();
                sendAiExtractProgress(titleId, "progress", processed, total, success, error, null);
            }
        }

        // 无分类的行也计入已处理
        if (processed < total) {
            processed = total;
            sendAiExtractProgress(titleId, "progress", processed, total, success, error, null);
        }

        // 汇总列（最多 20 个）
        List<String> keyList = new ArrayList<>(allKeys);
        if (keyList.size() > 20) keyList = keyList.subList(0, 20);

        if (keyList.isEmpty()) {
            String msg = "未提取到任何属性，请确认：文件已设置分类列或已执行智能分类，且对应分类编码在标准字段表头中存在，且属性拆分列有内容";
            sendAiExtractProgress(titleId, "complete", total, total, success, error, msg);
            log.warn("AI 提取未产生任何属性，文件 {}", titleId);
            return;
        }

        // ---------- 步骤6：覆盖旧结果后入库（独立事务） ----------
        // 重复提取：先删除该文件已有的全部属性提取结果（extra_data + extra_data_title），再重新写入
        extraDataMapper.deleteByTempDataTitleId(titleId);
        extraDataTitleMapper.deleteByTempDataTitleId(titleId);

        final List<String> columnKeys = keyList;
        final ExtraDataTitleEntity[] extraTitleHolder = new ExtraDataTitleEntity[1];
        transactionTemplate.executeWithoutResult(status -> {
            ExtraDataTitleEntity extraTitle = new ExtraDataTitleEntity();
            extraTitleHolder[0] = extraTitle;
            extraTitle.setTempDataTitleId(titleId);
            extraTitle.setParseRuleId(null);
            extraTitle.setIsAiExtract(true);
            extraTitle.setCustomName(null);
            extraTitle.setExtractStatus("RUNNING");
            extraTitle.setExtractStartTime(new java.util.Date(extractStartTs));
            extraTitle.setRowCount(total);
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

        String summary = "属性数 " + keyList.size() + "，成功 " + success + " 行，失败/未提取 " + error
                + " 行，批次数 " + batchNo + "，涉及分类 " + rowsByCategory.size() + " 个";

        // 回填提取元数据（结束时间 / 耗时 / 状态）
        ExtraDataTitleEntity savedTitle = extraTitleHolder[0];
        if (savedTitle != null && savedTitle.getId() != null) {
            savedTitle.setExtractEndTime(new java.util.Date());
            savedTitle.setExtractCostMs(System.currentTimeMillis() - extractStartTs);
            savedTitle.setExtractStatus(error > 0 && success == 0 ? "FAILED"
                    : (error > 0 ? "PARTIAL" : "SUCCESS"));
            extraDataTitleMapper.updateById(savedTitle);
        }

        sendAiExtractProgress(titleId, "complete", total, total, success, error,
                "AI 属性提取完成，共提取 " + keyList.size() + " 个属性");
        log.info("AI 属性提取完成，文件 {}，{}", titleId, summary);
    }

    /**
     * 组装单条待提取记录：把原始数据的关键列拼成"字段名=值"的形式，便于大模型理解。
     */
    private Map<String, String> buildExtractRecord(int seq, TempDataEntity td, TempDataTitleEntity title,
                                                   int fullDescIndex, CleanedDataEntity cleaned) {
        Map<String, String> rec = new LinkedHashMap<>();
        rec.put("序号", String.valueOf(seq));
        // 原始数据全部有值的列（列名取自表头），让模型拿到完整上下文
        for (int c = 1; c <= 10; c++) {
            String colName = title.getColTitle(c);
            String val = td.getColData(c);
            if (StrUtil.isNotBlank(colName) && StrUtil.isNotBlank(val)) {
                rec.put(colName, val.trim());
            }
        }
        // 显式补充全描述（属性拆分列），确保即使列名为空也能带上
        if (fullDescIndex > 0) {
            String fullDesc = td.getColData(fullDescIndex);
            if (StrUtil.isNotBlank(fullDesc)) rec.put("全描述", fullDesc.trim());
        }
        // 已清洗结果里的规范化信息（若有）可作为补充线索
        if (cleaned != null) {
            if (StrUtil.isNotBlank(cleaned.getMaterialName())) rec.putIfAbsent("物料名称", cleaned.getMaterialName());
            if (StrUtil.isNotBlank(cleaned.getSpecification())) rec.putIfAbsent("规格", cleaned.getSpecification());
        }
        return rec;
    }

    /**
     * 构造跑批用户提示词：标准字段清单 + 多条原始数据，要求模型返回 JSON 数组。
     */
    private String buildAiBatchUserPrompt(String categoryCode, String categoryName,
                                          List<String> fields, List<Map<String, String>> records) {
        StringBuilder fieldSb = new StringBuilder();
        for (int i = 0; i < fields.size(); i++) {
            fieldSb.append(i + 1).append(". ").append(fields.get(i)).append('\n');
        }
        StringBuilder recSb = new StringBuilder();
        for (Map<String, String> rec : records) {
            recSb.append("【序号 ").append(rec.get("序号")).append("】");
            boolean first = true;
            for (Map.Entry<String, String> e : rec.entrySet()) {
                if ("序号".equals(e.getKey())) continue;
                if (!first) recSb.append(" | ");
                recSb.append(e.getKey()).append('=').append(e.getValue());
                first = false;
            }
            recSb.append('\n');
        }
        String template = batchExtractPrompt.getOrDefault("extract.user-prompt", null);
        if (StrUtil.isBlank(template)) {
            template = "分类编码：{categoryCode}（{categoryName}）\n\n"
                    + "【标准分类字段（目标字段，严格使用以下名称作为 JSON 键）】\n{fields}\n\n"
                    + "【待识别的原始数据（共 {count} 条）】\n{records}\n\n"
                    + "【要求】\n"
                    + "1. 针对每一条原始数据，从其内容中识别并填充上述标准字段的值；\n"
                    + "2. 字段名必须严格使用给定名称，不要增加、删除或改写；\n"
                    + "3. 某字段在该条数据中无法确定时，值填空字符串 \"\"；\n"
                    + "4. 只输出一个 JSON 数组，元素个数与数据条数一致，格式为：\n"
                    + "[{\"index\":1,\"attrs\":{\"字段名\":\"值\"}},{\"index\":2,\"attrs\":{...}}]\n"
                    + "5. 不要输出解释文字，不要输出 Markdown 代码块。";
        }
        return template
                .replace("{categoryCode}", categoryCode == null ? "" : categoryCode)
                .replace("{categoryName}", categoryName == null ? "" : categoryName)
                .replace("{fields}", fieldSb.toString())
                .replace("{records}", recSb.toString())
                .replace("{count}", String.valueOf(records.size()));
    }

    /**
     * 解析批次返回的 JSON 数组，得到 序号(1-based) -> 属性 Map。
     * 兼容模型返回 Markdown 包裹、对象包裹数组、纯对象数组等情况。
     */
    private Map<Integer, Map<String, String>> parseAiBatchJson(String aiText, List<String> fields, int expectSize) {
        Map<Integer, Map<String, String>> result = new LinkedHashMap<>();
        if (StrUtil.isBlank(aiText)) return result;
        String text = stripCodeFence(aiText);

        // 截取数组主体；若模型返回 {"results":[...]} 则先取出数组
        int as = text.indexOf('[');
        int ae = text.lastIndexOf(']');
        if (as < 0 || ae <= as) {
            // 退化情况：只返回了单个对象
            Map<String, String> single = parseAiJson(text, fields);
            if (!single.isEmpty() && expectSize == 1) result.put(1, single);
            return result;
        }
        String arrText = text.substring(as, ae + 1);

        try {
            com.alibaba.fastjson2.JSONArray arr = JSON.parseArray(arrText);
            for (int i = 0; i < arr.size(); i++) {
                JSONObject item = arr.getJSONObject(i);
                if (item == null) continue;
                Integer index = item.getInteger("index");
                if (index == null) index = item.getInteger("序号");
                if (index == null) index = i + 1;
                JSONObject attrs = item.getJSONObject("attrs");
                if (attrs == null) attrs = item.getJSONObject("属性");
                if (attrs == null) {
                    // 模型可能直接把属性平铺在元素里
                    attrs = new JSONObject(item);
                    attrs.remove("index");
                    attrs.remove("序号");
                }
                Map<String, String> mapped = mapToStandardFields(attrs, fields);
                if (!mapped.isEmpty()) result.put(index, mapped);
            }
        } catch (Exception e) {
            log.warn("AI 批次返回解析失败，原文前 500 字: {}",
                    arrText.length() > 500 ? arrText.substring(0, 500) : arrText);
        }
        return result;
    }

    /** 把模型返回的键映射到标准字段名（精确 -> 归一化 -> 包含关系模糊匹配） */
    private Map<String, String> mapToStandardFields(JSONObject obj, List<String> fields) {
        Map<String, String> result = new LinkedHashMap<>();
        if (obj == null) return result;
        Map<String, String> fieldNorm = new HashMap<>();
        for (String f : fields) fieldNorm.put(normalize(f), f);
        for (String key : obj.keySet()) {
            String val = obj.getString(key);
            if (val == null) val = "";
            String stdField = fieldNorm.get(normalize(key));
            if (stdField == null) {
                String nk = normalize(key);
                for (String f : fields) {
                    String nf = normalize(f);
                    if (nf.equals(nk) || nf.contains(nk) || nk.contains(nf)) {
                        stdField = f;
                        break;
                    }
                }
            }
            if (stdField != null) result.put(stdField, val);
        }
        return result;
    }

    /** 去掉 ```json ... ``` 代码块包裹 */
    private String stripCodeFence(String text) {
        String t = text.trim();
        if (t.startsWith("```")) {
            int firstNL = t.indexOf('\n');
            if (firstNL >= 0) t = t.substring(firstNL + 1);
            int lastFence = t.lastIndexOf("```");
            if (lastFence >= 0) t = t.substring(0, lastFence);
            t = t.trim();
        }
        return t;
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
        return batchExtractPrompt.getOrDefault("extract.system-prompt", aiSystemPrompt);
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
        // 记录完成时间，供 getAiExtractProgress 自动清理过期条目（防止内存泄漏）
        if ("complete".equals(type) || "error".equals(type)) {
            msg.put("_completedAt", System.currentTimeMillis());
        }
        aiExtractProgressMap.put(titleId, msg);
        try {
            messagingTemplate.convertAndSend("/topic/ai-extract/" + titleId, JSON.toJSONString(msg));
        } catch (Exception e) {
            log.warn("WebSocket 推送 AI 提取进度失败: {}", e.getMessage());
        }
    }

    // ==================== 分类匹配与清洗 ====================

    @Override
    public CleanedDataEntity matchAndClean(Long tempDataId, Long extraDataTitleId, Long parseRuleId) {
        TempDataEntity tempData = tempDataMapper.selectById(tempDataId);
        if (tempData == null) throw new RuntimeException("原始数据不存在: " + tempDataId);

        TempDataTitleEntity titleEntity = tempDataTitleMapper.selectById(tempData.getTempDataTitleId());
        ParseRuleEntity ruleEntity = parseRuleMapper.selectById(parseRuleId);
        ParseRule parseRule = ruleEntity != null ? ruleEntity.toParseRule() : getDefaultParseRule();

        // 计算阶段：纯内存计算 + 可能的 AI 网络调用，必须置于事务之外，
        // 否则 AI 长耗时（最长约 120s/条）会让数据库连接与行锁被长时间霸占，引发 Lock wait timeout。
        CleanedDataEntity cleanedData = matchAndCleanPrepare(tempData, extraDataTitleId, parseRule,
                titleEntity, null, null, null, true);

        // 持久化阶段：在短事务内仅做 DB 写入，立即提交释放连接与行锁
        return transactionTemplate.execute(status -> {
            matchAndCleanPersist(cleanedData, tempData, false);
            return cleanedData;
        });
    }

    // ==================== 批量数据清洗 ====================

    @Override
    @Async
    public String startCleaning(Long titleId, Long parseRuleId, Integer batchSize) {
        log.info("开始数据清洗（固定AI分类），表头ID: {}, 规则ID: {}, batchSize: {}", titleId, parseRuleId, batchSize);
        // 任务入队：先把文件状态置为"排队等待中"并记录文件级清洗开始时间（独立提交），
        // 让智能分类页文件列表能立即看到排队状态；真正开始处理时再切为"清洗中"。
        try {
            TempDataTitleEntity title = tempDataTitleMapper.selectById(titleId);
            if (title != null) {
                title.setStatus(DataStatus.QUEUED);
                if (title.getCleanStartTime() == null) {
                    title.setCleanStartTime(java.time.LocalDateTime.now());
                }
                title.setCleanEndTime(null);
                tempDataTitleMapper.updateById(title);
            }
        } catch (Exception e) {
            log.warn("设置文件排队状态失败，titleId: {}", titleId, e);
        }
        doStartCleaning(titleId, parseRuleId, batchSize);
        return "cleaning_task_" + titleId;
    }

    /**
     * 实际的清洗执行逻辑，由 startCleaning 异步调用
     * 使用 TransactionTemplate 确保在异步线程中事务正确生效
     */
    public void doStartCleaning(Long titleId, Long parseRuleId, Integer batchSize) {
        // 0. 如果该批次已清洗过，先清理旧数据，确保重新生成。
        // 注意：清理必须在并行清洗之前、且在独立事务（自动提交）中提交，
        // 否则外层事务会持有 cleaned_data 的间隙锁(gap lock)，与并行 Worker 的 INSERT 相互阻塞，
        // 导致 "Lock wait timeout exceeded"。因此此处放在外层 transactionTemplate 之前执行。
        cleanPreviousCleaningData(titleId);

        // 真正开始处理：把文件状态由"排队等待中"切为"清洗中"并立即提交（独立自动提交，不在外层事务内），
        // 使"智能分类"页文件列表在长时间 AI 清洗期间能即时看到进行中状态（否则会一直显示排队直到整批结束）。
        TempDataTitleEntity titleForStatus = tempDataTitleMapper.selectById(titleId);
        if (titleForStatus != null) {
            titleForStatus.setStatus(DataStatus.PROCESSING);
            if (titleForStatus.getCleanStartTime() == null) {
                titleForStatus.setCleanStartTime(java.time.LocalDateTime.now());
            }
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
                // 使用类级别 stopFlag，使 stopCleaning() 可从外部中止本异步任务
                AtomicBoolean stopped = cleaningStopFlags.computeIfAbsent(titleId, k -> new AtomicBoolean(false));
                stopped.set(false); // 重置，防止上次残留

                sendCleaningProgress(titleId, "start", 0, totalCount, null, 0, 0);
                // 记录整批清洗的开始时间，供结果表展示
                java.time.LocalDateTime cleanStartTime = java.time.LocalDateTime.now();

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
                            for (TempDataEntity td : batch) {
                                if (stopped.get()) break;
                                try {
                                    // Phase 1（事务外）：纯计算（解析/字段提取/去重），不调用大模型；
                                    // 分类与评分统一延迟到第二阶段（2.3）一次性批次 AI 分类，避免每条调一次大模型导致极慢。
                                    // 固定 AI 分类：本阶段仅占位，最终分类由向量召回 top-k + 大模型给出。
                                    CleanedDataEntity cleaned = matchAndCleanPrepare(td, null, parseRule,
                                            titleEntity, allCategories, synonyms, seenHashes, true);
                                    // Phase 2（独立短事务 REQUIRES_NEW）：仅做 DB 写入并立即提交，快速释放连接与行锁；
                                    // 使用 REQUIRES_NEW 确保即使后续 AI 分类阶段异常触发外层事务回滚，已入库的清洗结果也不会被冲掉。
                                    requiresNewTemplate.executeWithoutResult(s -> matchAndCleanPersist(cleaned, td, true));
                                    allCleaned.add(cleaned);
                                    allScores.add(cleaned.getQualityScore() != null ? cleaned.getQualityScore() : 0.0);
                                    int cur = successCount.incrementAndGet();
                                    // WebSocket 节流：每 500ms 最多推送一次，最后一条必推
                                    // 第一阶段（入库）仅占总进度的前 PHASE1_WEIGHT（45%），避免与后续 AI 分类阶段进度叠加导致倒退
                                    long now = System.currentTimeMillis();
                                    Long lastPush = lastProgressPushTime.get(titleId);
                                    if (lastPush == null || now - lastPush >= PROGRESS_THROTTLE_MS || cur == totalCount) {
                                        sendCleaningProgress(titleId, "progress", (int) (totalCount * PHASE1_WEIGHT * cur / totalCount), totalCount, null, cur, errorCount.get());
                                        lastProgressPushTime.put(titleId, now);
                                    }
                                } catch (Exception e) {
                                    localErr.incrementAndGet();
                                    int cur = errorCount.incrementAndGet();
                                    log.error("并行清洗失败，tempDataId: {}", td.getId(), e);
                                    sendCleaningProgress(titleId, "progress", (int) (totalCount * PHASE1_WEIGHT * (successCount.get() + cur) / totalCount), totalCount, null, successCount.get(), cur);
                                    if (localErr.get() >= 5) {
                                        stopped.set(true);
                                        log.error("分片内连续失败 {} 次，停止后续清洗。已成功: {}, 失败: {}", localErr.get(), successCount.get(), errorCount.get());
                                        break;
                                    }
                                }
                            }
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
                sendCleaningProgress(titleId, "progress", (int) (totalCount * PHASE1_WEIGHT), totalCount, null, successCount.get(), errorCount.get());

                // 2.3 一次性批次分类（固定 AI 分类）：
                // 并行分片阶段已解析/提取字段并入库（分类留空占位），此处把全部已入库数据按批次一次性交给大模型
                // 重评分并回写分类与评分（updateStatus=false，状态仍由下一阶段阈值自适应统一打标）。
                // 相比原"每条调用一次大模型"，AI 调用次数由 N 降为 N/batchSize，大幅提升清洗速度。
                if (aiClientService.isEnabled() && !allCleaned.isEmpty()) {
                    // 入库阶段占总进度前 45%；进入大模型分类阶段时先把进度推进到 45%，
                    // 后续由批次分类回调把进度从 45% 平滑推进到 100%，两阶段权重不重叠、不倒退。
                    sendCleaningProgress(titleId, "progress", (int) (totalCount * PHASE1_WEIGHT), totalCount, null, successCount.get(), errorCount.get());
                    // 阶段内进度 phase∈[0,1] → 整体进度 = 45% + phase*55%
                    final double phase2Base = PHASE1_WEIGHT;
                    final double phase2Span = 1.0 - PHASE1_WEIGHT;
                    List<CleanedDataEntity> aiUpdated = batchClassificationService.batchClassifyEntities(
                            titleId, batchSize, false,
                            phase -> {
                                int cur = (int) (totalCount * (phase2Base + phase2Span * phase));
                                sendCleaningProgress(titleId, "progress", cur, totalCount, null,
                                        successCount.get(), errorCount.get());
                            });
                    // 用批次分类后的最新评分刷新内存集合，供下一阶段统一打标
                    if (aiUpdated != null && !aiUpdated.isEmpty()) {
                        Map<Long, CleanedDataEntity> aiById = new HashMap<>();
                        for (CleanedDataEntity c : aiUpdated) if (c != null && c.getId() != null) aiById.put(c.getId(), c);
                        List<Double> refreshedScores = new ArrayList<>();
                        for (int i = 0; i < allCleaned.size(); i++) {
                            CleanedDataEntity cd = allCleaned.get(i);
                            CleanedDataEntity updated = aiById.get(cd.getId());
                            if (updated != null) {
                                // 把批次分类结果（分类字段/评分/AI理由）回填到内存实体
                                cd.setCategoryId(updated.getCategoryId());
                                cd.setCategoryCode(updated.getCategoryCode());
                                cd.setCategoryName(updated.getCategoryName());
                                cd.setCategoryLevel(updated.getCategoryLevel());
                                cd.setCategoryFullPath(updated.getCategoryFullPath());
                                cd.setQualityScore(updated.getQualityScore());
                                cd.setAccuracyScore(updated.getAccuracyScore());
                                cd.setAiReason(updated.getAiReason());
                            }
                            refreshedScores.add(cd.getQualityScore() != null ? cd.getQualityScore() : 0.0);
                        }
                        allScores.clear();
                        allScores.addAll(refreshedScores);
                    }
                    log.info("AI 一次性批次分类完成，表头ID: {}, 共 {} 条", titleId, allCleaned.size());
                }

                // 2.4 阈值自适应统一打标（初始清洗也启用自适应阈值）+ 建审核任务
                java.time.LocalDateTime cleanEndTime = java.time.LocalDateTime.now();
                double[] thr = resolveThresholds(allScores);
                double review = thr[0], export = thr[1];
                for (CleanedDataEntity cd : allCleaned) {
                    double score = cd.getQualityScore() != null ? cd.getQualityScore() : 0.0;
                    cd.setCleanStartTime(cleanStartTime);
                    cd.setCleanEndTime(cleanEndTime);
                    applyStatus(cd, score, review, export);
                    if (score < review) {
                        createReviewTask(cd, "质量评分过低: " + score);
                    }
                    cleanedDataMapper.updateById(cd);
                }

                // 2.5 低置信样本沉淀（主动学习）：固定 AI 分类下，把低分/未匹配样本沉淀为 LOW_CONFIDENCE
                if (aiClientService.isEnabled()) {
                    for (CleanedDataEntity cd : allCleaned) {
                        double score = cd.getQualityScore() != null ? cd.getQualityScore() : 0.0;
                        boolean matched = cd.getCategoryId() != null && !"UNMATCHED".equals(cd.getMatchSource());
                        if (score < review && !matched) {
                            persistLowConfidenceSample(cd, score);
                        }
                    }
                }

                // 更新表头状态：已完成，并记录文件级清洗结束时间
                titleEntity.setStatus(DataStatus.COMPLETED);
                titleEntity.setCleanEndTime(java.time.LocalDateTime.now());
                tempDataTitleMapper.updateById(titleEntity);

                // 发送完成消息
                sendCleaningProgress(titleId, "complete", totalCount, totalCount, null, successCount.get(), errorCount.get());

                // 清理停止信号和节流时间戳
                cleaningStopFlags.remove(titleId);
                lastProgressPushTime.remove(titleId);

                log.info("数据清洗完成，成功: {}, 失败: {}", successCount.get(), errorCount.get());
            } catch (Exception e) {
                log.error("数据清洗任务执行失败，表头ID: {}", titleId, e);
                // 推送错误消息
                sendCleaningProgress(titleId, "error", 0, 0, null, 0, 0);
                // 清理停止信号和节流时间戳
                cleaningStopFlags.remove(titleId);
                lastProgressPushTime.remove(titleId);
                try {
                    TempDataTitleEntity titleEntity = tempDataTitleMapper.selectById(titleId);
                    if (titleEntity != null) {
                        titleEntity.setStatus(DataStatus.REJECTED);
                        titleEntity.setCleanEndTime(java.time.LocalDateTime.now());
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
        // 删除主动学习样本须在 cleaned_data 之前（依赖 entity_id -> cleaned_data 子查询定位），避免重清洗后残留孤儿样本
        int sampleCount = activeLearningSampleMapper.deleteByTitleId(titleId);
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
     * 内部清洗方法（计算阶段）：接受预加载的缓存数据，避免循环内重复 DB 查询。
     * 注意：本方法【不写库】，且 AI 网络调用（computeQualityScore -> aiClientService.chat，最长约 120s/条）
     * 发生在此处，因此调用方必须在【事务之外】调用本方法，避免长事务长时间持有数据库连接与行锁，
     * 否则会与其他操作（删除导入文件、结果打标、worker 间）发生 Lock wait timeout。
     */
    private CleanedDataEntity matchAndCleanPrepare(TempDataEntity tempData, Long extraDataTitleId,
                                                    ParseRule parseRule, TempDataTitleEntity titleEntity,
                                                     List<CategoryEntity> allCategories,
                                                     List<CategorySynonymEntity> synonyms,
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

        // 分类匹配：采用「直接 AI 分类」模式，省略规则分类（categoryMatcher.match）与规则/AI 评分。
        // 本阶段仅做解析与字段提取，分类与评分延迟到第二阶段（2.3）一次性批次分类：
        //   用整条原始数据（全部属性）做向量库语义召回 top-3 候选，连同原始数据提交大模型，
        //   由大模型直接给出分类结果 + 分类原因，再回写入库。
        // matchSource/matchConfidence 占位为 UNMATCHED，待批次分类回写真实分类。
        CategoryMatchOutcome matchResult = new CategoryMatchOutcome(null, "UNMATCHED", 0.0);
        CategoryEntity matchedCategory = null;

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

        // 质量评分占位：本阶段不做规则/AI 评分，分类与评分由第二阶段批次分类统一给出。
        // 仅以字段完整度作为临时质量分占位，供第二阶段阈值自适应参考（最终会被 AI 评分覆盖）。
        cleanedData.setCompletenessScore(cleanedData.calculateCompleteness());
        double qualityScore = calculateQuality(cleanedData);
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

        // 分类未命中：本阶段不赋分类，标记为待审核（最终由批次分类回写真实分类，若仍无候选则保持待审核）
        cleanedData.setStatus(DataStatus.NEEDS_REVIEW);

        return cleanedData;
    }

    /**
     * 内部清洗方法（持久化阶段）：在调用方提供的事务内执行 DB 写入并立即提交，释放连接与行锁。
     * 本方法【不包含任何网络调用】，因此事务足够短，不会引发锁等待。
     */
    private void matchAndCleanPersist(CleanedDataEntity cleanedData, TempDataEntity tempData, boolean deferStatus) {
        cleanedDataMapper.insert(cleanedData);

        // 审核任务延迟至第二阶段（仅当质量分低于自适应阈值时创建），避免延迟模式下重复创建
        if (!deferStatus && cleanedData.getQualityScore() != null && cleanedData.getQualityScore() < thresholdReview) {
            createReviewTask(cleanedData, "质量评分过低: " + cleanedData.getQualityScore());
        }

        // 更新原始数据状态
        tempData.setStatus(DataStatus.PROCESSED);
        tempDataMapper.updateById(tempData);
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
        result.put("cleanStartTime", title.getCleanStartTime());
        result.put("cleanEndTime", title.getCleanEndTime());
        // 耗时（毫秒）：开始时间已记录且未结束时按当前时间计算，便于文件列表实时展示
        if (title.getCleanStartTime() != null) {
            LocalDateTime end = title.getCleanEndTime() != null ? title.getCleanEndTime() : LocalDateTime.now();
            result.put("durationMs", java.time.Duration.between(title.getCleanStartTime(), end).toMillis());
        } else {
            result.put("durationMs", null);
        }
        return result;
    }

    @Override
    public void stopCleaning(Long titleId) {
        log.info("停止清洗任务，表头ID: {}", titleId);
        // 1. 发送停止信号给正在运行的异步任务
        AtomicBoolean flag = cleaningStopFlags.get(titleId);
        if (flag != null) {
            flag.set(true);
            log.info("已发送停止信号，titleId: {}", titleId);
        }
        // 2. 更新表头状态
        TempDataTitleEntity title = tempDataTitleMapper.selectById(titleId);
        if (title != null && (DataStatus.PROCESSING == title.getStatus()
                || DataStatus.QUEUED == title.getStatus()
                || DataStatus.needsReview(title.getStatus()))) {
            title.setStatus(DataStatus.REJECTED);
            title.setCleanEndTime(LocalDateTime.now());
            tempDataTitleMapper.updateById(title);
        }
        // 3. 通知前端
        sendCleaningProgress(titleId, "stopped", 0, 0, null, 0, 0);
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

    // ==================== 智能分类人工修正 ====================

    @Override
    @Transactional
    public CleanedDataEntity updateCleanedDataCategory(Long id, String categoryCode, String categoryName, String remark) {
        CleanedDataEntity cd = cleanedDataMapper.selectById(id);
        if (cd == null) throw new RuntimeException("清洗数据不存在: " + id);
        boolean changed = false;
        if (StrUtil.isNotBlank(categoryCode) || StrUtil.isNotBlank(categoryName)) {
            // 用人工选择的编码/名称重新匹配标准库三级
            CategoryEntity target = stdLib.resolveWithGrade(cd, categoryCode, categoryName).getCategory();
            if (target != null) {
                cd.setCategoryId(target.getId());
                cd.setCategoryCode(target.getCategoryCode());
                cd.setCategoryName(target.getCategoryName());
                cd.setCategoryLevel(target.getLevel());
                cd.setCategoryFullPath(target.getFullPath());
                cd.setMatchSource("MANUAL");
                cd.setMatchConfidence(1.0);
                cd.setQualityScore(100.0);
                cd.setAccuracyScore(100.0);
                cd.setAiReason("人工修正分类：" + target.getCategoryCode() + " " + target.getCategoryName());
                changed = true;
            }
        }
        if (StrUtil.isNotBlank(remark)) {
            cd.setReviewComment(remark);
            changed = true;
        }
        if (changed) {
            cd.setStatus(DataStatus.MODIFIED);
            cd.setReviewedBy(StrUtil.isNotBlank(cd.getReviewedBy()) ? cd.getReviewedBy() : "admin");
            cd.setReviewedAt(LocalDateTime.now());
        }
        cleanedDataMapper.updateById(cd);
        return cd;
    }

    @Override
    public List<Map<String, Object>> searchCategories(String keyword, int limit) {
        int k = limit > 0 ? Math.min(limit, 50) : 20;
        List<Map<String, Object>> out = new ArrayList<>();
        if (StrUtil.isBlank(keyword)) return out;
        // 复用标准库关键词检索（编码/名称/分词），仅返回三级分类
        List<CategoryEntity> cats = stdLib.searchByKeyword(keyword, k);
        for (CategoryEntity cat : cats) {
            if (cat == null || cat.getLevel() == null || cat.getLevel() != 3) continue;
            Map<String, Object> m = new HashMap<>();
            m.put("categoryCode", cat.getCategoryCode());
            m.put("categoryName", cat.getCategoryName());
            m.put("categoryFullPath", cat.getFullPath());
            m.put("level", cat.getLevel());
            out.add(m);
        }
        return out;
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

    /**
     * 按层级返回属性提取结果：文件 -> 分类 -> 属性列表。
     * <p>
     * extraDataTitleId 为空时，取该文件最近一次的提取结果表头。
     *
     * @param tempDataTitleId  原始数据文件表头ID（文件层）
     * @param extraDataTitleId 指定的提取结果表头ID，可为空
     * @return {file:{...}, extraTitle:{...}, columns:[...], categories:[{categoryCode, categoryName, rowCount,
     *         attributes:[{name, filledCount, fillRate, samples:[...]}], rows:[{tempDataId, materialName, attrs:{}}]}]}
     */
    @Override
    public Map<String, Object> getExtractResultTree(Long tempDataTitleId, Long extraDataTitleId) {
        Map<String, Object> result = new LinkedHashMap<>();

        TempDataTitleEntity fileTitle = tempDataTitleMapper.selectById(tempDataTitleId);
        if (fileTitle == null) {
            throw new RuntimeException("文件表头不存在: " + tempDataTitleId);
        }

        // ---------- 文件层 ----------
        Map<String, Object> fileInfo = new LinkedHashMap<>();
        fileInfo.put("tempDataTitleId", fileTitle.getId());
        fileInfo.put("fileName", fileTitle.getFileName());
        fileInfo.put("categoryCol", fileTitle.getCategoryCol());
        fileInfo.put("fullDescCol", fileTitle.getFullDescCol());
        result.put("file", fileInfo);

        // ---------- 定位提取结果表头 ----------
        List<ExtraDataTitleEntity> titles = extraDataTitleMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<ExtraDataTitleEntity>()
                        .eq("temp_data_title_id", tempDataTitleId)
                        .orderByDesc("id"));
        result.put("extraTitleOptions", titles.stream().map(t -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", t.getId());
            m.put("customName", t.getCustomName());
            m.put("isAiExtract", t.getIsAiExtract());
            return m;
        }).collect(java.util.stream.Collectors.toList()));

        ExtraDataTitleEntity extraTitle = null;
        if (extraDataTitleId != null) {
            extraTitle = extraDataTitleMapper.selectById(extraDataTitleId);
        } else if (!titles.isEmpty()) {
            extraTitle = titles.get(0);
        }
        if (extraTitle == null) {
            result.put("extraTitle", null);
            result.put("columns", Collections.emptyList());
            result.put("categories", Collections.emptyList());
            return result;
        }
        result.put("extraTitle", extraTitle);

        // 属性列名清单
        List<String> columns = new ArrayList<>();
        for (int c = 1; c <= 20; c++) {
            String t = extraTitle.getColTitle(c);
            if (StrUtil.isNotBlank(t)) columns.add(t);
        }
        result.put("columns", columns);

        // ---------- 数据装载 ----------
        List<ExtraDataEntity> extraDataList = extraDataMapper.selectByExtraDataTitleId(extraTitle.getId());
        Map<Long, ExtraDataEntity> extraByTempId = new HashMap<>();
        for (ExtraDataEntity ed : extraDataList) {
            extraByTempId.put(ed.getTempDataId(), ed);
        }

        List<TempDataEntity> tempDataList = tempDataMapper.selectByTitleId(tempDataTitleId);
        Map<Long, CleanedDataEntity> cleanedByTempId = new HashMap<>();
        for (CleanedDataEntity cd : cleanedDataMapper.selectAllByTempDataTitleId(tempDataTitleId)) {
            cleanedByTempId.put(cd.getTempDataId(), cd);
        }
        List<CategoryEntity> allCategories = categoryMapper.selectList(null);
        Map<String, CategoryEntity> catByCode = new HashMap<>();
        Map<String, CategoryEntity> catByName = new HashMap<>();
        for (CategoryEntity c : allCategories) {
            if (c.getCategoryCode() != null) catByCode.put(c.getCategoryCode(), c);
            if (c.getCategoryName() != null) catByName.put(c.getCategoryName(), c);
        }
        int categoryColIndex = findColIndex(fileTitle, fileTitle.getCategoryCol());

        // ---------- 分类层分组 ----------
        Map<String, List<Map<String, Object>>> rowsByCategory = new LinkedHashMap<>();
        for (TempDataEntity td : tempDataList) {
            ExtraDataEntity ed = extraByTempId.get(td.getId());
            if (ed == null) continue;
            CleanedDataEntity cleaned = cleanedByTempId.get(td.getId());
            String code = resolveCategoryCode(td, categoryColIndex, cleanedByTempId, catByCode, catByName);
            String key = code == null ? "__UNCLASSIFIED__" : code;

            Map<String, String> attrs = new LinkedHashMap<>();
            for (int j = 0; j < columns.size(); j++) {
                attrs.put(columns.get(j), ed.getColData(j + 1));
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("tempDataId", td.getId());
            row.put("extraDataId", ed.getId());
            row.put("materialCode", cleaned != null ? cleaned.getMaterialCode() : null);
            row.put("materialName", cleaned != null ? cleaned.getMaterialName() : td.getColData(1));
            row.put("attrs", attrs);
            // 源数据（原始各列），供前端查看明细与修改时使用
            List<String> sourceHeaders = new ArrayList<>();
            List<String> sourceData = new ArrayList<>();
            for (int c = 1; c <= 20; c++) {
                String h = fileTitle.getColTitle(c);
                if (h == null) continue;
                String v = td.getColData(c);
                if (StrUtil.isBlank(v)) continue;
                sourceHeaders.add(h);
                sourceData.add(v);
            }
            row.put("sourceHeaders", sourceHeaders);
            row.put("sourceData", sourceData);
            boolean rowUnclassified = "__UNCLASSIFIED__".equals(key);
            row.put("categoryCode", rowUnclassified ? null : code);
            row.put("categoryName", rowUnclassified ? "未分类" : (catByCode.get(code) != null ? catByCode.get(code).getCategoryName() : code));
            rowsByCategory.computeIfAbsent(key, k -> new ArrayList<>()).add(row);
        }

        // ---------- 组装：分类 -> 属性列表 ----------
        List<Map<String, Object>> categories = new ArrayList<>();
        for (Map.Entry<String, List<Map<String, Object>>> e : rowsByCategory.entrySet()) {
            String code = e.getKey();
            List<Map<String, Object>> rows = e.getValue();
            boolean unclassified = "__UNCLASSIFIED__".equals(code);
            CategoryEntity cat = unclassified ? null : catByCode.get(code);

            Map<String, Object> node = new LinkedHashMap<>();
            node.put("categoryCode", unclassified ? null : code);
            node.put("categoryName", unclassified ? "未分类" : (cat != null ? cat.getCategoryName() : code));
            node.put("rowCount", rows.size());

            // 该分类下的标准分类字段（来自 standard_title），用于对比"标准字段 vs 实际提取"
            List<String> stdFields = new ArrayList<>();
            if (!unclassified) {
                StandardTitleEntity st = standardTitleMapper.selectByCategoryCode(code);
                if (st != null) {
                    for (int c = 1; c <= 20; c++) {
                        String t = st.getColTitle(c);
                        if (StrUtil.isNotBlank(t)) stdFields.add(t);
                    }
                }
            }
            node.put("standardFields", stdFields);

            // 属性层：每个属性的填充数、填充率、示例值
            List<Map<String, Object>> attributes = new ArrayList<>();
            for (String col : columns) {
                int filled = 0;
                List<String> samples = new ArrayList<>();
                for (Map<String, Object> row : rows) {
                    @SuppressWarnings("unchecked")
                    Map<String, String> attrs = (Map<String, String>) row.get("attrs");
                    String v = attrs.get(col);
                    if (StrUtil.isNotBlank(v)) {
                        filled++;
                        if (samples.size() < 3 && !samples.contains(v)) samples.add(v);
                    }
                }
                if (filled == 0 && !stdFields.contains(col)) continue; // 与该分类无关的属性列不展示
                Map<String, Object> attr = new LinkedHashMap<>();
                attr.put("name", col);
                attr.put("isStandardField", stdFields.contains(col));
                attr.put("filledCount", filled);
                attr.put("fillRate", rows.isEmpty() ? 0
                        : Math.round(filled * 1000.0 / rows.size()) / 10.0);
                attr.put("samples", samples);
                attributes.add(attr);
            }
            node.put("attributes", attributes);
            node.put("rows", rows);
            categories.add(node);
        }
        // 未分类排在最后，其余按数据量倒序
        categories.sort((a, b) -> {
            boolean au = a.get("categoryCode") == null;
            boolean bu = b.get("categoryCode") == null;
            if (au != bu) return au ? 1 : -1;
            return Integer.compare((Integer) b.get("rowCount"), (Integer) a.get("rowCount"));
        });
        result.put("categories", categories);

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("totalRows", extraDataList.size());
        summary.put("categoryCount", categories.size());
        summary.put("columnCount", columns.size());
        result.put("summary", summary);
        return result;
    }

    @Override
    public Map<String, Object> getExtraRowDetail(Long extraDataId) {
        Map<String, Object> result = new LinkedHashMap<>();
        ExtraDataEntity ed = extraDataMapper.selectById(extraDataId);
        if (ed == null) {
            throw new RuntimeException("提取明细不存在: " + extraDataId + "（可能已被重新提取清理，请刷新后重试）");
        }
        ExtraDataTitleEntity extraTitle = extraDataTitleMapper.selectById(ed.getExtraDataTitleId());
        TempDataEntity td = tempDataMapper.selectById(ed.getTempDataId());
        TempDataTitleEntity fileTitle = td != null ? tempDataTitleMapper.selectById(td.getTempDataTitleId()) : null;
        CleanedDataEntity cleaned = cleanedDataMapper.selectByTempDataId(ed.getTempDataId());

        // 该明细所属分类（用于对齐层级查看的属性过滤）
        String rowCategoryCode = cleaned != null ? cleaned.getCategoryCode() : null;
        List<String> stdFields = new ArrayList<>();
        if (rowCategoryCode != null) {
            StandardTitleEntity st = standardTitleMapper.selectByCategoryCode(rowCategoryCode);
            if (st != null) {
                for (int c = 1; c <= 20; c++) {
                    String t = st.getColTitle(c);
                    if (StrUtil.isNotBlank(t)) stdFields.add(t);
                }
            }
        }

        // 提取属性列标题（仅展示该分类相关属性，与层级查看保持一致）
        List<String> columns = new ArrayList<>();
        if (extraTitle != null) {
            for (int c = 1; c <= 20; c++) {
                String t = extraTitle.getColTitle(c);
                if (StrUtil.isBlank(t)) continue;
                String v = ed.getColData(c);
                // 过滤规则与层级查看一致：属于标准字段，或本行该列已赋值
                if (stdFields.contains(t) || StrUtil.isNotBlank(v)) columns.add(t);
            }
        }
        // 提取属性值
        Map<String, String> attrs = new LinkedHashMap<>();
        for (int c = 1; c <= 20; c++) {
            String t = extraTitle != null ? extraTitle.getColTitle(c) : null;
            if (StrUtil.isBlank(t)) continue;
            String v = ed.getColData(c);
            if (stdFields.contains(t) || StrUtil.isNotBlank(v)) attrs.put(t, v);
        }
        // 源数据（原始列）
        List<String> sourceHeaders = new ArrayList<>();
        List<String> sourceData = new ArrayList<>();
        if (fileTitle != null && td != null) {
            for (int c = 1; c <= 20; c++) {
                String h = fileTitle.getColTitle(c);
                if (h == null) continue;
                String v = td.getColData(c);
                if (StrUtil.isBlank(v)) continue;
                sourceHeaders.add(h);
                sourceData.add(v);
            }
        }

        result.put("extraDataId", ed.getId());
        result.put("tempDataId", ed.getTempDataId());
        result.put("extraDataTitleId", ed.getExtraDataTitleId());
        result.put("materialCode", cleaned != null ? cleaned.getMaterialCode() : null);
        result.put("materialName", cleaned != null ? cleaned.getMaterialName() : (td != null ? td.getColData(1) : null));
        result.put("columns", columns);
        result.put("attrs", attrs);
        result.put("sourceHeaders", sourceHeaders);
        result.put("sourceData", sourceData);
        result.put("categoryCode", cleaned != null ? cleaned.getCategoryCode() : null);
        result.put("categoryName", cleaned != null ? cleaned.getCategoryName() : null);
        return result;
    }

    @Override
    public void updateExtraRow(Long extraDataId, Map<String, String> cols) {
        if (cols == null || cols.isEmpty()) return;
        ExtraDataEntity ed = extraDataMapper.selectById(extraDataId);
        if (ed == null) {
            throw new RuntimeException("提取明细不存在: " + extraDataId + "（可能已被重新提取清理，请刷新后重试）");
        }
        ExtraDataTitleEntity extraTitle = extraDataTitleMapper.selectById(ed.getExtraDataTitleId());
        if (extraTitle == null) {
            throw new RuntimeException("提取表头不存在: " + ed.getExtraDataTitleId());
        }
        // 仅更新传入的、且列标题匹配的字段
        for (int c = 1; c <= 20; c++) {
            String colTitle = extraTitle.getColTitle(c);
            if (StrUtil.isBlank(colTitle)) continue;
            if (cols.containsKey(colTitle)) {
                ed.setColData(c, cols.get(colTitle));
            }
        }
        extraDataMapper.updateById(ed);
    }

    @Override
    public List<Map<String, Object>> getAiExtractTaskList() {
        List<Map<String, Object>> result = new ArrayList<>();
        // 查询全部 AI 提取记录（按 id 倒序，最新在前）
        List<ExtraDataTitleEntity> titles = extraDataTitleMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<ExtraDataTitleEntity>()
                        .eq("is_ai_extract", true)
                        .orderByDesc("id"));
        if (titles == null) return result;

        for (ExtraDataTitleEntity t : titles) {
            Map<String, Object> m = new LinkedHashMap<>();
            Long tempDataTitleId = t.getTempDataTitleId();
            TempDataTitleEntity fileTitle = tempDataTitleId != null ? tempDataTitleMapper.selectById(tempDataTitleId) : null;
            String fileName = fileTitle != null ? fileTitle.getFileName() : ("数据#" + tempDataTitleId);

            // 行数：优先用记录的 rowCount，否则按 extra_data 计数
            int rowCount = t.getRowCount() != null ? t.getRowCount() : 0;

            m.put("extraDataTitleId", t.getId());
            m.put("tempDataTitleId", tempDataTitleId);
            m.put("fileName", fileName);
            m.put("rowCount", rowCount);
            m.put("extractStatus", t.getExtractStatus());
            m.put("extractStartTime", t.getExtractStartTime());
            m.put("extractEndTime", t.getExtractEndTime());
            m.put("extractCostMs", t.getExtractCostMs());
            result.add(m);
        }
        return result;
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
    }

    @Override
    public StandardTitleEntity getStandardTitleById(Long id) {
        return standardTitleMapper.selectById(id);
    }

    @Override
    public List<StandardTitleEntity> getAllStandardTitles() {
        List<StandardTitleEntity> titles = standardTitleMapper.selectList(null);
        applyCategoryNames(titles);
        return titles;
    }

    @Override
    public IPage<StandardTitleEntity> pageStandardTitles(long page, long size, String keyword, String sortOrder) {
        Page<StandardTitleEntity> pageReq = new Page<>(page, size);
        LambdaQueryWrapper<StandardTitleEntity> wrapper = new LambdaQueryWrapper<>();
        if (keyword != null && !keyword.trim().isEmpty()) {
            wrapper.like(StandardTitleEntity::getCategoryCode, keyword.trim());
        }
        if ("asc".equalsIgnoreCase(sortOrder)) {
            wrapper.orderByAsc(StandardTitleEntity::getId);
        } else {
            wrapper.orderByDesc(StandardTitleEntity::getId);
        }
        IPage<StandardTitleEntity> result = standardTitleMapper.selectPage(pageReq, wrapper);
        // 补全分类名称
        applyCategoryNames(result.getRecords());
        return result;
    }

    /**
     * 为一批标准字段表头补全分类名称。
     * 一次性加载全部分类到 Map，避免逐条 selectByCode 产生的 N+1 查询。
     */
    private void applyCategoryNames(List<StandardTitleEntity> titles) {
        if (titles == null || titles.isEmpty()) return;
        List<CategoryEntity> allCategories = categoryMapper.selectList(null);
        Map<String, String> catNameByCode = new HashMap<>();
        if (allCategories != null) {
            for (CategoryEntity c : allCategories) {
                if (StrUtil.isNotBlank(c.getCategoryCode()) && c.getCategoryName() != null) {
                    catNameByCode.put(c.getCategoryCode(), c.getCategoryName());
                }
            }
        }
        for (StandardTitleEntity title : titles) {
            if (StrUtil.isNotBlank(title.getCategoryCode())) {
                String name = catNameByCode.get(title.getCategoryCode());
                if (name != null) title.setCategoryName(name);
            }
        }
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

    /**
     * 原始数据优先按 tempDataId 回查 temp_data（列名取 temp_data_title 的 colNTitle），
     * 源行已删除或无 tempDataId 时回退使用提交快照 requestColumnsJson。
     */
    @Override
    public byte[] exportResultData(Long standardTitleId, int page, int pageSize) throws IOException {
        SearchCondition cond = new SearchCondition();
        cond.setStandardTitleId(standardTitleId);
        cond.setPage(page);
        cond.setPageSize(pageSize);
        List<ResultDataEntity> results = resultDataMapper.searchByConditions(cond);
        if (results == null || results.isEmpty()) {
            throw new IllegalStateException("没有结果数据可导出");
        }

        // 结果属性列顺序：取该标准表头实际配置的属性列（1~20）
        List<String> headers = new ArrayList<>();
        List<Integer> activeCols = new ArrayList<>();
        StandardTitleEntity st = standardTitleId != null ? standardTitleMapper.selectById(standardTitleId) : null;
        if (st != null) {
            for (int i = 1; i <= 20; i++) {
                String t = st.getColTitle(i);
                if (StrUtil.isNotBlank(t)) {
                    headers.add(t);
                    activeCols.add(i);
                }
            }
        }

        // 原始数据列
        List<String> rawColumnOrder = new ArrayList<>();
        Map<Long, Map<String, String>> rawDataByRow = new LinkedHashMap<>();
        loadResultRawData(results, rawDataByRow, rawColumnOrder);

        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            CellStyle headerStyle = buildResultExportHeaderStyle(workbook);
            CellStyle rawHeaderStyle = buildResultExportRawHeaderStyle(workbook);
            Sheet sheet = workbook.createSheet("结果数据");

            List<String> finalHeaders = new ArrayList<>();
            finalHeaders.add("行号");
            for (String rk : rawColumnOrder) {
                finalHeaders.add("原始-" + rk);
            }
            finalHeaders.addAll(headers);
            Row headerRow = sheet.createRow(0);
            for (int i = 0; i < finalHeaders.size(); i++) {
                Cell cell = headerRow.createCell(i);
                cell.setCellValue(finalHeaders.get(i));
                cell.setCellStyle(i >= 1 && i <= rawColumnOrder.size() ? rawHeaderStyle : headerStyle);
            }

            int r = 1;
            for (ResultDataEntity rd : results) {
                Row row = sheet.createRow(r++);
                row.createCell(0).setCellValue(rd.getId() == null ? "" : String.valueOf(rd.getId()));
                Map<String, String> raw = rawDataByRow.getOrDefault(rd.getId(), new LinkedHashMap<>());
                for (int idx = 0; idx < rawColumnOrder.size(); idx++) {
                    String v = raw.get(rawColumnOrder.get(idx));
                    row.createCell(1 + idx).setCellValue(v != null ? v : "");
                }
                int offset = 1 + rawColumnOrder.size();
                for (int idx = 0; idx < activeCols.size(); idx++) {
                    String v = rd.getColData(activeCols.get(idx));
                    row.createCell(offset + idx).setCellValue(v != null ? v : "");
                }
            }

            for (int i = 0; i < finalHeaders.size(); i++) {
                sheet.autoSizeColumn(i);
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            workbook.write(out);
            return out.toByteArray();
        }
    }

    /**
     * 加载结果数据对应的原始数据（按 tempDataId 回查 temp_data）。
     * 结果写入 rawDataByRow（rowIndex -> (原始列名 -> 值)）与 rawColumnOrder（全局列顺序）。
     */
    private void loadResultRawData(List<ResultDataEntity> results,
                                   Map<Long, Map<String, String>> rawDataByRow,
                                   List<String> rawColumnOrder) {
        if (results == null || results.isEmpty()) {
            return;
        }
        List<Long> tempDataIds = results.stream()
                .map(ResultDataEntity::getTempDataId)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());
        Map<Long, TempDataEntity> tempDataMap = new LinkedHashMap<>();
        if (!tempDataIds.isEmpty()) {
            try {
                List<TempDataEntity> tempRows = tempDataMapper.selectBatchIds(tempDataIds);
                if (tempRows != null) {
                    for (TempDataEntity td : tempRows) {
                        tempDataMap.put(td.getId(), td);
                    }
                }
            } catch (Exception e) {
                log.warn("导出时回查 temp_data 失败", e);
            }
        }

        TempDataTitleEntity title = null;
        if (!tempDataMap.isEmpty()) {
            Long titleId = tempDataMap.values().iterator().next().getTempDataTitleId();
            if (titleId != null) {
                try {
                    title = tempDataTitleMapper.selectById(titleId);
                } catch (Exception e) {
                    log.warn("导出时查询 temp_data_title 失败 titleId={}", titleId, e);
                }
            }
        }
        List<Integer> validColIndexes = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            String colTitle = title == null ? null : title.getColTitle(i);
            boolean hasTitle = StrUtil.isNotBlank(colTitle);
            boolean hasValue = false;
            if (!hasTitle) {
                for (TempDataEntity td : tempDataMap.values()) {
                    if (StrUtil.isNotBlank(td.getColData(i))) {
                        hasValue = true;
                        break;
                    }
                }
            }
            if (hasTitle || hasValue) {
                validColIndexes.add(i);
                String name = hasTitle ? colTitle.trim() : ("col" + i);
                if (!rawColumnOrder.contains(name)) {
                    rawColumnOrder.add(name);
                }
            }
        }

        for (ResultDataEntity rd : results) {
            Map<String, String> raw = new LinkedHashMap<>();
            TempDataEntity td = rd.getTempDataId() == null ? null : tempDataMap.get(rd.getTempDataId());
            if (td != null) {
                for (Integer idx : validColIndexes) {
                    String colTitle = title == null ? null : title.getColTitle(idx);
                    String name = StrUtil.isNotBlank(colTitle) ? colTitle.trim() : ("col" + idx);
                    String v = td.getColData(idx);
                    raw.put(name, v == null ? "" : v);
                }
            }
            rawDataByRow.put(rd.getId(), raw);
        }
    }

    /**
     * 构建结果数据导出的表头样式（加粗 + 灰底）
     */
    private CellStyle buildResultExportHeaderStyle(XSSFWorkbook workbook) {
        CellStyle style = workbook.createCellStyle();
        Font font = workbook.createFont();
        font.setBold(true);
        style.setFont(font);
        style.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        return style;
    }

    /**
     * 构建原始数据列表头样式（加粗 + 浅蓝底，与结果列区分，便于对比）
     */
    private CellStyle buildResultExportRawHeaderStyle(XSSFWorkbook workbook) {
        CellStyle style = workbook.createCellStyle();
        Font font = workbook.createFont();
        font.setBold(true);
        style.setFont(font);
        style.setFillForegroundColor(IndexedColors.PALE_BLUE.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        return style;
    }

    /**
     * 加载指定数据文件下所有结果数据对应的原始数据（按 tempDataId 回查 temp_data）。
     * 列名取 temp_data_title 的 colNTitle，源行已删除时回退使用提交快照 requestColumnsJson。
     * 结果写入 rawDataByRow（rowIndex -> (原始列名 -> 值)）与 rawColumnOrder（全局列顺序）。
     */
    private void loadResultRawDataByTitle(Long tempDataTitleId,
                                          Map<Long, Map<String, String>> rawDataByRow,
                                          List<String> rawColumnOrder) {
        SearchCondition cond = new SearchCondition();
        cond.setTempDataTitleId(tempDataTitleId);
        cond.setQueryAll(true);
        List<ResultDataEntity> allResults = resultDataMapper.searchByConditions(cond);
        if (allResults == null || allResults.isEmpty()) {
            return;
        }

        List<Long> tempDataIds = allResults.stream()
                .map(ResultDataEntity::getTempDataId)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());
        Map<Long, TempDataEntity> tempDataMap = new LinkedHashMap<>();
        if (!tempDataIds.isEmpty()) {
            try {
                List<TempDataEntity> tempRows = tempDataMapper.selectBatchIds(tempDataIds);
                if (tempRows != null) {
                    for (TempDataEntity td : tempRows) {
                        tempDataMap.put(td.getId(), td);
                    }
                }
            } catch (Exception e) {
                log.warn("导出时回查 temp_data 失败 titleId={}", tempDataTitleId, e);
            }
        }

        TempDataTitleEntity title = null;
        if (!tempDataMap.isEmpty()) {
            Long titleId = tempDataMap.values().iterator().next().getTempDataTitleId();
            if (titleId != null) {
                try {
                    title = tempDataTitleMapper.selectById(titleId);
                } catch (Exception e) {
                    log.warn("导出时查询 temp_data_title 失败 titleId={}", titleId, e);
                }
            }
        }
        List<Integer> validColIndexes = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            String colTitle = title == null ? null : title.getColTitle(i);
            boolean hasTitle = StrUtil.isNotBlank(colTitle);
            boolean hasValue = false;
            if (!hasTitle) {
                for (TempDataEntity td : tempDataMap.values()) {
                    if (StrUtil.isNotBlank(td.getColData(i))) {
                        hasValue = true;
                        break;
                    }
                }
            }
            if (hasTitle || hasValue) {
                validColIndexes.add(i);
                String name = hasTitle ? colTitle.trim() : ("col" + i);
                if (!rawColumnOrder.contains(name)) {
                    rawColumnOrder.add(name);
                }
            }
        }

        for (ResultDataEntity rd : allResults) {
            Map<String, String> raw = new LinkedHashMap<>();
            TempDataEntity td = rd.getTempDataId() == null ? null : tempDataMap.get(rd.getTempDataId());
            if (td != null) {
                for (Integer idx : validColIndexes) {
                    String colTitle = title == null ? null : title.getColTitle(idx);
                    String name = StrUtil.isNotBlank(colTitle) ? colTitle.trim() : ("col" + idx);
                    String v = td.getColData(idx);
                    raw.put(name, v == null ? "" : v);
                }
            }
            rawDataByRow.put(rd.getId(), raw);
        }
    }

    /**
     * 生成合法的 Excel sheet 名称：去除非法字符、长度限制 31、追加标准表头 ID 保证唯一
     */
    private String sanitizeSheetName(StandardTitleEntity st, int index) {
        String base = st.getCategoryName();
        if (StrUtil.isBlank(base)) base = st.getCategoryCode();
        if (StrUtil.isBlank(base)) base = "标准表头";
        base = base.replaceAll("[\\\\/:*?\\[\\]]", "_").trim();
        if (base.length() > 25) base = base.substring(0, 25);
        String name = base + "_" + st.getId();
        if (name.length() > 31) name = name.substring(0, 31);
        return name;
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
        // 不再静默回退第5列，返回 -1 由调用方决定降级策略
        log.warn("未找到列 '{}' 对应的索引，titleId: {}，可用列标题: [{}]",
                fullDescCol, titleEntity.getId(),
                java.util.stream.IntStream.rangeClosed(1, 10)
                    .mapToObj(i -> titleEntity.getColTitle(i))
                    .filter(Objects::nonNull)
                    .collect(Collectors.joining(", ")));
        return -1;
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
        // 物料名称（更具体的文本信号）：优先“物资名称”，其次“物资简称”
        if (extraAttrs != null) {
            ctx.setMaterialName(extraAttrs.getOrDefault("物资名称",
                    extraAttrs.getOrDefault("物资简称", "")));
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
     * 判断 AI 的 reason 是否【未】明确认可某个推荐编码/名称。
     * 返回 true 表示 reason 中既未出现推荐编码也未出现其名称 —— 即该推荐是 AI 自相矛盾的幻觉输出，应抑制。
     * 返回 false 表示 reason 明确提及并推荐了该编码（如“更精确的分类应为 100110”）—— 属合理建议，应保留。
     */
    private static boolean reasonNotEndorsingRecommendation(String reason, String code, String name) {
        if (StrUtil.isBlank(reason)) return true;
        if (StrUtil.isNotBlank(code) && reason.contains(code)) return false;
        if (StrUtil.isNotBlank(name) && reason.contains(name)) return false;
        return true;
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
     * 文本分类识别（供 AI 聊天使用）：把待识别内容作为物料描述构造临时清洗实体，
     * AI 模式下复用主流程的 aiDetect 在候选标准分类中选出最合理编码；
     * 未启用 AI 时退化为关键词召回的 top 候选。返回推荐的分类名称、分类编码与理由。
     */
    @Override
    public Map<String, Object> classifyText(String text) {
        if (StrUtil.isBlank(text)) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("message", "请输入待分类的物料描述文字");
            return r;
        }
        stdLib.ensureLoaded();
        // 固定 AI 分类：仅检查 AI 能力是否可用（AI 服务是否配置），无业务开关
        boolean aiOn = aiClientService.isEnabled();

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
            // 候选召回与主流程统一：直接用整条文字做向量库语义匹配取 top-k，
            // 语义不可用时关键词兜底；最终由大模型给出分类 + 理由。
            List<?> rawCandidates = retrieveTextCandidates(text, candidateTopK);
            List<CategoryStandardLibrary.Candidate> candidates = toStdCandidates(rawCandidates);
            AiDetectResult d = aiDetect(cd, null, candidates);
            result.put("recommendedCode", d.bestMatchCode);
            result.put("recommendedName", d.bestMatchName);
            result.put("reason", d.reason);
            result.put("score", d.score);
            result.put("candidateCount", candidates.size());
        } else {
            // AI 能力不可用（未配置）时的容错：基于关键词召回 top-k 给出最可能的标准分类
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

    /**
     * 文本分类候选召回（与批次分类统一的语义优先策略）：
     * 直接用待分类文本做向量库语义匹配取 top-k；语义不可用/未命中时回退关键词召回 top-k。
     */
    private List<?> retrieveTextCandidates(String text, int topK) {
        if (StrUtil.isNotBlank(text) && semanticLib != null && semanticLib.isEnabled()) {
            List<SemanticCategoryLibrary.Candidate> semantic = semanticLib.searchTopK(text, topK);
            if (semantic != null && !semantic.isEmpty()) return semantic;
        }
        // 用临时实体触发关键词召回
        CleanedDataEntity tmp = new CleanedDataEntity();
        tmp.setMaterialName(text);
        tmp.setFullDescription(text);
        return stdLib.retrieveCandidates(tmp, topK);
    }

    /** 把语义候选/标准库候选统一转换为标准库 Candidate 列表，供 aiDetect 使用 */
    private List<CategoryStandardLibrary.Candidate> toStdCandidates(List<?> raw) {
        List<CategoryStandardLibrary.Candidate> out = new ArrayList<>();
        if (raw == null) return out;
        for (Object o : raw) {
            if (o instanceof CategoryStandardLibrary.Candidate) out.add((CategoryStandardLibrary.Candidate) o);
            else if (o instanceof SemanticCategoryLibrary.Candidate) {
                SemanticCategoryLibrary.Candidate s = (SemanticCategoryLibrary.Candidate) o;
                out.add(new CategoryStandardLibrary.Candidate(s.getCategory(), s.getSimilarity()));
            }
        }
        return out;
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

    /** 依据阈值对单条清洗数据打状态（低于 review 阈值 → 待审核；否则 → 审核通过；不再使用 EXPORT_READY） */
    private void applyStatus(CleanedDataEntity cd, double score, double review, double export) {
        if (score < review) cd.setStatus(DataStatus.NEEDS_REVIEW);
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

}
