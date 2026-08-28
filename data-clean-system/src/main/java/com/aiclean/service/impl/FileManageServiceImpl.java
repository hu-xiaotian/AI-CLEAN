package com.aiclean.service.impl;

import com.aiclean.config.FileStorageConfig;
import com.aiclean.entity.FileRecordEntity;
import com.aiclean.knowledgebase.client.KnowledgeBaseApiClient;
import com.aiclean.knowledgebase.config.KnowledgeBaseProperties;
import com.aiclean.mapper.FileRecordMapper;
import com.aiclean.service.FileManageService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 文件管理模块服务实现
 */
@Slf4j
@Service
public class FileManageServiceImpl implements FileManageService {

    @Autowired
    private FileRecordMapper fileRecordMapper;

    @Autowired
    private FileStorageConfig fileStorageConfig;

    @Autowired(required = false)
    private KnowledgeBaseApiClient knowledgeBaseApiClient;

    @Autowired(required = false)
    private KnowledgeBaseProperties knowledgeBaseProperties;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public FileRecordEntity upload(MultipartFile file, String relativePath, String remark, String username) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("上传文件不能为空");
        }
        // 大小校验
        if (file.getSize() > fileStorageConfig.getMaxSize()) {
            throw new IllegalArgumentException("文件大小超过限制，最大允许 " + (fileStorageConfig.getMaxSize() / 1024 / 1024) + "MB");
        }
        // 确定相对路径
        String relPath = (relativePath == null || relativePath.trim().isEmpty())
                ? file.getOriginalFilename()
                : relativePath.trim();
        if (!StringUtils.hasText(relPath)) {
            throw new IllegalArgumentException("文件路径不能为空");
        }
        relPath = normalize(relPath);
        // 后缀白名单校验
        String ext = getExtension(relPath);
        if (!fileStorageConfig.getAllowedExtensionList().contains(ext.toLowerCase())) {
            throw new IllegalArgumentException("不支持的文件类型: " + ext);
        }
        // 物理落盘（受控路径，防穿越）
        File target = fileStorageConfig.resolve(relPath);
        File parent = target.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        try {
            file.transferTo(target);
        } catch (IOException e) {
            throw new RuntimeException("文件保存失败: " + e.getMessage(), e);
        }

        String hash = sha256(file);
        long size = target.length();
        LocalDateTime now = LocalDateTime.now();

        LambdaQueryWrapper<FileRecordEntity> qw = new LambdaQueryWrapper<>();
        qw.eq(FileRecordEntity::getRelativePath, relPath);
        FileRecordEntity exist = fileRecordMapper.selectOne(qw);

        if (exist == null) {
            // 新增
            FileRecordEntity entity = new FileRecordEntity();
            entity.setFileName(new File(relPath).getName());
            entity.setRelativePath(relPath);
            entity.setAbsolutePath(target.getAbsolutePath());
            entity.setFileSize(size);
            entity.setFileHash(hash);
            entity.setFileType(ext);
            entity.setImportStatus(FileRecordEntity.IMPORT_STATUS_NOT_IMPORTED);
            entity.setRemark(remark);
            entity.setUploadedAt(now);
            entity.setCreatedBy(username);
            entity.setUpdatedBy(username);
            fileRecordMapper.insert(entity);
            return entity;
        } else {
            // 同名覆盖：保留首次上传时间，重置导入状态，刷新文件信息与更新时间
            exist.setFileName(new File(relPath).getName());
            exist.setAbsolutePath(target.getAbsolutePath());
            exist.setFileSize(size);
            exist.setFileHash(hash);
            exist.setFileType(ext);
            exist.setImportStatus(FileRecordEntity.IMPORT_STATUS_NOT_IMPORTED);
            exist.setImportedAt(null);
            if (remark != null) {
                exist.setRemark(remark);
            }
            exist.setUpdatedBy(username);
            fileRecordMapper.updateById(exist);
            return exist;
        }
    }

    @Override
    public Page<FileRecordEntity> list(String fileName, String importStatus, Long current, Long size) {
        Page<FileRecordEntity> page = new Page<>(current == null ? 1 : current, size == null ? 20 : size);
        LambdaQueryWrapper<FileRecordEntity> qw = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(fileName)) {
            qw.like(FileRecordEntity::getFileName, fileName);
        }
        if (StringUtils.hasText(importStatus)) {
            qw.eq(FileRecordEntity::getImportStatus, importStatus);
        }
        qw.orderByDesc(FileRecordEntity::getUploadedAt);
        return fileRecordMapper.selectPage(page, qw);
    }

    @Override
    public FileRecordEntity detail(Long id) {
        return fileRecordMapper.selectById(id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public FileRecordEntity updateRemark(Long id, String remark) {
        FileRecordEntity entity = fileRecordMapper.selectById(id);
        if (entity == null) {
            throw new IllegalArgumentException("文件记录不存在: " + id);
        }
        entity.setRemark(remark);
        fileRecordMapper.updateById(entity);
        return entity;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id, boolean deletePhysical) {
        FileRecordEntity entity = fileRecordMapper.selectById(id);
        if (entity == null) {
            throw new IllegalArgumentException("文件记录不存在: " + id);
        }
        if (deletePhysical) {
            try {
                File f = fileStorageConfig.resolve(entity.getRelativePath());
                Files.deleteIfExists(f.toPath());
            } catch (IOException e) {
                log.warn("删除物理文件失败: {}", e.getMessage());
            }
        }
        fileRecordMapper.deleteById(id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public FileRecordEntity updateImportStatus(Long id, boolean imported) {
        FileRecordEntity entity = fileRecordMapper.selectById(id);
        if (entity == null) {
            throw new IllegalArgumentException("文件记录不存在: " + id);
        }
        entity.setImportStatus(imported ? FileRecordEntity.IMPORT_STATUS_IMPORTED
                : FileRecordEntity.IMPORT_STATUS_NOT_IMPORTED);
        entity.setImportedAt(imported ? LocalDateTime.now() : null);
        fileRecordMapper.updateById(entity);
        return entity;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean ingest(Long id) {
        if (knowledgeBaseProperties == null || !knowledgeBaseProperties.isEnabled()
                || knowledgeBaseApiClient == null) {
            throw new IllegalStateException("知识库入库未启用（app.knowledge-base.enabled=false 或配置缺失）");
        }
        FileRecordEntity entity = fileRecordMapper.selectById(id);
        if (entity == null) {
            throw new IllegalArgumentException("文件记录不存在: " + id);
        }
        // 调用 api-design.md 4.4.4 接口，按 relative_path 入库
        boolean success = knowledgeBaseApiClient.ingest(entity.getRelativePath());
        if (success) {
            // 入库成功则自动翻转导入状态为"已录入"，刷新导入时间
            entity.setImportStatus(FileRecordEntity.IMPORT_STATUS_IMPORTED);
            entity.setImportedAt(LocalDateTime.now());
            fileRecordMapper.updateById(entity);
        }
        return success;
    }

    @Override
    public File resolvePhysical(Long id) {
        FileRecordEntity entity = fileRecordMapper.selectById(id);
        if (entity == null) {
            throw new IllegalArgumentException("文件记录不存在: " + id);
        }
        File f = fileStorageConfig.resolve(entity.getRelativePath());
        if (!f.exists()) {
            throw new IllegalArgumentException("物理文件不存在: " + entity.getRelativePath());
        }
        return f;
    }

    @Override
    public List<FileRecordEntity> all() {
        LambdaQueryWrapper<FileRecordEntity> qw = new LambdaQueryWrapper<>();
        qw.orderByDesc(FileRecordEntity::getUploadedAt);
        return fileRecordMapper.selectList(qw);
    }

    // ===== 工具方法 =====

    private String normalize(String path) {
        // 去掉开头的 / 与反斜杠，统一为正向相对路径
        return path.replace('\\', '/').replaceAll("^/+", "");
    }

    private String getExtension(String path) {
        int idx = path.lastIndexOf('.');
        if (idx < 0 || idx == path.length() - 1) {
            return "";
        }
        return path.substring(idx + 1);
    }

    private String sha256(MultipartFile file) {
        try (InputStream in = file.getInputStream()) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[8192];
            int len;
            while ((len = in.read(buf)) != -1) {
                digest.update(buf, 0, len);
            }
            byte[] hash = digest.digest();
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }
}
