package com.aiclean.externalclean.dto;

import lombok.Data;

import java.util.List;

/**
 * 提交外部清洗任务请求
 */
@Data
public class SubmitExternalCleanTaskRequest {

    /** 来源数据文件ID（temp_data_title.id） */
    private Long tempDataTitleId;

    /** 指定要清洗的行ID列表；为空表示清洗该文件全部行 */
    private List<Long> rowIds;

    /** 清洗选项（对应 api-design.md 的 CleanOptions） */
    private CleanOptions options;

    /**
     * 提交模式：sync 同步 / async 异步 / 不传则由服务端按行数自动判定
     */
    private String mode;
}
