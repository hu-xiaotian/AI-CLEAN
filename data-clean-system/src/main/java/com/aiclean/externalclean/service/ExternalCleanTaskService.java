package com.aiclean.externalclean.service;

import com.aiclean.common.UserContext;
import com.aiclean.externalclean.client.ExternalCleanApiClient;
import com.aiclean.externalclean.config.ExternalCleanProperties;
import com.aiclean.externalclean.dto.CallbackPayload;
import com.aiclean.externalclean.dto.CleanOptions;
import com.aiclean.externalclean.dto.ExternalProgressResponse;
import com.aiclean.externalclean.dto.SubmitExternalCleanTaskRequest;
import com.aiclean.externalclean.dto.TaskRowCorrectRequest;
import com.aiclean.externalclean.entity.ExternalCleanCallbackLogEntity;
import com.aiclean.externalclean.entity.ExternalCleanTaskEntity;
import com.aiclean.externalclean.entity.ExternalCleanTaskRowEntity;
import com.aiclean.externalclean.mapper.ExternalCleanCallbackLogMapper;
import com.aiclean.externalclean.mapper.ExternalCleanTaskMapper;
import com.aiclean.externalclean.mapper.ExternalCleanTaskRowMapper;
import com.aiclean.mapper.StandardTitleMapper;
import com.aiclean.mapper.TempDataMapper;
import com.aiclean.mapper.TempDataTitleMapper;
import com.aiclean.entity.StandardTitleEntity;
import com.aiclean.entity.TempDataEntity;
import com.aiclean.entity.TempDataTitleEntity;
import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

/**
 * 外部清洗任务服务
 * 负责：提交任务（快照原始数据）、接收回调/轮询结果、采纳与修正。
 * 本服务仅读取 temp_data 用于构建快照，结果完全独立存储于本模块三张表，与系统其他功能解耦。
 */
@Slf4j
@Service
public class ExternalCleanTaskService {

    private static final int SYNC_MAX_ROWS = 10;
    /**
     * 单次同步提交外部服务的最大行数（外部接口上限，超出需拆分批次）
     */
    private static final int SYNC_BATCH_LIMIT = 10;

    private final ExternalCleanProperties properties;
    private final ExternalCleanApiClient apiClient;
    private final ExternalCleanTaskMapper taskMapper;
    private final ExternalCleanTaskRowMapper rowMapper;
    private final ExternalCleanCallbackLogMapper callbackLogMapper;
    private final TempDataTitleMapper tempDataTitleMapper;
    private final TempDataMapper tempDataMapper;
    private final StandardTitleMapper standardTitleMapper;

    public ExternalCleanTaskService(ExternalCleanProperties properties,
                                    ExternalCleanApiClient apiClient,
                                    ExternalCleanTaskMapper taskMapper,
                                    ExternalCleanTaskRowMapper rowMapper,
                                    ExternalCleanCallbackLogMapper callbackLogMapper,
                                    TempDataTitleMapper tempDataTitleMapper,
                                    TempDataMapper tempDataMapper,
                                    StandardTitleMapper standardTitleMapper) {
        this.properties = properties;
        this.apiClient = apiClient;
        this.taskMapper = taskMapper;
        this.rowMapper = rowMapper;
        this.callbackLogMapper = callbackLogMapper;
        this.tempDataTitleMapper = tempDataTitleMapper;
        this.tempDataMapper = tempDataMapper;
        this.standardTitleMapper = standardTitleMapper;
    }

    // ===================== 提交任务 =====================

    /**
     * 提交外部清洗任务。
     * <p>同步模式（sync）若数据行超过 {@link #SYNC_BATCH_LIMIT} 条，外部服务单次有上限，
     * 调用方可分多次提交：首批不带 appendTaskId 新建任务，后续批次携带同一 appendTaskId 追加，
     * 复用同一 taskId，每批最多 {@link #SYNC_BATCH_LIMIT} 条。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    public ExternalCleanTaskEntity submitTask(SubmitExternalCleanTaskRequest request) {
        if (!properties.isEnabled()) {
            throw new IllegalStateException("外部清洗模块未启用（app.external-clean.enabled=false）");
        }
        if (request.getTempDataTitleId() == null) {
            throw new IllegalArgumentException("tempDataTitleId 不能为空");
        }
        TempDataTitleEntity title = tempDataTitleMapper.selectById(request.getTempDataTitleId());
        if (title == null) {
            throw new IllegalArgumentException("数据文件不存在: " + request.getTempDataTitleId());
        }

        // ---- 追加模式：本次提交作为已有任务的后续批次 ----
        if (StringUtils.hasText(request.getAppendTaskId())) {
            return appendToTask(request, title);
        }

        // 1. 读取选定行，构建列数据快照
        List<TempDataEntity> tempRows = loadTempRows(title.getId(), request.getRowIds());
        if (tempRows.isEmpty()) {
            throw new IllegalArgumentException("未找到可清洗的数据行");
        }
        CleanOptions options = request.getOptions() == null ? new CleanOptions() : request.getOptions();

        // 2. 生成业务 task_id 后插入（task_id 列非空约束，必须在插入前赋值，不依赖自增 id）
        ExternalCleanTaskEntity task = new ExternalCleanTaskEntity();
        task.setTempDataTitleId(title.getId());
        task.setFileName(title.getFileName());
        // 模式优先取请求指定值，未指定则由行数自动判定
        String reqMode = request.getMode();
        if ("sync".equalsIgnoreCase(reqMode) || "async".equalsIgnoreCase(reqMode)) {
            task.setMode(reqMode.toLowerCase());
        } else {
            task.setMode(tempRows.size() <= SYNC_MAX_ROWS ? "sync" : "async");
        }
        task.setStatus("pending");
        task.setTotalRows(tempRows.size());
        task.setOptionsJson(JSON.toJSONString(options));
        task.setRetryCount(0);
        task.setTaskId(generateTaskId());
        taskMapper.insert(task);

        // 3. 写入行快照
        List<Map<String, String>> columnsList = buildRowSnapshots(task, title, tempRows, 0);

        // 4. 提交外部服务
        submitToExternal(task, options, columnsList, 1);
        return taskMapper.selectById(task.getId());
    }

    /**
     * 追加批次：往已有任务追加行快照并提交同步清洗（同一 taskId）。
     * 行号（rowIndex / 外部 index）从已有行数之后连续递增，保证结果能正确落回对应行。
     */
    @Transactional(rollbackFor = Exception.class)
    public ExternalCleanTaskEntity appendToTask(SubmitExternalCleanTaskRequest request, TempDataTitleEntity title) {
        ExternalCleanTaskEntity task = getTask(request.getAppendTaskId());
        if (task == null) {
            throw new IllegalArgumentException("追加失败：目标任务不存在 " + request.getAppendTaskId());
        }
        if (!"sync".equals(task.getMode())) {
            throw new IllegalStateException("仅同步任务支持追加分批提交，目标任务模式为 " + task.getMode());
        }
        if ("completed".equals(task.getStatus()) || "failed".equals(task.getStatus())
                || "cancelled".equals(task.getStatus())) {
            throw new IllegalStateException("目标任务已处于终态(" + task.getStatus() + ")，无法追加");
        }

        // 读取本批次行并追加行快照
        List<TempDataEntity> tempRows = loadTempRows(title.getId(), request.getRowIds());
        if (tempRows.isEmpty()) {
            throw new IllegalArgumentException("未找到可清洗的数据行");
        }
        CleanOptions options = request.getOptions() == null ? new CleanOptions() : request.getOptions();
        int baseIndex = countTaskRows(task.getTaskId());
        List<Map<String, String>> columnsList = buildRowSnapshots(task, title, tempRows, baseIndex);

        // 追加批次只允许同步提交（复用同一任务）
        submitToExternal(task, options, columnsList, baseIndex + 1);

        // 更新任务总行数
        task.setTotalRows(task.getTotalRows() + tempRows.size());
        taskMapper.updateById(task);
        return taskMapper.selectById(task.getId());
    }

