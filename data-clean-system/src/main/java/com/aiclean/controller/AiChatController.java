package com.aiclean.controller;

import com.aiclean.ai.AiClientService;
import com.aiclean.common.R;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * AI 对话控制器
 * 为数据看板提供多轮对话能力，复用通用 AI 客户端（app.ai 配置），
 * 可用于解读统计指标、分析失败原因与分类匹配情况等。
 */
@RestController
@RequestMapping("/api/ai")
@Tag(name = "AI 对话", description = "看板 AI 对话接口")
@Slf4j
public class AiChatController {

    private static final String DEFAULT_SYSTEM_PROMPT =
            "你是一名专业的数据清洗分析助手，帮助用户解读数据统计看板中的指标、失败原因与分类匹配情况，" +
            "用简洁、可操作的中文给出分析与建议。";

    @Autowired
    private AiClientService aiClientService;

    @PostMapping("/chat")
    @Operation(summary = "AI 对话", description = "多轮对话接口，请求体 messages 为对话历史 [{role, content}]，可选 systemPrompt 覆盖系统提示词")
    public R<Map<String, Object>> chat(@RequestBody Map<String, Object> body) {
        try {
            if (!aiClientService.isEnabled()) {
                return R.error("AI 对话功能未启用，请在 application.yml 中配置 app.ai（base-url / api-key / model）");
            }
            Object rawMessages = body.get("messages");
            if (!(rawMessages instanceof List) || ((List<?>) rawMessages).isEmpty()) {
                return R.badRequest("messages 不能为空");
            }
            List<Map<String, String>> messages = new ArrayList<>();
            for (Object o : (List<?>) rawMessages) {
                if (o instanceof Map) {
                    Map<?, ?> m = (Map<?, ?>) o;
                    String role = m.get("role") == null ? "user" : String.valueOf(m.get("role"));
                    String content = m.get("content") == null ? "" : String.valueOf(m.get("content"));
                    messages.add(new HashMap<String, String>() {{
                        put("role", role);
                        put("content", content);
                    }});
                }
            }
            if (messages.isEmpty()) {
                return R.badRequest("messages 格式不正确");
            }
            String systemPrompt = body.get("systemPrompt") == null
                    ? DEFAULT_SYSTEM_PROMPT
                    : String.valueOf(body.get("systemPrompt"));

            String reply = aiClientService.chatWithHistory(systemPrompt, messages);
            Map<String, Object> data = new HashMap<>();
            data.put("reply", reply);
            return R.success(data);
        } catch (Exception e) {
            log.error("AI 对话失败", e);
            return R.error("AI 对话失败: " + e.getMessage());
        }
    }

    @GetMapping("/chat-enabled")
    @Operation(summary = "查询 AI 对话是否可用")
    public R<Map<String, Object>> chatEnabled() {
        Map<String, Object> data = new HashMap<>();
        data.put("enabled", aiClientService.isEnabled());
        return R.success(data);
    }
}
