package com.aiclean.entity.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;

/**
 * 任务优先级枚举
 */
@Getter
public enum TaskPriority {
    
    LOW("low", "低"),
    MEDIUM("medium", "中"),
    HIGH("high", "高"),
    URGENT("urgent", "紧急");
    
    @EnumValue
    @JsonValue
    private final String code;
    private final String description;
    
    TaskPriority(String code, String description) {
        this.code = code;
        this.description = description;
    }
    
    public static TaskPriority fromCode(String code) {
        for (TaskPriority priority : values()) {
            if (priority.code.equals(code)) {
                return priority;
            }
        }
        return MEDIUM;
    }
    
    public int getWeight() {
        switch (this) {
            case LOW: return 1;
            case MEDIUM: return 3;
            case HIGH: return 5;
            case URGENT: return 10;
            default: return 3;
        }
    }
}