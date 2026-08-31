package com.aiclean.controller;

import com.aiclean.common.R;
import com.aiclean.common.UserContext;
import com.aiclean.entity.FileRecordEntity;
import com.aiclean.knowledgebase.client.KnowledgeBaseApiClient;
import com.aiclean.service.FileManageService;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.nio.file.Files;
import java.util.List;

/**
 * 文件管理模块控制器
 *
 * 职责：文件上传/下载/列表/在线预览/备注/导入状态翻转。
 * 文件统一存储在 app.file-manage.kb-dir（默认 /opt/kb），与 4.4 章节
 * relative_path 指向 KB_FILE_DIR 的约定对齐，便于外部知识库系统直接读取导入。
 */
@Slf4j
@RestController
@RequestMapping("/api/file")
@Tag(name = "文件管理")
public class FileManageController {

    @Autowired
    private FileManageService fileManageService;

    @Autowired(required = false)
    private KnowledgeBaseApiClient knowledgeBaseApiClient;

    @PostMapping("/upload")
    @Operation(summary = "上传文件", description = "保存至 kb-dir，同名覆盖并重置导入状态")
    public R<FileRecordEntity> upload(@RequestParam("file") MultipartFile file,
                                      @RequestParam(value = "relativePath", required = false) String relativePath,
                                      @RequestParam(value = "remark", required = false) String remark) {
        String username = UserContext.getUsername();
        FileRecordEntity entity = fileManageService.upload(file, relativePath, remark, username);
        return R.success("上传成功", entity);
    }

    @GetMapping("/list")
    @Operation(summary = "文件列表", description = "支持按文件名模糊、导入状态过滤，分页")
    public R<Page<FileRecordEntity>> list(@RequestParam(value = "fileName", required = false) String fileName,
                                          @RequestParam(value = "importStatus", required = false) String importStatus,
                                          @RequestParam(value = "current", defaultValue = "1") Long current,
                                          @RequestParam(value = "size", defaultValue = "20") Long size) {
        return R.success(fileManageService.list(fileName, importStatus, current, size));
    }

    @GetMapping("/all")
    @Operation(summary = "全部文件", description = "供外部知识库系统拉取 relative_path 入库")
    public R<List<FileRecordEntity>> all() {
        return R.success(fileManageService.all());
    }

    @GetMapping("/{id}")
    @Operation(summary = "文件详情")
    public R<FileRecordEntity> detail(@PathVariable Long id) {
        return R.success(fileManageService.detail(id));
    }

    @PutMapping("/remark/{id}")
    @Operation(summary = "更新备注")
    public R<FileRecordEntity> updateRemark(@PathVariable Long id,
                                            @RequestParam("remark") String remark) {
        return R.success(fileManageService.updateRemark(id, remark));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "删除文件", description = "deletePhysical=true 同时删除物理文件")
    public R<Void> delete(@PathVariable Long id,
                          @RequestParam(value = "deletePhysical", defaultValue = "false") boolean deletePhysical) {
        fileManageService.delete(id, deletePhysical);
        return R.success("删除成功", null);
    }

    @PutMapping("/import-status/{id}")
    @Operation(summary = "翻转导入知识库状态", description = "外部知识库入库完成回调；imported=true 已录入")
    public R<FileRecordEntity> updateImportStatus(@PathVariable Long id,
                                                  @RequestParam("imported") boolean imported) {
        return R.success(fileManageService.updateImportStatus(id, imported));
    }

    @PostMapping("/ingest/{id}")
    @Operation(summary = "入库到知识库", description = "调用 api-design.md 4.4.4 接口按相对路径入库；返回入库成功则自动标记已录入")
    public R<Boolean> ingest(@PathVariable Long id) {
        boolean success = fileManageService.ingest(id);
        return R.success(success ? "入库成功" : "入库失败", success);
    }

    @GetMapping("/knowledge-files")
    @Operation(summary = "知识库详情", description = "直接调用外部知识库 GET /api/v1/knowledge/files 查询入库情况，不落库")
    public R<com.alibaba.fastjson2.JSONObject> knowledgeFiles() {
        if (knowledgeBaseApiClient == null) {
            return R.error("知识库服务未启用（app.knowledge-base.enabled=false 或配置缺失）");
        }
        com.alibaba.fastjson2.JSONObject result = knowledgeBaseApiClient.listFiles();
        if (result == null) {
            return R.error("查询知识库文件失败，请检查知识库服务是否可用");
        }
        return R.success(result);
    }

