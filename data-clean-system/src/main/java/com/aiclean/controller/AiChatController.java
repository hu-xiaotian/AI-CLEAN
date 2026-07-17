package com.aiclean.controller;

import com.aiclean.ai.AiClientService;
import com.aiclean.common.R;
import com.aiclean.service.CategoryAiService;
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

    @Autowired
    private CategoryAiService categoryAiService;

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

    /**
     * 标准分类代码问答
     * 基于 main_data_category（标准分类库）回答用户关于标准分类的提问。
     * 请求体 messages 为对话历史（最后一条应为用户当前问题），可选 systemPrompt 覆盖系统提示词。
     * 返回 reply（AI 回复）与 sources（命中的标准分类来源记录）。
     */
    @PostMapping("/category-chat")
    @Operation(summary = "标准分类问答", description = "基于 main_data_category 标准分类库回答用户关于标准分类的提问，返回回复与命中的来源记录")
    public R<Map<String, Object>> categoryChat(@RequestBody Map<String, Object> body) {
        try {
            if (!aiClientService.isEnabled()) {
                return R.error("AI 对话功能未启用，请在 application.yml 中配置 app.ai（base-url / api-key / model）");
            }
            Object rawMessages = body.get("messages");
            if (!(rawMessages instanceof List) || ((List<?>) rawMessages).isEmpty()) {
                return R.badRequest("messages 不能为空");
            }
            List<Map<String, String>> messages = new ArrayList<>();
            String lastUserQuestion = null;
            for (Object o : (List<?>) rawMessages) {
                if (o instanceof Map) {
                    Map<?, ?> mm = (Map<?, ?>) o;
                    String role = mm.get("role") == null ? "user" : String.valueOf(mm.get("role"));
                    String content = mm.get("content") == null ? "" : String.valueOf(mm.get("content"));
                    messages.add(new HashMap<String, String>() {{
                        put("role", role);
                        put("content", content);
                    }});
                    if ("user".equals(role) && !content.isEmpty()) {
                        lastUserQuestion = content;
                    }
                }
            }
            if (messages.isEmpty()) {
                return R.badRequest("messages 格式不正确");
            }
            if (lastUserQuestion == null) {
                return R.badRequest("未找到用户问题");
            }
            CategoryAiService.CategoryChatResult result = categoryAiService.chat(messages, lastUserQuestion);
            Map<String, Object> data = new HashMap<>();
            data.put("reply", result.getReply());
            data.put("sources", result.getSources() == null ? new ArrayList<>() : result.getSources());
            data.put("sourceCount", result.getSources() == null ? 0 : result.getSources().size());
            return R.success(data);
        } catch (Exception e) {
            log.error("标准分类问答失败", e);
            return R.error("标准分类问答失败: " + e.getMessage());
        }
    }
}
