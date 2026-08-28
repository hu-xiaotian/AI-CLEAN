package com.aiclean.service;

import com.aiclean.entity.FileRecordEntity;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 文件管理模块服务接口
 */
public interface FileManageService {

    /**
     * 上传文件。同名(relativePath)上传则物理覆盖，并重置导入状态为未录入、刷新更新时间。
     *
     * @param file         文件
     * @param relativePath 相对 KB_FILE_DIR 的路径；为空则用原文件名
     * @param remark       备注
     * @param username     当前操作人
     * @return 文件记录
     */
    FileRecordEntity upload(MultipartFile file, String relativePath, String remark, String username);

    /**
     * 分页/条件列表查询
     */
    Page<FileRecordEntity> list(String fileName, String importStatus, Long current, Long size);

    /**
     * 详情
     */
    FileRecordEntity detail(Long id);

    /**
     * 更新备注
     */
    FileRecordEntity updateRemark(Long id, String remark);

    /**
     * 删除记录（可选是否删除物理文件）
     */
    void delete(Long id, boolean deletePhysical);

    /**
     * 翻转导入状态（外部知识库入库完成后调用）。
     *
     * @param imported true=已录入，false=未录入
     */
    FileRecordEntity updateImportStatus(Long id, boolean imported);

    /**
     * 触发入库：调用 api-design.md 4.4.4 接口把该文件导入知识库，
     * 返回入库成功则自动把导入状态置为"已录入"。
     *
     * @return 入库是否成功
     */
    boolean ingest(Long id);

    /**
     * 读取物理文件，供下载/预览共用
     */
    java.io.File resolvePhysical(Long id);

    /**
     * 所有记录（用于外部系统拉取 relative_path 入库）
     */
    List<FileRecordEntity> all();
}