    @GetMapping("/knowledge-search")
    @Operation(summary = "知识库检索", description = "直接调用外部知识库 GET /api/v1/knowledge/search 检索内容，不落库")
    public R<com.alibaba.fastjson2.JSONObject> knowledgeSearch(
            @RequestParam("q") String q,
            @RequestParam(value = "top_k", required = false, defaultValue = "5") Integer topK,
            @RequestParam(value = "category_code", required = false) String categoryCode,
            @RequestParam(value = "field", required = false) String field) {
        if (knowledgeBaseApiClient == null) {
            return R.error("知识库服务未启用（app.knowledge-base.enabled=false 或配置缺失）");
        }
        if (q == null || q.trim().isEmpty()) {
            return R.error("检索关键词 q 不能为空");
        }
        com.alibaba.fastjson2.JSONObject result = knowledgeBaseApiClient.search(q.trim(), topK, categoryCode, field);
        if (result == null) {
            return R.error("知识库检索失败，请检查知识库服务是否可用");
        }
        return R.success(result);
    }

    @PostMapping("/knowledge-upload")
    @Operation(summary = "知识库文件导入", description = "直接调用外部知识库 POST /api/v1/knowledge/files 上传文件，不落库")
    public R<com.alibaba.fastjson2.JSONObject> knowledgeUpload(
            @RequestParam("file") org.springframework.web.multipart.MultipartFile file,
            @RequestParam(value = "relative_path", required = false) String relativePath) {
        if (knowledgeBaseApiClient == null) {
            return R.error("知识库服务未启用（app.knowledge-base.enabled=false 或配置缺失）");
        }
        if (file == null || file.isEmpty()) {
            return R.error("上传文件不能为空");
        }
        try {
            byte[] content = file.getBytes();
            com.alibaba.fastjson2.JSONObject result =
                    knowledgeBaseApiClient.uploadFile(file.getOriginalFilename(), content, relativePath);
            if (result == null) {
                return R.error("知识库文件导入失败，请检查知识库服务是否可用");
            }
            return R.success(result);
        } catch (Exception e) {
            log.error("知识库文件导入异常 {}", e.getMessage(), e);
            return R.error("知识库文件导入异常：" + e.getMessage());
        }
    }

    @GetMapping("/download/{id}")
    @Operation(summary = "下载文件")
    public ResponseEntity<org.springframework.core.io.Resource> download(@PathVariable Long id) {
        return toResponse(id, true);
    }

    @GetMapping("/preview/{id}")
    @Operation(summary = "在线预览", description = "图片/PDF/文本内联展示；Office 等返回附件供浏览器处理")
    public ResponseEntity<org.springframework.core.io.Resource> preview(@PathVariable Long id) {
        return toResponse(id, false);
    }

    private ResponseEntity<org.springframework.core.io.Resource> toResponse(Long id, boolean forceDownload) {
        FileRecordEntity meta = fileManageService.detail(id);
        if (meta == null) {
            throw new IllegalArgumentException("文件记录不存在: " + id);
        }
        File file = fileManageService.resolvePhysical(id);
        org.springframework.core.io.Resource resource = new org.springframework.core.io.FileSystemResource(file);
        String contentType;
        try {
            contentType = Files.probeContentType(file.toPath());
        } catch (Exception e) {
            contentType = null;
        }
        if (contentType == null) {
            contentType = MediaType.APPLICATION_OCTET_STREAM_VALUE;
        }
        String dispositionType = forceDownload ? "attachment" : inlineOrAttachment(contentType);
        String encodedName;
        try {
            encodedName = java.net.URLEncoder.encode(meta.getFileName(), "UTF-8").replace("+", "%20");
        } catch (java.io.UnsupportedEncodingException e) {
            encodedName = meta.getFileName();
        }
        String header = String.format("%s; filename=\"%s\"; filename*=UTF-8''%s",
                dispositionType,
                meta.getFileName(),
                encodedName);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, header)
                .header(HttpHeaders.CACHE_CONTROL, "no-cache, no-store, must-revalidate")
                .contentType(MediaType.parseMediaType(contentType))
                .contentLength(file.length())
                .body(resource);
    }

    /** 图片、PDF、文本类允许浏览器内联(inline)预览；其余作为附件 */
    private String inlineOrAttachment(String contentType) {
        if (contentType == null) {
            return "attachment";
        }
        if (contentType.startsWith("image/")
                || contentType.equals("application/pdf")
                || contentType.startsWith("text/")) {
            return "inline";
        }
        return "attachment";
    }
}
