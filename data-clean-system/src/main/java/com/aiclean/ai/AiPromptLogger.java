package com.aiclean.ai;

import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * 大模型调用输入/输出的 txt 调试日志记录器。
 * <p>
 * 每个业务任务（如一次属性提取）对应一个 txt 文件，按追加方式顺序写入每个批次的
 * system prompt / user prompt / 原始返回内容，便于人工排查提示词与模型输出问题。
 * <p>
 * 文件位置：{app.ai.prompt-log.dir}/{module}/{module}_{bizId}_{yyyyMMdd_HHmmss}.txt
 */
@Slf4j
@Component
public class AiPromptLogger {

    private static final DateTimeFormatter FILE_TS = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");
    private static final DateTimeFormatter LINE_TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    private static final String SEP = "================================================================================";

    @Value("${app.ai.prompt-log.enabled:true}")
    private boolean enabled;

    @Value("${app.ai.prompt-log.dir:logs/ai-prompt}")
    private String baseDir;

    /** 单条 prompt/响应最大记录字符数，超出截断，避免日志文件过大 */
    @Value("${app.ai.prompt-log.max-content-length:200000}")
    private int maxContentLength;

    /** 每个模块保留的最大文件数，超出按修改时间删除最旧的 */
    @Value("${app.ai.prompt-log.max-files-per-module:200}")
    private int maxFilesPerModule;

    /** 同一个日志会话（sessionKey -> 文件路径），保证并发写入同一文件 */
    private final Map<String, Path> sessionFiles = new ConcurrentHashMap<>();
    /** 文件级写锁，保证多线程批次交叉写入时内容不串行错乱 */
    private final Map<String, Object> fileLocks = new ConcurrentHashMap<>();

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * 开启一个日志会话（一次跑批任务一个文件）。
     *
     * @param module 模块名，如 attr-extract
     * @param bizId  业务标识，如文件表头ID
     * @return sessionKey，后续写入与查询用；未启用时返回 null
     */
    public String startSession(String module, Object bizId) {
        if (!enabled) return null;
        try {
            String safeModule = safe(module);
            Path dir = Paths.get(baseDir, safeModule);
            Files.createDirectories(dir);
            String fileName = safeModule + "_" + bizId + "_" + LocalDateTime.now().format(FILE_TS) + ".txt";
            Path file = dir.resolve(fileName);
            Files.write(file, new byte[0], StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            String sessionKey = safeModule + "/" + fileName;
            sessionFiles.put(sessionKey, file);
            cleanupOldFiles(dir);
            return sessionKey;
        } catch (Exception e) {
            log.warn("创建 AI 提示词日志文件失败，module={}, bizId={}: {}", module, bizId, e.getMessage());
            return null;
        }
    }

    /** 写入任务头部信息（配置概览、总量等） */
    public void writeHeader(String sessionKey, String title, Map<String, Object> meta) {
        if (sessionKey == null || !enabled) return;
        StringBuilder sb = new StringBuilder();
        sb.append(SEP).append('\n');
        sb.append("【任务】").append(title).append('\n');
        sb.append("【开始时间】").append(LocalDateTime.now().format(LINE_TS)).append('\n');
        if (meta != null) {
            meta.forEach((k, v) -> sb.append("【").append(k).append("】").append(v).append('\n'));
        }
        sb.append(SEP).append("\n\n");
        append(sessionKey, sb.toString());
    }

    /**
     * 记录一次大模型调用的输入与输出。
     *
     * @param sessionKey   会话标识
     * @param batchLabel   批次标签，如 "分类 01010101 / 批次 2/5（20条）"
     * @param systemPrompt 系统提示词
     * @param userPrompt   用户提示词
     * @param response     模型原始返回
     * @param costMs       耗时（毫秒），可为 null
     * @param error        异常信息，成功时为 null
     */
    public void writeCall(String sessionKey, String batchLabel, String systemPrompt, String userPrompt,
                          String response, Long costMs, String error) {
        if (sessionKey == null || !enabled) return;
        StringBuilder sb = new StringBuilder();
        sb.append(SEP).append('\n');
        sb.append(">>> ").append(batchLabel == null ? "调用" : batchLabel)
                .append("  @").append(LocalDateTime.now().format(LINE_TS));
        if (costMs != null) sb.append("  耗时 ").append(costMs).append("ms");
        sb.append('\n').append(SEP).append('\n');
        sb.append("---------- [INPUT] system ----------\n").append(truncate(systemPrompt)).append('\n');
        sb.append("---------- [INPUT] user ----------\n").append(truncate(userPrompt)).append('\n');
        if (StrUtil.isNotBlank(error)) {
            sb.append("---------- [ERROR] ----------\n").append(truncate(error)).append('\n');
        }
        sb.append("---------- [OUTPUT] ----------\n").append(truncate(response)).append("\n\n");
        append(sessionKey, sb.toString());
    }

    /** 写入自定义文本段（如解析后的结构化结果、汇总信息） */
    public void writeSection(String sessionKey, String title, String content) {
        if (sessionKey == null || !enabled) return;
        append(sessionKey, "---------- " + title + " ----------\n" + truncate(content) + "\n\n");
    }

    /** 写入任务结尾汇总 */
    public void writeFooter(String sessionKey, String summary) {
        if (sessionKey == null || !enabled) return;
        append(sessionKey, SEP + "\n【任务结束】" + LocalDateTime.now().format(LINE_TS) + "\n"
                + (summary == null ? "" : summary + "\n") + SEP + "\n");
    }

    /** 结束会话，释放句柄映射（文件仍保留在磁盘） */
    public void endSession(String sessionKey) {
        if (sessionKey == null) return;
        sessionFiles.remove(sessionKey);
        fileLocks.remove(sessionKey);
    }

    /** 获取会话对应的日志文件绝对路径（供前端展示/下载） */
    public String getLogFilePath(String sessionKey) {
        if (sessionKey == null) return null;
        Path p = sessionFiles.get(sessionKey);
        if (p == null) {
            p = Paths.get(baseDir).resolve(sessionKey);
        }
        return p.toAbsolutePath().toString();
    }

    /** 列出某模块下的日志文件（按修改时间倒序） */
    public List<Map<String, Object>> listLogFiles(String module, int limit) {
        List<Map<String, Object>> result = new ArrayList<>();
        Path dir = Paths.get(baseDir, safe(module));
        if (!Files.isDirectory(dir)) return result;
        try (Stream<Path> stream = Files.list(dir)) {
            List<Path> files = stream.filter(p -> p.getFileName().toString().endsWith(".txt"))
                    .sorted(Comparator.comparingLong(AiPromptLogger::lastModified).reversed())
                    .limit(limit <= 0 ? 50 : limit)
                    .collect(java.util.stream.Collectors.toList());
            for (Path p : files) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("fileName", p.getFileName().toString());
                m.put("sessionKey", safe(module) + "/" + p.getFileName());
                m.put("size", Files.size(p));
                m.put("lastModified", LocalDateTime.ofInstant(
                        Files.getLastModifiedTime(p).toInstant(), java.time.ZoneId.systemDefault())
                        .format(LINE_TS));
                result.add(m);
            }
        } catch (IOException e) {
            log.warn("列出 AI 提示词日志失败: {}", e.getMessage());
        }
        return result;
    }