    /**
     * 写入行快照，rowIndex 从 startRowIndex 开始连续递增；返回与行一一对应的列快照列表
     */
    private List<Map<String, String>> buildRowSnapshots(ExternalCleanTaskEntity task, TempDataTitleEntity title,
                                                        List<TempDataEntity> tempRows, int startRowIndex) {
        List<Map<String, String>> columnsList = new ArrayList<>(tempRows.size());
        int rowIdx = startRowIndex;
        for (TempDataEntity tr : tempRows) {
            Map<String, String> columns = buildColumns(title, tr);
            columnsList.add(columns);
            rowIdx++;
            ExternalCleanTaskRowEntity row = new ExternalCleanTaskRowEntity();
            row.setTaskId(task.getTaskId());
            row.setRowIndex(rowIdx);
            row.setTempDataId(tr.getId());
            row.setRequestColumnsJson(JSON.toJSONString(columns));
            row.setRowStatus("pending");
            rowMapper.insert(row);
        }
        return columnsList;
    }

    /**
     * 统计任务已有行数
     */
    private int countTaskRows(String taskId) {
        return Math.toIntExact(rowMapper.selectCount(
                new LambdaQueryWrapper<ExternalCleanTaskRowEntity>().eq(ExternalCleanTaskRowEntity::getTaskId, taskId)));
    }

    /**
     * 将列快照提交到外部服务。
     * 同步模式若超过 {@link #SYNC_BATCH_LIMIT} 则自动按批拆分（每批最多该上限条），
     * 因 submitSync 为阻塞调用，逐批 await 天然满足"前批完成再提交下批"。
     */
    private void submitToExternal(ExternalCleanTaskEntity task, CleanOptions options,
                                  List<Map<String, String>> columnsList, int startIndex) {
        String callbackUrl = task.getCallbackUrl();
        if (callbackUrl == null) {
            callbackUrl = properties.getCallbackBaseUrl();
            if (cn.hutool.core.util.StrUtil.isNotBlank(callbackUrl)) {
                callbackUrl = (callbackUrl.endsWith("/") ? callbackUrl.substring(0, callbackUrl.length() - 1) : callbackUrl)
                        + "/api/internal/tasks/" + task.getTaskId() + "/result";
                task.setCallbackUrl(callbackUrl);
            }
        }
        task.setStatus("submitting");
        taskMapper.updateById(task);

//        if ("sync".equals(task.getMode())) {
//            try {
//                for (int i = 0; i < columnsList.size(); i += SYNC_BATCH_LIMIT) {
//                    int to = Math.min(i + SYNC_BATCH_LIMIT, columnsList.size());
//                    List<Map<String, String>> batch = columnsList.subList(i, to);
//                    // 本批起始行号：全局偏移 + 批次内偏移
//                    int batchStart = startIndex + i;
//                    CallbackPayload payload = apiClient.submitSync(task.getTaskId(), batch, options, batchStart);
//                    task.setStatus("processing");
//                    task.setSubmittedAt(LocalDateTime.now());
//                    taskMapper.updateById(task);
//                    // 同步模式直接应用结果（无需回调）。applyResults 按 item.index 落回对应行
//                    applyResults(task, payload, null);
//                }
//            } catch (Exception e) {
//                task.setStatus("failed");
//                task.setErrorMessage("同步清洗失败: " + e.getMessage());
//                taskMapper.updateById(task);
//                throw new RuntimeException(task.getErrorMessage(), e);
//            }
//        } else {
        // 提交外部智能体前，清理列值中的引号/控制字符，避免破坏请求 JSON 结构
        // （本地行快照仍保留原始数据，仅对外提交的数据做清洗）
        List<Map<String, String>> safeColumnsList = sanitizeColumnsForJson(columnsList);
        boolean submitted = apiClient.submitAsync(task.getTaskId(), callbackUrl, safeColumnsList, options);
        if (submitted) {
            task.setStatus("processing");
            task.setSubmittedAt(LocalDateTime.now());
        } else {
            task.setStatus("failed");
            task.setErrorMessage("外部服务未接受任务（返回非 202）");
        }
        taskMapper.updateById(task);
        if (!submitted) {
            throw new RuntimeException(task.getErrorMessage());
        }
//        }
    }

    private List<TempDataEntity> loadTempRows(Long titleId, List<Long> rowIds) {
        if (CollectionUtils.isEmpty(rowIds)) {
            return tempDataMapper.selectList(
                    new LambdaQueryWrapper<TempDataEntity>().eq(TempDataEntity::getTempDataTitleId, titleId));
        }
        List<TempDataEntity> all = tempDataMapper.selectBatchIds(rowIds);
        return all.stream().filter(r -> titleId.equals(r.getTempDataTitleId())).collect(Collectors.toList());
    }

