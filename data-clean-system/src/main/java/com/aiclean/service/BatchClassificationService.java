package com.aiclean.service;

import com.aiclean.entity.CleanedDataEntity;

import java.util.List;
import java.util.function.Consumer;

/**
 * 批次分类服务（供主清洗流程内部使用）。
 * <p>
 * 清洗固定启用 AI 智能分类时，本服务把一整批物料一次性发给大模型，
 * 大模型返回 JSON 数组结果（分类 + 理由 + 评分），服务解析后按清洗数据 id 批量回写 cleaned_data，
 * 并支持多线程并发处理多个批次，大幅降低 AI 调用次数、提升分类速度。
 */
public interface BatchClassificationService {

    /**
     * 对指定数据文件执行批次分类并返回更新后的实体列表。
     * 供主清洗流程等内部使用：批次分类回写分类、评分与理由（可选是否回写状态），
     * 返回实体可用于后续统一的阈值自适应打标。分类固定走 AI（向量召回 top-k 候选 + 大模型），
     * 不再有规则分类开关。
     *
     * @param titleId      数据文件表头ID
     * @param batchSize    自定义批次大小（为 null 时取默认配置）
     * @param updateStatus 是否由批次分类回写状态（true 时按 thresholdReview 打标；false 时仅回写分类与评分）
     * @return 更新后的清洗数据实体列表（含最新分类与评分）
     */
    List<CleanedDataEntity> batchClassifyEntities(Long titleId, Integer batchSize, boolean updateStatus);

    /**
     * 同 {@link #batchClassifyEntities(Long, Integer, boolean)}，但支持传入阶段内进度回调。
     * 回调参数 phaseProgress 取值 [0,1]，表示该批次分类阶段内部的完成进度（按已完成的批次数/总批次数估算），
     * 供主清洗流程把「大模型分类」这一耗时阶段纳入整体清洗进度，避免进度在入库完成后长期卡在 100% 不动。
     *
     * @param titleId        数据文件表头ID
     * @param batchSize      自定义批次大小（为 null 时取默认配置）
     * @param updateStatus   是否由批次分类回写状态
     * @param phaseProgress  阶段内进度回调（可为 null，为 null 时不回调）
     * @return 更新后的清洗数据实体列表（含最新分类与评分）
     */
    List<CleanedDataEntity> batchClassifyEntities(Long titleId, Integer batchSize, boolean updateStatus,
                                                 Consumer<Double> phaseProgress);
}
