package com.aiclean.entity;

import com.aiclean.entity.enums.DataStatus;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 原始数据实体类
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("temp_data")
public class TempDataEntity extends BaseEntity {
    
    /**
     * 所属表头ID
     */
    private Long tempDataTitleId;
    
    /**
     * 在文件中的行号（从2开始）
     */
    private Integer rowIndex;
    
    /**
     * 第1列数据
     */
    private String col1;
    
    /**
     * 第2列数据
     */
    private String col2;
    
    /**
     * 第3列数据
     */
    private String col3;
    
    /**
     * 第4列数据
     */
    private String col4;
    
    /**
     * 第5列数据
     */
    private String col5;
    
    /**
     * 第6列数据
     */
    private String col6;
    
    /**
     * 第7列数据
     */
    private String col7;
    
    /**
     * 第8列数据
     */
    private String col8;
    
    /**
     * 第9列数据
     */
    private String col9;
    
    /**
     * 第10列数据
     */
    private String col10;
    
    /**
     * 处理状态
     */
    private DataStatus status;
    
    /**
     * 错误信息
     */
    private String errorMsg;
    
    /**
     * 匹配的分类ID（非数据库字段）
     */
    @TableField(exist = false)
    private Long matchedCategoryId;
    
    /**
     * 匹配的分类名称（非数据库字段）
     */
    @TableField(exist = false)
    private String matchedCategoryName;
    
    /**
     * 提取的属性数据（非数据库字段）
     */
    @TableField(exist = false)
    private String extraDataJson;
    
    /**
     * 全描述字段（非数据库字段）
     */
    @TableField(exist = false)
    private String fullDescription;
    
    /**
     * 获取列数据
     */
    public String getColData(int index) {
        switch (index) {
            case 1: return col1;
            case 2: return col2;
            case 3: return col3;
            case 4: return col4;
            case 5: return col5;
            case 6: return col6;
            case 7: return col7;
            case 8: return col8;
            case 9: return col9;
            case 10: return col10;
            default: return "";
        }
    }
    
    /**
     * 设置列数据
     */
    public void setColData(int index, String data) {
        switch (index) {
            case 1: col1 = data; break;
            case 2: col2 = data; break;
            case 3: col3 = data; break;
            case 4: col4 = data; break;
            case 5: col5 = data; break;
            case 6: col6 = data; break;
            case 7: col7 = data; break;
            case 8: col8 = data; break;
            case 9: col9 = data; break;
            case 10: col10 = data; break;
        }
    }
    
    /**
     * 获取列数据数组
     */
    public String[] getColDataArray() {
        return new String[]{col1, col2, col3, col4, col5, col6, col7, col8, col9, col10};
    }
}