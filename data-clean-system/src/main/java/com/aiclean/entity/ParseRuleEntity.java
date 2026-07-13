package com.aiclean.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 解析规则持久化实体类
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("parse_rule")
public class ParseRuleEntity extends BaseEntity {

    private String ruleName;
    private String description;
    private String keyValueSeparator;
    private String itemSeparator;
    private String escapeChar;
    private Boolean trimSpaces;
    private Boolean ignoreEmptyItems;
    private Boolean isActive;

    /**
     * 转换为模型对象
     */
    public com.aiclean.model.ParseRule toParseRule() {
        com.aiclean.model.ParseRule rule = new com.aiclean.model.ParseRule();
        rule.setId(getId());
        rule.setRuleName(ruleName);
        rule.setDescription(description);
        rule.setKeyValueSeparator(keyValueSeparator != null ? keyValueSeparator : " ");
        rule.setItemSeparator(itemSeparator != null ? itemSeparator : ";");
        rule.setEscapeChar(escapeChar != null ? escapeChar : "");
        rule.setTrimSpaces(trimSpaces != null ? trimSpaces : true);
        rule.setIgnoreEmptyItems(ignoreEmptyItems != null ? ignoreEmptyItems : true);
        rule.setIsActive(isActive != null ? isActive : true);
        rule.setCreatedBy(getCreatedBy());
        return rule;
    }
}