    /**
     * 将 temp_data 行转为 列名->列值 的快照（与 api-design.md RawRow.columns 对齐）
     */
    private Map<String, String> buildColumns(TempDataTitleEntity title, TempDataEntity tr) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 1; i <= 10; i++) {
            String colTitle = title.getColTitle(i);
            String colData = tr.getColData(i);
            if (colTitle != null && cn.hutool.core.util.StrUtil.isNotBlank(colTitle)) {
                map.put(colTitle, colData == null ? "" : colData);
            }
        }
        return map;
    }

    /**
     * 提交外部智能体前，对列值做 JSON 安全清洗：
     * 去掉双引号（"）以免破坏请求 JSON 的字段边界；同时剔除反斜杠与控制字符（换行/回车/Tab 等），
     * 避免这些值在 JSON 序列化前/后被错误解析。键（列名）本身不被修改，且不影响本地存储的原始数据。
     */
    private List<Map<String, String>> sanitizeColumnsForJson(List<Map<String, String>> columnsList) {
        List<Map<String, String>> result = new ArrayList<>(columnsList.size());
        for (Map<String, String> columns : columnsList) {
            Map<String, String> safe = new LinkedHashMap<>();
            for (Map.Entry<String, String> e : columns.entrySet()) {
                safe.put(e.getKey(), sanitizeValue(e.getValue()));
            }
            result.add(safe);
        }
        return result;
    }

    private String sanitizeValue(String value) {
        if (value == null) {
            return "";
        }
        // 去掉双引号，并移除反斜杠与控制字符（\u0000-\u001F）
        return value.replace("\"", "")
                .replace("\\", "")
                .replaceAll("[\\x00-\\x1F]", "");
    }

    // ===================== 查询 =====================

    /**
     * 生成业务 task_id。task_id 列有非空约束，必须在插入任务前生成，
     * 因此不能依赖自增主键 id。采用"日期 + 纳秒时间戳 + 随机数"保证唯一性，
     * 以满足 idx_ect_task_id 唯一索引要求。
     */
    private String generateTaskId() {
        String date = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        String seq = String.format("%013d", System.nanoTime() % 1_000_000_000_000L)
                + ThreadLocalRandom.current().nextInt(100, 1000);
        return "task-" + date + "-" + seq;
    }

    public IPage<ExternalCleanTaskEntity> listTasks(int page, int size, String status, String sortField, String sortOrder) {
        LambdaQueryWrapper<ExternalCleanTaskEntity> q = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(status)) {
            q.eq(ExternalCleanTaskEntity::getStatus, status);
        }
        applyTaskSort(q, sortField, sortOrder);
        return taskMapper.selectPage(new Page<>(page, size), q);
    }

    /** 将前端传入的排序字段与方向映射为数据库排序条件 */
    private void applyTaskSort(LambdaQueryWrapper<ExternalCleanTaskEntity> q, String sortField, String sortOrder) {
        // 排序字段白名单，避免 SQL 注入；为空时按创建时间倒序
        boolean asc = "asc".equalsIgnoreCase(sortOrder);
        if ("fileName".equals(sortField)) {
            q.orderBy(true, asc, ExternalCleanTaskEntity::getFileName);
        } else if ("submittedAt".equals(sortField)) {
            q.orderBy(true, asc, ExternalCleanTaskEntity::getSubmittedAt);
        } else if ("completedAt".equals(sortField)) {
            q.orderBy(true, asc, ExternalCleanTaskEntity::getCompletedAt);
        } else if ("status".equals(sortField)) {
            q.orderBy(true, asc, ExternalCleanTaskEntity::getStatus);
        } else if ("estimatedAccuracy".equals(sortField)) {
            q.orderBy(true, asc, ExternalCleanTaskEntity::getEstimatedAccuracy);
        } else {
            q.orderBy(true, asc, ExternalCleanTaskEntity::getCreatedAt);
        }
    }

    public ExternalCleanTaskEntity getTask(String taskId) {
        return taskMapper.selectOne(new LambdaQueryWrapper<ExternalCleanTaskEntity>().eq(ExternalCleanTaskEntity::getTaskId, taskId));
    }

    public IPage<ExternalCleanTaskRowEntity> listRows(String taskId, int page, int size, Integer needsReview) {
        LambdaQueryWrapper<ExternalCleanTaskRowEntity> q = new LambdaQueryWrapper<>();
        q.eq(ExternalCleanTaskRowEntity::getTaskId, taskId);
        if (needsReview != null) {
            q.eq(ExternalCleanTaskRowEntity::getNeedsReview, needsReview);
        }
        q.orderByAsc(ExternalCleanTaskRowEntity::getRowIndex);
        return rowMapper.selectPage(new Page<>(page, size), q);
    }

    /**
     * 导出任务结果：按分类分 Sheet。
     * <p>每个 Sheet 表头顺序为：行号 + 原始数据列（前置，便于与结果比对） + 结果属性列
     * （extractedAttrsJson 属性列，缺失属性补空列）。原始数据优先取 temp_data 中
     * tempDataId 对应的行（列名取 temp_data_title 的表头），若源行已删除则回退使用
     * 提交时的快照 requestColumnsJson。</p>
     */
    public byte[] exportRowsByCategory(String taskId) {
        List<ExternalCleanTaskRowEntity> rows = rowMapper.selectList(
                new LambdaQueryWrapper<ExternalCleanTaskRowEntity>()
                        .eq(ExternalCleanTaskRowEntity::getTaskId, taskId)
                        .orderByAsc(ExternalCleanTaskRowEntity::getRowIndex));
        if (rows == null || rows.isEmpty()) {
            throw new IllegalStateException("该任务暂无结果数据，无法导出");
        }

        // 原始数据：rowIndex -> (原始列名 -> 值)，以及全局原始列顺序
        List<String> rawColumnOrder = new ArrayList<>();
        Map<Integer, Map<String, String>> rowRawData = loadRawDataForExport(taskId, rows, rawColumnOrder);

        // 解析每行的属性 Map（含缺失属性）
        Map<Integer, Map<String, String>> rowAttrs = new LinkedHashMap<>();
        for (ExternalCleanTaskRowEntity row : rows) {
            Map<String, String> attrs = new LinkedHashMap<>();
            if (StringUtils.hasText(row.getExtractedAttrsJson())) {
                try {
                    Map<?, ?> parsed = JSON.parseObject(row.getExtractedAttrsJson(), Map.class);
                    for (Map.Entry<?, ?> e : parsed.entrySet()) {
                        String k = String.valueOf(e.getKey());
                        Object v = e.getValue();
                        attrs.put(k, v == null ? "" : (v instanceof Map || v instanceof List ? JSON.toJSONString(v) : String.valueOf(v)));
                    }
                } catch (Exception ex) {
                    log.warn("行 {} 的 extractedAttrsJson 解析失败", row.getRowIndex(), ex);
                }
            }
            // 缺失属性（missingAttrsJson）也作为属性键记录（值为空）
            if (StringUtils.hasText(row.getMissingAttrsJson())) {
                try {
                    List<?> missing = JSON.parseArray(row.getMissingAttrsJson(), String.class);
                    if (missing != null) {
                        for (Object m : missing) {
                            String k = String.valueOf(m);
                            attrs.putIfAbsent(k, "");
                        }
                    }
                } catch (Exception ex) {
                    log.warn("行 {} 的 missingAttrsJson 解析失败", row.getRowIndex(), ex);
                }
            }
            rowAttrs.put(row.getRowIndex(), attrs);
        }

        // 按分类分组
        Map<String, List<ExternalCleanTaskRowEntity>> byCategory = new LinkedHashMap<>();
        for (ExternalCleanTaskRowEntity row : rows) {
            String cat = StringUtils.hasText(row.getCategoryName()) ? row.getCategoryName()
                    : (StringUtils.hasText(row.getCategoryCode()) ? row.getCategoryCode() : "未分类");
            byCategory.computeIfAbsent(cat, k -> new ArrayList<>()).add(row);
        }

        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            CellStyle headerStyle = buildExportHeaderStyle(workbook);
            CellStyle rawHeaderStyle = buildExportRawHeaderStyle(workbook);
            int catSeq = 0;
            for (Map.Entry<String, List<ExternalCleanTaskRowEntity>> entry : byCategory.entrySet()) {
                String sheetName = sanitizeSheetName(entry.getKey(), catSeq++);
                Sheet sheet = workbook.createSheet(sheetName);

                // 仅取当前分类内的属性并集（含缺失属性），不混入其它分类的字段
                List<String> categoryKeys = new ArrayList<>();
                for (ExternalCleanTaskRowEntity row : entry.getValue()) {
                    Map<String, String> attrs = rowAttrs.getOrDefault(row.getRowIndex(), new LinkedHashMap<>());
                    for (String k : attrs.keySet()) {
                        if (!categoryKeys.contains(k)) categoryKeys.add(k);
                    }
                }

                // 表头顺序：优先采用标准字段表头(colTitle1..20)顺序，与"标准字段表头"页保持一致；
                // 标准表头未覆盖的提取属性（如 AI 额外抽出的字段）追加在末尾，避免数据丢失
                String catCode = entry.getValue().isEmpty() ? null : entry.getValue().get(0).getCategoryCode();
                List<String> standardCols = resolveStandardTitleColumns(catCode);
                List<String> orderedKeys = new ArrayList<>();
                if (standardCols != null && !standardCols.isEmpty()) {
                    orderedKeys.addAll(standardCols);
                    java.util.Set<String> covered = new java.util.LinkedHashSet<>(standardCols);
                    for (String k : categoryKeys) {
                        if (!covered.contains(k)) orderedKeys.add(k);
                    }
                } else {
                    orderedKeys.addAll(categoryKeys);
                }

                // 本分类实际出现过的原始数据列（保持全局原始列顺序）
                List<String> rawKeys = new ArrayList<>();
                for (String rk : rawColumnOrder) {
                    for (ExternalCleanTaskRowEntity row : entry.getValue()) {
                        Map<String, String> raw = rowRawData.get(row.getRowIndex());
                        if (raw != null && raw.containsKey(rk)) {
                            rawKeys.add(rk);
                            break;
                        }
                    }
                }

                // 表头：行号 + 原始数据列（前置，便于对比） + 本分类结果属性列（按标准/提取顺序）
                List<String> headers = new ArrayList<>();
                headers.add("行号");
                for (String rk : rawKeys) {
                    headers.add("原始-" + rk);
                }
                headers.addAll(orderedKeys);
                Row headerRow = sheet.createRow(0);
                for (int i = 0; i < headers.size(); i++) {
                    org.apache.poi.ss.usermodel.Cell cell = headerRow.createCell(i);
                    cell.setCellValue(headers.get(i));
                    cell.setCellStyle(i >= 1 && i <= rawKeys.size() ? rawHeaderStyle : headerStyle);
                }

                int r = 1;
                for (ExternalCleanTaskRowEntity row : entry.getValue()) {
                    Row excelRow = sheet.createRow(r++);
                    excelRow.createCell(0).setCellValue(row.getRowIndex() == null ? "" : String.valueOf(row.getRowIndex()));
                    // 原始数据列
                    Map<String, String> raw = rowRawData.getOrDefault(row.getRowIndex(), new LinkedHashMap<>());
                    for (int i = 0; i < rawKeys.size(); i++) {
                        String v = raw.get(rawKeys.get(i));
                        excelRow.createCell(1 + i).setCellValue(v == null ? "" : v);
                    }
                    // 结果属性列
                    int offset = 1 + rawKeys.size();
                    Map<String, String> attrs = rowAttrs.getOrDefault(row.getRowIndex(), new LinkedHashMap<>());
                    for (int i = 0; i < orderedKeys.size(); i++) {
                        String v = attrs.get(orderedKeys.get(i));
                        excelRow.createCell(offset + i).setCellValue(v == null ? "" : v);
                    }
                }
                for (int i = 0; i < headers.size(); i++) {
                    sheet.autoSizeColumn(i);
                }
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            workbook.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException("导出 Excel 失败: " + e.getMessage(), e);
        }
    }

    /**
     * 根据分类编码获取标准字段表头列名（按 colTitle1..20 物理顺序，跳过空列）。
     * 用于任务结果导出时，使表头顺序与"标准字段表头"保持一致。
     */
    private List<String> resolveStandardTitleColumns(String categoryCode) {
        if (!StringUtils.hasText(categoryCode)) {
            return new ArrayList<>();
        }
        StandardTitleEntity std = standardTitleMapper.selectByCategoryCode(categoryCode);
        if (std == null) {
            return new ArrayList<>();
        }
        List<String> cols = new ArrayList<>();
        for (int i = 1; i <= 20; i++) {
            String title = std.getColTitle(i);
            if (StringUtils.hasText(title)) {
                cols.add(title);
            }
        }
        return cols;
    }

    private CellStyle buildExportHeaderStyle(XSSFWorkbook workbook) {
        CellStyle style = workbook.createCellStyle();
        Font font = workbook.createFont();
        font.setBold(true);
        style.setFont(font);
        style.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        return style;
    }

    /**
     * 原始数据列表头样式：与结果列区分（浅蓝底色）
     */
    private CellStyle buildExportRawHeaderStyle(XSSFWorkbook workbook) {
        CellStyle style = workbook.createCellStyle();
        Font font = workbook.createFont();
        font.setBold(true);
        style.setFont(font);
        style.setFillForegroundColor(IndexedColors.PALE_BLUE.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        return style;
    }

    /**
     * 加载导出所需的原始数据。
     * <p>优先按行的 tempDataId 回查 temp_data（列名取 temp_data_title 的 colNTitle），
     * 源行已删除或无 tempDataId 时回退使用提交快照 requestColumnsJson。</p>
     *
     * @param rawColumnOrder 出参，收集全局原始列顺序（表头顺序优先，快照额外列追加在后）
     * @return rowIndex -> (原始列名 -> 值)
     */
    private Map<Integer, Map<String, String>> loadRawDataForExport(String taskId,
                                                                   List<ExternalCleanTaskRowEntity> rows,
                                                                   List<String> rawColumnOrder) {
        Map<Integer, Map<String, String>> result = new LinkedHashMap<>();

        // 1) 批量查询 temp_data
        List<Long> tempDataIds = rows.stream()
                .map(ExternalCleanTaskRowEntity::getTempDataId)
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
                log.warn("导出时回查 temp_data 失败 taskId={}，将回退使用提交快照", taskId, e);
            }
        }

        // 2) 原始表头（列名）
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
        // 有效列索引：该列有标题，或任一行该列有值
        List<Integer> validColIndexes = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            String colTitle = title == null ? null : title.getColTitle(i);
            boolean hasTitle = StringUtils.hasText(colTitle);
            boolean hasValue = false;
            if (!hasTitle) {
                for (TempDataEntity td : tempDataMap.values()) {
                    if (StringUtils.hasText(td.getColData(i))) {
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

        // 3) 逐行组装
        for (ExternalCleanTaskRowEntity row : rows) {
            Map<String, String> raw = new LinkedHashMap<>();
            TempDataEntity td = row.getTempDataId() == null ? null : tempDataMap.get(row.getTempDataId());
            if (td != null) {
                for (Integer idx : validColIndexes) {
                    String colTitle = title == null ? null : title.getColTitle(idx);
                    String name = StringUtils.hasText(colTitle) ? colTitle.trim() : ("col" + idx);
                    String v = td.getColData(idx);
                    raw.put(name, v == null ? "" : v);
                }
            } else if (StringUtils.hasText(row.getRequestColumnsJson())) {
                // 回退：使用提交时的快照
                try {
                    Map<?, ?> parsed = JSON.parseObject(row.getRequestColumnsJson(), Map.class);
                    for (Map.Entry<?, ?> e : parsed.entrySet()) {
                        String k = String.valueOf(e.getKey());
                        Object v = e.getValue();
                        raw.put(k, v == null ? "" : (v instanceof Map || v instanceof List ? JSON.toJSONString(v) : String.valueOf(v)));
                        if (!rawColumnOrder.contains(k)) {
                            rawColumnOrder.add(k);
                        }
                    }
                } catch (Exception ex) {
                    log.warn("行 {} 的 requestColumnsJson 解析失败", row.getRowIndex(), ex);
                }
            }
            result.put(row.getRowIndex(), raw);
        }
        return result;
    }

    private String sanitizeSheetName(String base, int index) {
        if (!StringUtils.hasText(base)) base = "未分类";
        base = base.replaceAll("[\\\\/:*?\\[\\]]", "_").trim();
        if (base.length() > 27) base = base.substring(0, 27);
        String name = base + "_" + index;
        if (name.length() > 31) name = name.substring(0, 31);
        return name;
    }

    // ===================== 回调 / 轮询结果处理 =====================

    /**
     * 处理外部回调（也用于轮询兜底）。幂等由 payload_digest 保证。
     *
     * @return 处理结果: success/duplicate/invalid/error
     */
    @Transactional(rollbackFor = Exception.class)
    public String handleCallback(String taskId, Integer pageNo, Integer pageSize, CallbackPayload payload) {
        ExternalCleanTaskEntity task = getTask(taskId);
        if (task == null) {
            return "invalid";
        }
        // 将对象序列化回原始报文，用于幂等摘要与日志快照
        String rawBody = payload == null ? "" : JSON.toJSONString(payload);
        String digest = sha256Hex(rawBody);

        // 幂等去重
        long dup = callbackLogMapper.selectCount(new LambdaQueryWrapper<ExternalCleanCallbackLogEntity>()
                .eq(ExternalCleanCallbackLogEntity::getPayloadDigest, digest));
        if (dup > 0) {
            return "duplicate";
        }

        try {
            applyResults(task, payload, digest);
            saveCallbackLog(taskId, pageNo, pageSize, digest, rawBody, "success", null);
            return "success";
        } catch (Exception e) {
            log.error("处理回调结果失败 taskId={}", taskId, e);
            saveCallbackLog(taskId, pageNo, pageSize, digest, rawBody, "error", e.getMessage());
            return "error";
        }
    }

    /**
     * 将回调/轮询结果写入任务与行表
     */
    private void applyResults(ExternalCleanTaskEntity task, CallbackPayload payload, String digest) {
        if (payload == null) {
            return;
        }
        // 更新任务统计
        if (payload.getStats() != null) {
            CallbackPayload.Stats s = payload.getStats();
            task.setTotalRows(s.getTotalRows());
            task.setClassifiedRows(s.getClassifiedRows());
            task.setProcessedRows(s.getProcessedRows());
            task.setHighConfidence(s.getHighConfidence());
            task.setMediumConfidence(s.getMediumConfidence());
            task.setLowConfidence(s.getLowConfidence());
            task.setConfidenceSum(s.getConfidenceSum());
            task.setEstimatedAccuracy(s.getEstimatedAccuracy());
        }
        // 任务终态
        String status = payload.getStatus();
        task.setCallbackReceivedAt(LocalDateTime.now());
        if ("completed".equals(status) || "failed".equals(status) || "cancelled".equals(status)) {
            task.setStatus(status);
            task.setCompletedAt(LocalDateTime.now());
            task.setErrorMessage(payload.getError());
        } else {
            task.setStatus("processing");
        }
        taskMapper.updateById(task);

        // 更新行结果
        if (payload.getResults() != null) {
            for (CallbackPayload.CleanResultItem item : payload.getResults()) {
                if (item.getIndex() == null) continue;
                ExternalCleanTaskRowEntity row = rowMapper.selectOne(
                        new LambdaQueryWrapper<ExternalCleanTaskRowEntity>()
                                .eq(ExternalCleanTaskRowEntity::getTaskId, task.getTaskId())
                                .eq(ExternalCleanTaskRowEntity::getRowIndex, item.getIndex()));
                if (row == null) {
                    row = new ExternalCleanTaskRowEntity();
                    row.setTaskId(task.getTaskId());
                    row.setRowIndex(item.getIndex());
                }
                row.setCategoryCode(item.getCategoryCode());
                row.setCategoryName(item.getCategoryName());
                row.setCategoryPath(item.getCategoryPath());
                row.setConfidence(item.getConfidence());
                row.setCategoryConfidence(item.getCategoryConfidence());
                row.setExtractedAttrsJson(item.getExtractedAttrs() == null ? null : JSON.toJSONString(item.getExtractedAttrs(), com.alibaba.fastjson2.JSONWriter.Feature.WriteMapNullValue));
                row.setMissingAttrsJson(item.getMissingAttrs() == null ? null : JSON.toJSONString(item.getMissingAttrs()));
                row.setNeedsReview(Boolean.TRUE.equals(item.getNeedsReview()) ? 1 : 0);
                row.setReviewReason(item.getReviewReason());
                row.setCategoryCitationsJson(item.getCategoryCitations() == null ? null : JSON.toJSONString(item.getCategoryCitations()));
                row.setAttrCitationsJson(item.getAttrCitations() == null ? null : JSON.toJSONString(item.getAttrCitations()));
                // 若尚未被人工采纳/修正，则置为 completed
                if (!"accepted".equals(row.getRowStatus()) && !"corrected".equals(row.getRowStatus()) && !"rejected".equals(row.getRowStatus())) {
                    row.setRowStatus("completed");
                }
                if (row.getId() == null) {
                    rowMapper.insert(row);
                } else {
                    rowMapper.updateById(row);
                }
            }
        }
    }

    /**
     * 主动到外部接口查询任务进展并更新到数据库（供前端定时轮询触发）。
     * 仅当本地任务处于 processing/pending 等未完成态时才拉取，避免无意义请求。
     * 返回更新后的任务实体；若无需更新或查询失败则返回 null。
     */
    public ExternalCleanTaskEntity queryAndUpdateProgress(String taskId) {
        ExternalCleanTaskEntity task = taskMapper.selectOne(
                new LambdaQueryWrapper<ExternalCleanTaskEntity>()
                        .eq(ExternalCleanTaskEntity::getTaskId, taskId));
        if (task == null) {
            return null;
        }
        // 终态任务无需再查询外部进展
        if (isTerminalStatus(task.getStatus())) {
            return task;
        }
        ExternalProgressResponse resp = apiClient.queryProgress(taskId);
        if (resp == null) {
            log.warn("查询外部任务进展失败，跳过更新 taskId={}", taskId);
            return task;
        }
        // 仅在数据库比外部少时回写，避免覆盖人工已更新的行数
        if (resp.getStats() != null) {
            ExternalProgressResponse.Stats s = resp.getStats();
            if (s.getTotalRows() != null) task.setTotalRows(s.getTotalRows());
            if (s.getClassifiedRows() != null) task.setClassifiedRows(s.getClassifiedRows());
            if (s.getProcessedRows() != null) task.setProcessedRows(s.getProcessedRows());
            if (s.getHighConfidence() != null) task.setHighConfidence(s.getHighConfidence());
            if (s.getMediumConfidence() != null) task.setMediumConfidence(s.getMediumConfidence());
            if (s.getLowConfidence() != null) task.setLowConfidence(s.getLowConfidence());
            if (s.getConfidenceSum() != null) task.setConfidenceSum(s.getConfidenceSum());
            if (s.getEstimatedAccuracy() != null) task.setEstimatedAccuracy(s.getEstimatedAccuracy());
        }
        // 同步外部状态；进入终态则记录完成时间
        String status = resp.getStatus();
        if ("completed".equals(status) || "failed".equals(status) || "cancelled".equals(status)) {
            task.setStatus(status);
            task.setCompletedAt(LocalDateTime.now());
            if ("failed".equals(status) || "cancelled".equals(status)) {
                task.setErrorMessage(resp.getError());
            }
        } else if (status != null) {
            task.setStatus(status);
        }
        taskMapper.updateById(task);
        return task;
    }

    private void saveCallbackLog(String taskId, Integer pageNo, Integer pageSize, String digest,
                                 String rawBody, String result, String error) {
        ExternalCleanCallbackLogEntity log = new ExternalCleanCallbackLogEntity();
        log.setTaskId(taskId);
        log.setPageNo(pageNo);
        log.setPageSize(pageSize);
        log.setPayloadDigest(digest);
        log.setPayloadSnapshot(rawBody);
        log.setProcessResult(result);
        log.setErrorMessage(error);
        log.setReceivedAt(LocalDateTime.now());
        callbackLogMapper.insert(log);
    }

    // ===================== 采纳 / 修正 / 驳回 =====================

    @Transactional(rollbackFor = Exception.class)
    public void adoptRow(String taskId, int rowIndex) {
        ExternalCleanTaskRowEntity row = requireRow(taskId, rowIndex);
        row.setRowStatus("accepted");
        row.setOperatedBy(currentUser());
        row.setOperatedAt(LocalDateTime.now());
        rowMapper.updateById(row);
    }

    @Transactional(rollbackFor = Exception.class)
    public void adoptAll(String taskId) {
        ExternalCleanTaskEntity task = requireTask(taskId);
        List<ExternalCleanTaskRowEntity> rows = rowMapper.selectList(
                new LambdaQueryWrapper<ExternalCleanTaskRowEntity>().eq(ExternalCleanTaskRowEntity::getTaskId, taskId));
        String user = currentUser();
        for (ExternalCleanTaskRowEntity row : rows) {
            if ("completed".equals(row.getRowStatus()) || "pending".equals(row.getRowStatus())) {
                row.setRowStatus("accepted");
                row.setOperatedBy(user);
                row.setOperatedAt(LocalDateTime.now());
                rowMapper.updateById(row);
            }
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public void rejectRow(String taskId, int rowIndex, String comment) {
        ExternalCleanTaskRowEntity row = requireRow(taskId, rowIndex);
        row.setRowStatus("rejected");
        row.setCorrectComment(comment);
        row.setOperatedBy(currentUser());
        row.setOperatedAt(LocalDateTime.now());
        rowMapper.updateById(row);
    }

    @Transactional(rollbackFor = Exception.class)
    public void correctRow(String taskId, int rowIndex, TaskRowCorrectRequest req) {
        ExternalCleanTaskRowEntity row = requireRow(taskId, rowIndex);
        row.setCorrectedCategoryCode(req.getCorrectedCategoryCode());
        row.setCorrectedCategoryName(req.getCorrectedCategoryName());
        row.setCorrectedAttrsJson(req.getCorrectedAttrs() == null ? null : JSON.toJSONString(req.getCorrectedAttrs()));
        row.setCorrectComment(req.getComment());
        row.setRowStatus("corrected");
        row.setOperatedBy(currentUser());
        row.setOperatedAt(LocalDateTime.now());
        if (req.getCorrectedCategoryCode() != null) {
            // 同步修正标准分类展示字段，便于前端直接看到
            row.setCategoryCode(req.getCorrectedCategoryCode());
        }
        if (req.getCorrectedCategoryName() != null) {
            row.setCategoryName(req.getCorrectedCategoryName());
        }
        rowMapper.updateById(row);
    }

    /**
     * 填充缺失属性：将手工填入的属性合并进 extractedAttrsJson，并从 missingAttrsJson 中移除已填充项。
     */
    @Transactional(rollbackFor = Exception.class)
    public void fillMissing(String taskId, int rowIndex, Map<String, String> filled) {
        if (filled == null || filled.isEmpty()) return;
        ExternalCleanTaskRowEntity row = requireRow(taskId, rowIndex);
        Map<String, String> attrs = new LinkedHashMap<>();
        if (StringUtils.hasText(row.getExtractedAttrsJson())) {
            try {
                Map<?, ?> parsed = JSON.parseObject(row.getExtractedAttrsJson(), Map.class);
                for (Map.Entry<?, ?> e : parsed.entrySet()) {
                    Object v = e.getValue();
                    attrs.put(String.valueOf(e.getKey()), v == null ? "" : (v instanceof Map || v instanceof List ? JSON.toJSONString(v) : String.valueOf(v)));
                }
            } catch (Exception ex) {
                log.warn("行 {} extractedAttrsJson 解析失败", rowIndex, ex);
            }
        }
        List<String> stillMissing = new ArrayList<>();
        if (StringUtils.hasText(row.getMissingAttrsJson())) {
            try {
                List<?> missing = JSON.parseArray(row.getMissingAttrsJson(), String.class);
                if (missing != null) {
                    for (Object m : missing) stillMissing.add(String.valueOf(m));
                }
            } catch (Exception ex) {
                log.warn("行 {} missingAttrsJson 解析失败", rowIndex, ex);
            }
        }
        for (Map.Entry<String, String> e : filled.entrySet()) {
            String key = e.getKey();
            attrs.put(key, e.getValue() == null ? "" : e.getValue());
            stillMissing.remove(key);
        }
        row.setExtractedAttrsJson(JSON.toJSONString(attrs, com.alibaba.fastjson2.JSONWriter.Feature.WriteMapNullValue));
        row.setMissingAttrsJson(stillMissing.isEmpty() ? null : JSON.toJSONString(stillMissing));
        if (CollectionUtils.isEmpty(stillMissing)) {
            row.setNeedsReview(0);
        }
        row.setOperatedBy(currentUser());
        row.setOperatedAt(LocalDateTime.now());
        rowMapper.updateById(row);
    }

    /**
     * 删除任务及关联的任务记录（结果行、回调日志）。
     * 仅允许终态（completed/failed/cancelled/callback_timeout）或待处理（pending）任务删除，
     * 正在提交/处理中的任务需先取消，避免与外部服务状态不一致。
     */
    @Transactional(rollbackFor = Exception.class)
    public void deleteTask(String taskId) {
        ExternalCleanTaskEntity task = requireTask(taskId);
        String status = task.getStatus();
        boolean deletable = "completed".equals(status) || "failed".equals(status)
                || "cancelled".equals(status) || "callback_timeout".equals(status)
                || "pending".equals(status);
        if (!deletable) {
            throw new IllegalStateException("当前状态(" + status + ")不允许删除，请先取消任务");
        }
        // 删除关联行记录
        rowMapper.delete(new LambdaQueryWrapper<ExternalCleanTaskRowEntity>()
                .eq(ExternalCleanTaskRowEntity::getTaskId, taskId));
        // 删除关联回调日志
        callbackLogMapper.delete(new LambdaQueryWrapper<ExternalCleanCallbackLogEntity>()
                .eq(ExternalCleanCallbackLogEntity::getTaskId, taskId));
        // 删除任务
        taskMapper.delete(new LambdaQueryWrapper<ExternalCleanTaskEntity>()
                .eq(ExternalCleanTaskEntity::getTaskId, taskId));
    }

    // ===================== 取消 / 重试 =====================

    @Transactional(rollbackFor = Exception.class)
    public void cancelTask(String taskId) {
        ExternalCleanTaskEntity task = requireTask(taskId);
        if (!"processing".equals(task.getStatus()) && !"pending".equals(task.getStatus())
                && !"submitting".equals(task.getStatus()) && !"queued".equals(task.getStatus())) {
            throw new IllegalStateException("当前状态(" + task.getStatus() + ")不允许取消");
        }
        if ("async".equals(task.getMode())) {
            apiClient.cancelTask(taskId);
        }
        task.setStatus("cancelled");
        task.setCompletedAt(LocalDateTime.now());
        taskMapper.updateById(task);
    }

    @Transactional(rollbackFor = Exception.class)
    public ExternalCleanTaskEntity retryTask(String taskId) {
        ExternalCleanTaskEntity task = requireTask(taskId);
        if (!"failed".equals(task.getStatus()) && !"callback_timeout".equals(task.getStatus())) {
            throw new IllegalStateException("仅 failed / callback_timeout 状态可重试");
        }
        // 重置行结果
        List<ExternalCleanTaskRowEntity> rows = rowMapper.selectList(
                new LambdaQueryWrapper<ExternalCleanTaskRowEntity>().eq(ExternalCleanTaskRowEntity::getTaskId, taskId));
        for (ExternalCleanTaskRowEntity row : rows) {
            if (!"accepted".equals(row.getRowStatus()) && !"corrected".equals(row.getRowStatus())) {
                row.setRowStatus("pending");
                row.setCategoryCode(null);
                row.setExtractedAttrsJson(null);
                row.setNeedsReview(0);
                rowMapper.updateById(row);
            }
        }
        task.setStatus("submitting");
        task.setErrorMessage(null);
        task.setCallbackReceivedAt(null);
        task.setCompletedAt(null);
        task.setRetryCount((task.getRetryCount() == null ? 0 : task.getRetryCount()) + 1);
        taskMapper.updateById(task);

        // 重新构建 rows 并再次提交
        List<Map<String, String>> columnsList = new ArrayList<>();
        for (ExternalCleanTaskRowEntity r : rows) {
            if (r.getRequestColumnsJson() != null) {
                @SuppressWarnings("unchecked")
                Map<String, String> cols = JSON.parseObject(r.getRequestColumnsJson(), Map.class);
                columnsList.add(cols);
            }
        }

        CleanOptions options = JSON.parseObject(task.getOptionsJson(), CleanOptions.class);
        boolean submitted;
        if ("sync".equals(task.getMode())) {
            try {
                CallbackPayload payload = apiClient.submitSync(task.getTaskId(), columnsList, options);
                task.setStatus("processing");
                applyResults(task, payload, null);
                submitted = true;
            } catch (Exception e) {
                task.setStatus("failed");
                task.setErrorMessage("重试同步失败: " + e.getMessage());
                submitted = false;
            }
        } else {
            submitted = apiClient.submitAsync(taskId, task.getCallbackUrl(), columnsList, options);
            task.setStatus(submitted ? "processing" : "failed");
            if (!submitted) task.setErrorMessage("重试提交外部服务未接受");
        }
        taskMapper.updateById(task);
        return task;
    }

    // ===================== 暂停 / 继续 =====================

    /**
     * 暂停清洗任务：调用外部服务暂停接口，仅 processing 状态可暂停，本地状态同步置为 paused。
     */
    @Transactional(rollbackFor = Exception.class)
    public void pauseTask(String taskId) {
        ExternalCleanTaskEntity task = requireTask(taskId);
        if (!"processing".equals(task.getStatus())) {
            throw new IllegalStateException("仅 processing 状态可暂停，当前状态(" + task.getStatus() + ")");
        }
        if ("async".equals(task.getMode())) {
            if (!apiClient.pauseTask(taskId)) {
                throw new IllegalStateException("调用外部暂停接口失败");
            }
        }
        task.setStatus("paused");
        taskMapper.updateById(task);
    }

    /**
     * 继续清洗任务：调用外部服务继续接口，仅 paused 状态可继续，本地状态同步恢复为 processing。
     */
    @Transactional(rollbackFor = Exception.class)
    public void resumeTask(String taskId) {
        ExternalCleanTaskEntity task = requireTask(taskId);
        if (!"paused".equals(task.getStatus())) {
            throw new IllegalStateException("仅 paused 状态可继续，当前状态(" + task.getStatus() + ")");
        }
        if ("async".equals(task.getMode())) {
            if (!apiClient.resumeTask(taskId)) {
                throw new IllegalStateException("调用外部继续接口失败");
            }
        }
        task.setStatus("processing");
        taskMapper.updateById(task);
    }

    // ===================== 兜底轮询 =====================

    /**
     * 由调度器定时调用：对 processing 且超时未收到回调的任务，主动查询外部服务拉取结果。
     */
    @Transactional(rollbackFor = Exception.class)
    public void pollFallback() {
        List<ExternalCleanTaskEntity> tasks = taskMapper.selectProcessingWithoutCallback();
        if (tasks.isEmpty()) return;
        LocalDateTime threshold = LocalDateTime.now().minusMinutes(properties.getCallbackTimeoutMinutes());
        for (ExternalCleanTaskEntity task : tasks) {
            if (task.getSubmittedAt() == null || task.getSubmittedAt().isAfter(threshold)) {
                continue; // 尚未超时
            }
            try {
                CallbackPayload payload = apiClient.queryTask(task.getTaskId());
                if (payload != null) {
                    task.setStatus("callback_timeout");
                    taskMapper.updateById(task);
                    applyResults(task, payload, null);
                    log.info("兜底轮询拉取任务 {} 结果成功", task.getTaskId());
                } else {
                    // 仍查不到，标记超时待重试
                    task.setStatus("callback_timeout");
                    taskMapper.updateById(task);
                }
            } catch (Exception e) {
                log.warn("兜底轮询任务 {} 异常: {}", task.getTaskId(), e.getMessage());
            }
        }
    }

    // ===================== 工具 =====================

    private ExternalCleanTaskEntity requireTask(String taskId) {
        ExternalCleanTaskEntity task = getTask(taskId);
        if (task == null) throw new IllegalArgumentException("任务不存在: " + taskId);
        return task;
    }

    private ExternalCleanTaskRowEntity requireRow(String taskId, int rowIndex) {
        ExternalCleanTaskRowEntity row = rowMapper.selectOne(
                new LambdaQueryWrapper<ExternalCleanTaskRowEntity>()
                        .eq(ExternalCleanTaskRowEntity::getTaskId, taskId)
                        .eq(ExternalCleanTaskRowEntity::getRowIndex, rowIndex));
        if (row == null) throw new IllegalArgumentException("任务行不存在: " + taskId + "#" + rowIndex);
        return row;
    }

    private String currentUser() {
        try {
            return UserContext.getUsername();
        } catch (Exception e) {
            return "system";
        }
    }

    /**
     * 是否为终态状态（无需再向外部查询进展）
     */
    private boolean isTerminalStatus(String status) {
        return "completed".equals(status) || "failed".equals(status)
                || "cancelled".equals(status) || "callback_timeout".equals(status);
    }

    private static String sha256Hex(String input) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return String.valueOf(input.hashCode());
        }
    }
}
