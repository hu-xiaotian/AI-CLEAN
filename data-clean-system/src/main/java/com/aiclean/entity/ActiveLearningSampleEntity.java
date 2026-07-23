package com.aiclean.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 主动学习样本实体。
 * 沉淀"低置信 / 人工修正"样本，用于后续训练、微调或规则优化，形成自学习闭环。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("active_learning_sample")
public class ActiveLearningSampleEntity extends BaseEntity {

    /**
     * 样本类型：LOW_CONFIDENCE(低置信) / CORRECTION(人工修正)
     */
    private String sampleType;

    /**
     * 关联清洗数据ID (cleaned_data.id)
     */
    private Long entityId;

    /**
     * 原始属性拆分列文本 / 物料描述
     */
    private String sourceText;

    /**
     * 原始(错误)分类名称
     */
    private String sourceCategoryName;

    /**
     * 原始(错误)分类编码
     */
    private String sourceCategoryCode;

    /**
     * 修正后/推荐的标准分类ID
     */
    private Long targetCategoryId;

    /**
     * 修正后/推荐的标准分类编码
     */
    private String targetCategoryCode;

    /**
     * 修正后/推荐的标准分类名称
     */
    private String targetCategoryName;

    /**
     * 匹配置信度
     */
    private Double confidence;

    /**
     * 质量评分
     */
    private Double score;

    /**
     * 说明
     */
    private String reason;

    /**
     * 状态：pending / used
     */
    private String status;
}
