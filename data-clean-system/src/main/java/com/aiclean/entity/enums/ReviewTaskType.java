package com.aiclean.entity.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;

/**
 * 审核任务类型枚举
 */
@Getter
public enum ReviewTaskType {
    
    FIELD_MAPPING("field_mapping", "字段映射审核"),
    CATEGORY_MATCH("category_match", "分类匹配审核"),
    DATA_VALIDATION("data_validation", "数据验证审核");
    
    @EnumValue
    @JsonValue
    private final String code;
    private final String description;
    
    ReviewTaskType(String code, String description) {
        this.code = code;
        this.description = description;
    }
    
    public static ReviewTaskType fromCode(String code) {
        for (ReviewTaskType type : values()) {
            if (type.code.equals(code)) {
                return type;
            }
        }
        return null;
    }
}