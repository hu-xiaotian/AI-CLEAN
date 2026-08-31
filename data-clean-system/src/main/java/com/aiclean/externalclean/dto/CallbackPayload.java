package com.aiclean.externalclean.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 外部服务回调载荷（与 api-design.md 第5.1节回调协议对齐）
 * 上游回调使用 snake_case 字段名（如 task_id），故声明 SnakeCase 命名策略。
 */
@Data
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonIgnoreProperties(ignoreUnknown = true)
public class CallbackPayload {

    private String taskId;
    private String status; // completed / failed / cancelled
    private Stats stats;
    private String error;

    /** 单页/全部清洗结果；分页回调时仅含该页数据 */
    private List<CleanResultItem> results;

    @Data
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Stats {
        private Integer totalRows;
        /** 已分类行数（v1.3 新增） */
        private Integer classifiedRows;
        private Integer processedRows;
        private Integer highConfidence;
        private Integer mediumConfidence;
        private Integer lowConfidence;
        /** 置信度累加值（v1.3 新增），用于计算平均置信度 */
        private Double confidenceSum;
        private Double estimatedAccuracy;
    }

    @Data
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class CleanResultItem {
        private Integer index;
        private String categoryCode;
        private String categoryName;
        private String categoryPath;
        private Double confidence;
        private Double categoryConfidence;
        private Map<String, String> extractedAttrs;
        private List<String> missingAttrs;
        private Boolean needsReview;
        private String reviewReason;

        /** 分类判定依据的引用片段（v1.3 新增） */
        private List<Object> categoryCitations;

        /** 属性提取的引用片段，key=属性名（v1.3 新增） */
        private Map<String, Object> attrCitations;
    }
}
