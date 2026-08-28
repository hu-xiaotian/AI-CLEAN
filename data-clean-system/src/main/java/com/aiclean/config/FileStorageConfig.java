package com.aiclean.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

import javax.annotation.PostConstruct;
import java.io.File;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 文件管理模块存储配置。
 * 路径统一从 application.yml 的 app.file-manage 读取，支持环境变量覆盖。
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "app.file-manage")
public class FileStorageConfig {

    /** 知识库文件根目录 */
    private String kbDir = "/opt/kb";

    /** 允许上传的文件后缀白名单（逗号分隔） */
    private String allowedExtensions = "docx,doc,txt,md,xlsx,xls,pdf,jpg,jpeg,png,gif,bmp,csv";

    /** 单文件大小上限（字节） */
    private long maxSize = 209715200L;

    @PostConstruct
    public void init() {
        File dir = new File(kbDir);
        if (!dir.exists()) {
            dir.mkdirs();
        }
    }

    /** 返回受控的根目录绝对路径 */
    public File getRootDir() {
        return new File(kbDir).getAbsoluteFile();
    }

    /** 允许的后缀列表（小写，去空） */
    public List<String> getAllowedExtensionList() {
        return Arrays.stream(allowedExtensions.split(","))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .map(String::toLowerCase)
                .collect(Collectors.toList());
    }

    /**
     * 把相对路径解析为受控的绝对文件，防止路径穿越（../）。
     * 返回的路径必须仍在根目录之内。
     */
    public File resolve(String relativePath) {
        File base = getRootDir();
        File target = new File(base, relativePath).getAbsoluteFile();
        if (!target.getPath().startsWith(base.getPath() + File.separator)
                && !target.getPath().equals(base.getPath())) {
            throw new IllegalArgumentException("非法的文件路径: " + relativePath);
        }
        return target;
    }
}
