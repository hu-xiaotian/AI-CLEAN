package com.aiclean.ai;

import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Properties;

/**
 * 一次性批次分类的提示词加载器。
 * <p>
 * 提示词独立存放在 {@code classification-batch-prompt.properties} 中，便于后续修改而无需改动代码。
 * 加载优先级：
 * <ol>
 *     <li>外部文件 {@code <外部提示词目录>/classification-batch-prompt.properties}（默认外部目录为
 *         启动目录下 {@code ./config}，可通过配置 {@code app.ai.batch-prompt-external-dir} 覆盖）——
 *         修改后保存即生效，适合现场调优。</li>
 *     <li>classpath 内置文件 {@code classpath:classification-batch-prompt.properties}（随包发布）。</li>
 * </ol>
 * 每次调用 {@link #get(String)} 都会重新检查外部文件，外部文件被修改后可立即读到新提示词。
 */
@Slf4j
@Component
public class BatchClassificationPrompt {

    /** 内置提示词文件的 classpath 路径 */
    private static final String CLASS_PATH_FILE = "classification-batch-prompt.properties";

    /** 外部提示词目录（默认启动目录下 ./config） */
    @Value("${app.ai.batch-prompt-external-dir:./config}")
    private String externalDir;

    /** 内置提示词（classpath）缓存，供外部文件缺失时兜底 */
    private final Properties classpathProps = new Properties();

    @PostConstruct
    public void init() {
        loadClasspath();
    }

    private void loadClasspath() {
        try (InputStream in = new ClassPathResource(CLASS_PATH_FILE).getInputStream()) {
            classpathProps.clear();
            classpathProps.load(new java.io.InputStreamReader(in, StandardCharsets.UTF_8));
            log.info("批次分类提示词已加载（classpath），共 {} 条配置", classpathProps.size());
        } catch (IOException e) {
            log.warn("classpath 中未找到批次分类提示词文件 {}，将使用默认提示词", CLASS_PATH_FILE, e);
        }
    }

    /**
     * 获取指定键的提示词。优先读外部文件，缺失时回退内置；均缺失返回 null。
     */
    public String get(String key) {
        if (StrUtil.isBlank(key)) return null;
        Properties external = loadExternal();
        if (external != null) {
            String v = external.getProperty(key);
            if (v != null) return v;
        }
        return classpathProps.getProperty(key);
    }

    /**
     * 获取指定键的提示词；均缺失时返回给定默认值。
     */
    public String getOrDefault(String key, String defaultValue) {
        String v = get(key);
        return v != null ? v : defaultValue;
    }

    /**
     * 尝试加载外部提示词文件。不存在或读取失败返回 null（不抛异常）。
     */
    private Properties loadExternal() {
        try {
            File dir = new File(externalDir);
            if (!dir.exists()) return null;
            File f = new File(dir, CLASS_PATH_FILE);
            if (!f.isFile() || !f.exists()) return null;
            Properties p = new Properties();
            p.load(new java.io.InputStreamReader(
                    Files.newInputStream(f.toPath()), StandardCharsets.UTF_8));
            return p;
        } catch (Exception e) {
            log.warn("读取外部批次分类提示词文件失败，回退内置: {}", e.getMessage());
            return null;
        }
    }

    /** 外部提示词目录（供测试/日志） */
    public String getExternalDir() {
        return externalDir;
    }
}
