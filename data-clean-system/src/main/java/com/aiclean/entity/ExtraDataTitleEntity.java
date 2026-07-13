package com.aiclean.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 补充数据表头实体类
 * 存储从全描述中提取的额外属性列名
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("extra_data_title")
public class ExtraDataTitleEntity extends BaseEntity {

    private Long tempDataTitleId;
    private Long parseRuleId;

    private String col1Title;
    private String col2Title;
    private String col3Title;
    private String col4Title;
    private String col5Title;
    private String col6Title;
    private String col7Title;
    private String col8Title;
    private String col9Title;
    private String col10Title;
    private String col11Title;
    private String col12Title;
    private String col13Title;
    private String col14Title;
    private String col15Title;
    private String col16Title;
    private String col17Title;
    private String col18Title;
    private String col19Title;
    private String col20Title;

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
            case 11: return col11Title;
            case 12: return col12Title;
            case 13: return col13Title;
            case 14: return col14Title;
            case 15: return col15Title;
            case 16: return col16Title;
            case 17: return col17Title;
            case 18: return col18Title;
            case 19: return col19Title;
            case 20: return col20Title;
            default: return null;
        }
    }

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
            case 11: col11Title = title; break;
            case 12: col12Title = title; break;
            case 13: col13Title = title; break;
            case 14: col14Title = title; break;
            case 15: col15Title = title; break;
            case 16: col16Title = title; break;
            case 17: col17Title = title; break;
            case 18: col18Title = title; break;
            case 19: col19Title = title; break;
            case 20: col20Title = title; break;
        }
    }
}
