package com.aiclean.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 清洗结果数据实体类
 * 按标准字段表头填充的最终数据
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("result_data")
public class ResultDataEntity extends BaseEntity {

    private Long standardTitleId;
    private Long tempDataId;
    private Long cleanedDataId;

    private String col1;
    private String col2;
    private String col3;
    private String col4;
    private String col5;
    private String col6;
    private String col7;
    private String col8;
    private String col9;
    private String col10;
    private String col11;
    private String col12;
    private String col13;
    private String col14;
    private String col15;
    private String col16;
    private String col17;
    private String col18;
    private String col19;
    private String col20;

    private String status;
    private String reviewComment;
    private String reviewedBy;
    private LocalDateTime reviewedAt;

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
            case 11: return col11;
            case 12: return col12;
            case 13: return col13;
            case 14: return col14;
            case 15: return col15;
            case 16: return col16;
            case 17: return col17;
            case 18: return col18;
            case 19: return col19;
            case 20: return col20;
            default: return "";
        }
    }

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
            case 11: col11 = data; break;
            case 12: col12 = data; break;
            case 13: col13 = data; break;
            case 14: col14 = data; break;
            case 15: col15 = data; break;
            case 16: col16 = data; break;
            case 17: col17 = data; break;
            case 18: col18 = data; break;
            case 19: col19 = data; break;
            case 20: col20 = data; break;
        }
    }
}
