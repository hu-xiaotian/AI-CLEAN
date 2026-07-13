package com.aiclean.entity;

import com.aiclean.entity.enums.DataStatus;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 原始数据表头实体类
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("temp_data_title")
public class TempDataTitleEntity extends BaseEntity {
    
    /**
     * 文件名
     */
    private String fileName;
    
    /**
     * 上传时间
     */
    private String uploadTime;
    
    /**
     * 总数据行数
     */
    private Integer totalRows;
    
    /**
     * 处理状态
     */
    private DataStatus status;
    
    /**
     * 第1列标题
     */
    private String col1Title;
    
    /**
     * 第2列标题
     */
    private String col2Title;
    
    /**
     * 第3列标题
     */
    private String col3Title;
    
    /**
     * 第4列标题
     */
    private String col4Title;
    
    /**
     * 第5列标题
     */
    private String col5Title;
    
    /**
     * 第6列标题
     */
    private String col6Title;
    
    /**
     * 第7列标题
     */
    private String col7Title;
    
    /**
     * 第8列标题
     */
    private String col8Title;
    
    /**
     * 第9列标题
     */
    private String col9Title;
    
    /**
     * 第10列标题
     */
    private String col10Title;
    
    /**
     * 全描述列名称
     */
    private String fullDescCol;
    
    /**
     * 类别列名称
     */
    private String categoryCol;
    
    /**
     * 成功行数（非数据库字段）
     */
    @TableField(exist = false)
    private Integer successRows;
    
    /**
     * 失败行数（非数据库字段）
     */
    @TableField(exist = false)
    private Integer failedRows;
    
    /**
     * 处理进度（非数据库字段）
     */
    @TableField(exist = false)
    private Double progress;
    
    /**
     * 获取列标题
     */
    public String getColTitle(int index) {
        switch (index) {
            case 1: return col1Title;
            case 2: return col2Title;
            case 3: return col3Title;
            case 4: return col4Title;
            case 5: return col5Title;
            case 6: return col6Title;
            case 7: return col7Title;
            case 8: return col8Title;
            case 9: return col9Title;
            case 10: return col10Title;
            default: return "col" + index;
        }
    }
    
    /**
     * 设置列标题
     */
    public void setColTitle(int index, String title) {
        switch (index) {
            case 1: col1Title = title; break;
            case 2: col2Title = title; break;
            case 3: col3Title = title; break;
            case 4: col4Title = title; break;
            case 5: col5Title = title; break;
            case 6: col6Title = title; break;
            case 7: col7Title = title; break;
            case 8: col8Title = title; break;
            case 9: col9Title = title; break;
            case 10: col10Title = title; break;
        }
    }
}