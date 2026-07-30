package com.aiclean.externalclean.dto;

import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 外部服务回调载荷（与 api-design.md 第5.1节回调协议对齐）
 */
@Data
public class CallbackPayload {

    private String taskId;
    private String status; // completed / failed / cancelled
    private Stats stats;
    private String error;

    /** 单页/全部清洗结果；分页回调时仅含该页数据 */
    private List<CleanResultItem> results;

    @Data
    public static class Stats {
        private Integer totalRows;
        private Integer processedRows;
        private Integer highConfidence;
        private Integer mediumConfidence;
        private Integer lowConfidence;
        private Double estimatedAccuracy;
    }

    @Data
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
    }
}
