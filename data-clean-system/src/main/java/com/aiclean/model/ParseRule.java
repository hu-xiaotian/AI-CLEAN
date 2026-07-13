package com.aiclean.model;

import lombok.Data;

/**
 * 解析规则配置
 */
@Data
public class ParseRule {
    
    /**
     * 规则ID
     */
    private Long id;
    
    /**
     * 规则名称
     */
    private String ruleName;
    
    /**
     * 规则描述
     */
    private String description;
    
    /**
     * 键值分隔符（默认空格）
     */
    private String keyValueSeparator = " ";
    
    /**
     * 条目分隔符（默认分号）
     */
    private String itemSeparator = ";";
    
    /**
     * 转义字符
     */
    private String escapeChar = "";
    
    /**
     * 是否去除首尾空格
     */
    private Boolean trimSpaces = true;
    
    /**
     * 是否忽略空条目
     */
    private Boolean ignoreEmptyItems = true;
    
    /**
     * 是否启用
     */
    private Boolean isActive = true;
    
    /**
     * 创建人
     */
    private String createdBy;
    
    /**
     * 解析全描述字段
     * @param fullDesc 全描述字符串
     * @return 解析后的键值对
     */
    public java.util.Map<String, String> parse(String fullDesc) {
        java.util.Map<String, String> result = new java.util.LinkedHashMap<>();
        
        if (fullDesc == null || fullDesc.isEmpty()) {
            return result;
        }
        
        // 分割条目
        String[] items = fullDesc.split(itemSeparator);
        
        for (String item : items) {
            // 去除首尾空格
            if (trimSpaces) {
                item = item.trim();
            }
            
            // 忽略空条目
            if (ignoreEmptyItems && item.isEmpty()) {
                continue;
            }
            
            // 处理转义字符
            if (!escapeChar.isEmpty()) {
                item = item.replace(escapeChar + itemSeparator, itemSeparator)
                          .replace(escapeChar + keyValueSeparator, keyValueSeparator);
            }
            
            // 分割键值
            if (item.contains(keyValueSeparator)) {
                String[] parts = item.split(keyValueSeparator, 2);
                String key = parts[0].trim();
                String value = parts.length > 1 ? parts[1].trim() : "";
                result.put(key, value);
            } else {
                // 如果没有分隔符，将整个条目作为值，键为空
                result.put("", item.trim());
            }
        }
        
        return result;
    }
    
    /**
     * 测试解析规则
     * @param testInput 测试输入
     * @return 解析结果
     */
    public ParseTestResult test(String testInput) {
        ParseTestResult result = new ParseTestResult();
        result.setInput(testInput);
        
        try {
            java.util.Map<String, String> parsed = parse(testInput);
            result.setParsedData(parsed);
            result.setSuccess(true);
            result.setMessage("解析成功，共解析出 " + parsed.size() + " 个属性");
        } catch (Exception e) {
            result.setSuccess(false);
            result.setMessage("解析失败: " + e.getMessage());
        }
        
        return result;
    }
    
    /**
     * 解析测试结果
     */
    @Data
    public static class ParseTestResult {
        private String input;
        private java.util.Map<String, String> parsedData;
        private boolean success;
        private String message;
    }
}