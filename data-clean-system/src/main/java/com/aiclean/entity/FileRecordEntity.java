package com.aiclean.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 文件管理记录表：记录上传到知识库文件库(/opt/kb)的文件信息，
 * 包括上传时间、导入知识库状态、备注、更新时间等。
 */
@Data
@EqualsAndHashCode(callSuper = false)
@TableName("file_record")
public class FileRecordEntity extends BaseEntity implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 文件名（含扩展名） */
    @TableField("file_name")
    private String fileName;

    /** 相对 KB_FILE_DIR 的路径，如 category/property/a.docx（唯一） */
    @TableField("relative_path")
    private String relativePath;

    /** 服务器绝对路径 */
    @TableField("absolute_path")
    private String absolutePath;

    /** 文件字节大小 */
    @TableField("file_size")
    private Long fileSize;

    /** SHA-256 文件指纹，用于判重/变更检测 */
    @TableField("file_hash")
    private String fileHash;

    /** 扩展名，如 docx */
    @TableField("file_type")
    private String fileType;

    /** 导入知识库状态：NOT_IMPORTED(未录入) / IMPORTED(已录入) */
    @TableField("import_status")
    private String importStatus;

    /** 备注 */
    @TableField("remark")
    private String remark;

    /** 首次上传时间 */
    @TableField("uploaded_at")
    private LocalDateTime uploadedAt;

    /** 导入知识库时间（外部系统录入完成后回填） */
    @TableField("imported_at")
    private LocalDateTime importedAt;

    public static final String IMPORT_STATUS_IMPORTED = "IMPORTED";
    public static final String IMPORT_STATUS_NOT_IMPORTED = "NOT_IMPORTED";
}