    /** 读取日志文件内容（用于前端在线查看） */
    public String readLog(String module, String fileName) {
        Path p = Paths.get(baseDir, safe(module), safe(fileName));
        if (!Files.isRegularFile(p)) {
            throw new RuntimeException("日志文件不存在: " + fileName);
        }
        try {
            return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("读取日志文件失败: " + e.getMessage());
        }
    }

    // ==================== 内部方法 ====================

    private void append(String sessionKey, String content) {
        Path file = sessionFiles.get(sessionKey);
        if (file == null) {
            file = Paths.get(baseDir).resolve(sessionKey);
        }
        Object lock = fileLocks.computeIfAbsent(sessionKey, k -> new Object());
        synchronized (lock) {
            try {
                Files.createDirectories(file.getParent());
                Files.write(file, content.getBytes(StandardCharsets.UTF_8),
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (Exception e) {
                log.warn("写入 AI 提示词日志失败: {}", e.getMessage());
            }
        }
    }

    private String truncate(String s) {
        if (s == null) return "(null)";
        if (maxContentLength > 0 && s.length() > maxContentLength) {
            return s.substring(0, maxContentLength) + "\n...(已截断，原始长度 " + s.length() + " 字符)";
        }
        return s;
    }

    private void cleanupOldFiles(Path dir) {
        if (maxFilesPerModule <= 0) return;
        try (Stream<Path> stream = Files.list(dir)) {
            List<Path> files = stream.filter(p -> p.getFileName().toString().endsWith(".txt"))
                    .sorted(Comparator.comparingLong(AiPromptLogger::lastModified))
                    .collect(java.util.stream.Collectors.toList());
            int over = files.size() - maxFilesPerModule;
            for (int i = 0; i < over; i++) {
                try {
                    Files.deleteIfExists(files.get(i));
                } catch (IOException ignore) {
                    // 忽略单个文件删除失败
                }
            }
        } catch (IOException ignore) {
            // 清理失败不影响主流程
        }
    }

    private static long lastModified(Path p) {
        try {
            return Files.getLastModifiedTime(p).toMillis();
        } catch (IOException e) {
            return 0L;
        }
    }

    /** 过滤路径穿越字符，避免任意文件读取 */
    private static String safe(String s) {
        if (StrUtil.isBlank(s)) return "default";
        return s.replaceAll("[^A-Za-z0-9_.\\-\u4e00-\u9fa5]", "_");
    }
}
