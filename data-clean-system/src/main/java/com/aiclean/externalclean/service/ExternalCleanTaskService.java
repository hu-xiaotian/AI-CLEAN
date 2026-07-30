package com.aiclean.externalclean.service;

import com.aiclean.common.UserContext;
import com.aiclean.externalclean.client.ExternalCleanApiClient;
import com.aiclean.externalclean.config.ExternalCleanProperties;
import com.aiclean.externalclean.dto.CallbackPayload;
import com.aiclean.externalclean.dto.CleanOptions;
import com.aiclean.externalclean.dto.SubmitExternalCleanTaskRequest;
import com.aiclean.externalclean.dto.TaskRowCorrectRequest;
import com.aiclean.externalclean.entity.ExternalCleanCallbackLogEntity;
import com.aiclean.externalclean.entity.ExternalCleanTaskEntity;
import com.aiclean.externalclean.entity.ExternalCleanTaskRowEntity;
import com.aiclean.externalclean.mapper.ExternalCleanCallbackLogMapper;
import com.aiclean.externalclean.mapper.ExternalCleanTaskMapper;
import com.aiclean.externalclean.mapper.ExternalCleanTaskRowMapper;
import com.aiclean.mapper.TempDataMapper;
import com.aiclean.mapper.TempDataTitleMapper;
import com.aiclean.entity.TempDataEntity;
import com.aiclean.entity.TempDataTitleEntity;
import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

    private final ExternalCleanProperties properties;
    private final ExternalCleanApiClient apiClient;
    private final ExternalCleanTaskMapper taskMapper;
    private final ExternalCleanTaskRowMapper rowMapper;
    private final ExternalCleanCallbackLogMapper callbackLogMapper;
    private final TempDataTitleMapper tempDataTitleMapper;
    private final TempDataMapper tempDataMapper;

    public ExternalCleanTaskService(ExternalCleanProperties properties,
                                    ExternalCleanApiClient apiClient,
                                    ExternalCleanTaskMapper taskMapper,
                                    ExternalCleanTaskRowMapper rowMapper,
                                    ExternalCleanCallbackLogMapper callbackLogMapper,
                                    TempDataTitleMapper tempDataTitleMapper,
                                    TempDataMapper tempDataMapper) {
        this.properties = properties;
        this.apiClient = apiClient;
        this.taskMapper = taskMapper;
        this.rowMapper = rowMapper;
        this.callbackLogMapper = callbackLogMapper;
        this.tempDataTitleMapper = tempDataTitleMapper;
        this.tempDataMapper = tempDataMapper;
    }

    // ===================== 提交任务 =====================

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

        // 1. 读取选定行，构建列数据快照
        List<TempDataEntity> tempRows = loadTempRows(title.getId(), request.getRowIds());
        if (tempRows.isEmpty()) {
            throw new IllegalArgumentException("未找到可清洗的数据行");
        }
        CleanOptions options = request.getOptions() == null ? new CleanOptions() : request.getOptions();

        // 2. 先插入任务占位，获取自增ID用于生成 task_id
        ExternalCleanTaskEntity task = new ExternalCleanTaskEntity();
        task.setTempDataTitleId(title.getId());
        task.setFileName(title.getFileName());
        task.setMode(tempRows.size() <= SYNC_MAX_ROWS ? "sync" : "async");
        task.setStatus("pending");
        task.setTotalRows(tempRows.size());
        task.setOptionsJson(JSON.toJSONString(options));
        task.setRetryCount(0);
        taskMapper.insert(task);

        String taskId = "task-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"))
                + "-" + task.getId();
        task.setTaskId(taskId);

        // 3. 写入行快照
        List<Map<String, String>> columnsList = new ArrayList<>(tempRows.size());
        for (TempDataEntity tr : tempRows) {
            Map<String, String> columns = buildColumns(title, tr);
            columnsList.add(columns);
            ExternalCleanTaskRowEntity row = new ExternalCleanTaskRowEntity();
            row.setTaskId(taskId);
            row.setRowIndex(columnsList.size());
            row.setTempDataId(tr.getId());
            row.setRequestColumnsJson(JSON.toJSONString(columns));
            row.setRowStatus("pending");
            rowMapper.insert(row);
        }

        // 4. 提交外部服务
        String callbackUrl = properties.getCallbackBaseUrl();
        if (cn.hutool.core.util.StrUtil.isNotBlank(callbackUrl)) {
            callbackUrl = (callbackUrl.endsWith("/") ? callbackUrl.substring(0, callbackUrl.length() - 1) : callbackUrl)
                    + "/api/internal/tasks/" + taskId + "/result";
        }
        task.setCallbackUrl(callbackUrl);
        task.setStatus("submitting");
        taskMapper.updateById(task);

        boolean submitted;
        if ("sync".equals(task.getMode())) {
            try {
                CallbackPayload payload = apiClient.submitSync(taskId, columnsList, options);
                task.setStatus("processing");
                task.setSubmittedAt(LocalDateTime.now());
                taskMapper.updateById(task);
                // 同步模式直接应用结果（无需回调）
                applyResults(task, payload, null);
                submitted = true;
            } catch (Exception e) {
                task.setStatus("failed");
                task.setErrorMessage("同步清洗失败: " + e.getMessage());
                taskMapper.updateById(task);
                submitted = false;
            }
        } else {
            submitted = apiClient.submitAsync(taskId, callbackUrl, columnsList, options);
            if (submitted) {
                task.setStatus("processing");
                task.setSubmittedAt(LocalDateTime.now());
            } else {
                task.setStatus("failed");
                task.setErrorMessage("外部服务未接受任务（返回非 202）");
            }
            taskMapper.updateById(task);
        }

        if (!submitted) {
            throw new RuntimeException(task.getErrorMessage());
        }
        return taskMapper.selectById(task.getId());
    }

    private List<TempDataEntity> loadTempRows(Long titleId, List<Long> rowIds) {
        if (CollectionUtils.isEmpty(rowIds)) {
            return tempDataMapper.selectList(
                    new LambdaQueryWrapper<TempDataEntity>().eq(TempDataEntity::getTempDataTitleId, titleId));
        }
        List<TempDataEntity> all = tempDataMapper.selectBatchIds(rowIds);
        return all.stream().filter(r -> titleId.equals(r.getTempDataTitleId())).collect(Collectors.toList());
    }

    /** 将 temp_data 行转为 列名->列值 的快照（与 api-design.md RawRow.columns 对齐） */
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

    // ===================== 查询 =====================

    public IPage<ExternalCleanTaskEntity> listTasks(int page, int size, String status) {
        LambdaQueryWrapper<ExternalCleanTaskEntity> q = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(status)) {
            q.eq(ExternalCleanTaskEntity::getStatus, status);
        }
        q.orderByDesc(ExternalCleanTaskEntity::getCreatedAt);
        return taskMapper.selectPage(new Page<>(page, size), q);
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

    // ===================== 回调 / 轮询结果处理 =====================

    /**
     * 处理外部回调（也用于轮询兜底）。幂等由 payload_digest 保证。
     * @return 处理结果: success/duplicate/invalid/error
     */
    @Transactional(rollbackFor = Exception.class)
    public String handleCallback(String taskId, Integer pageNo, Integer pageSize, String rawBody) {
        ExternalCleanTaskEntity task = getTask(taskId);
        if (task == null) {
            return "invalid";
        }
        String digest = sha256Hex(rawBody == null ? "" : rawBody);

        // 幂等去重
        long dup = callbackLogMapper.selectCount(new LambdaQueryWrapper<ExternalCleanCallbackLogEntity>()
                .eq(ExternalCleanCallbackLogEntity::getPayloadDigest, digest));
        if (dup > 0) {
            return "duplicate";
        }

        CallbackPayload payload;
        try {
            payload = JSON.parseObject(rawBody, CallbackPayload.class);
        } catch (Exception e) {
            log.error("解析回调报文失败 taskId={}", taskId, e);
            saveCallbackLog(taskId, pageNo, pageSize, digest, rawBody, "error", "报文解析失败: " + e.getMessage());
            return "error";
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

    /** 将回调/轮询结果写入任务与行表 */
    private void applyResults(ExternalCleanTaskEntity task, CallbackPayload payload, String digest) {
        if (payload == null) {
            return;
        }
        // 更新任务统计
        if (payload.getStats() != null) {
            CallbackPayload.Stats s = payload.getStats();
            task.setTotalRows(s.getTotalRows());
            task.setProcessedRows(s.getProcessedRows());
            task.setHighConfidence(s.getHighConfidence());
            task.setMediumConfidence(s.getMediumConfidence());
            task.setLowConfidence(s.getLowConfidence());
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
                row.setExtractedAttrsJson(item.getExtractedAttrs() == null ? null : JSON.toJSONString(item.getExtractedAttrs()));
                row.setMissingAttrsJson(item.getMissingAttrs() == null ? null : JSON.toJSONString(item.getMissingAttrs()));
                row.setNeedsReview(Boolean.TRUE.equals(item.getNeedsReview()) ? 1 : 0);
                row.setReviewReason(item.getReviewReason());
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

    // ===================== 取消 / 重试 =====================

    @Transactional(rollbackFor = Exception.class)
    public void cancelTask(String taskId) {
        ExternalCleanTaskEntity task = requireTask(taskId);
        if (!"processing".equals(task.getStatus()) && !"pending".equals(task.getStatus()) && !"submitting".equals(task.getStatus())) {
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
                CallbackPayload payload = apiClient.submitSync(taskId, columnsList, options);
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
