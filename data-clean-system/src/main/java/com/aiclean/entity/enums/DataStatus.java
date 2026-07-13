package com.aiclean.entity.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;

/**
 * 数据状态枚举
 */
@Getter
public enum DataStatus {
    
    DRAFT("draft", "草稿"),
    NEEDS_REVIEW("needs_review", "待审核"),
    REVIEWING("reviewing", "审核中"),
    APPROVED("approved", "审核通过"),
    REJECTED("rejected", "审核驳回"),
    MODIFIED("modified", "已修改"),
    EXPORT_READY("export_ready", "可导出"),
    PROCESSED("processed", "已处理"),
    COMPLETED("completed", "已完成");
    
    @EnumValue
    @JsonValue
    private final String code;
    private final String description;
    
    DataStatus(String code, String description) {
        this.code = code;
        this.description = description;
    }
    
    public static DataStatus fromCode(String code) {
        for (DataStatus status : values()) {
            if (status.code.equals(code)) {
                return status;
            }
        }
        return null;
    }
    
    public static boolean isExportable(DataStatus status) {
        return status == EXPORT_READY || status == APPROVED;
    }
    
    public static boolean needsReview(DataStatus status) {
        return status == NEEDS_REVIEW || status == REVIEWING;
    }
    
    public static boolean isFinal(DataStatus status) {
        return status == APPROVED || status == REJECTED || status == MODIFIED;
    }
}