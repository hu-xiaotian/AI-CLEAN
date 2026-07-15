package com.aiclean.ai;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.Arrays;

/**
 * 通用 AI 客户端
 * 兼容 OpenAI / DeepSeek / 通义千问(兼容模式) 等 Chat Completions 接口。
 * 通过 application.yml 中的 app.ai 配置项启用并指定接入点、密钥与模型。
 */
@Slf4j
@Service
public class AiClientService {

    @Value("${app.ai.enabled:false}")
    private boolean enabled;

    @Value("${app.ai.base-url:}")
    private String baseUrl;

    @Value("${app.ai.api-key:}")
    private String apiKey;

    @Value("${app.ai.model:}")
    private String model;

    @Value("${app.ai.temperature:0.2}")
    private double temperature;

    @Value("${app.ai.max-tokens:2048}")
    private int maxTokens;

    private final RestTemplate restTemplate;

    public AiClientService() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(15000);
        // AI 调用可能较慢，读取超时给到 120s
        factory.setReadTimeout(120000);
        this.restTemplate = new RestTemplate(factory);
    }

    public boolean isEnabled() {
        return enabled
                && baseUrl != null && !StrUtil.isBlank(baseUrl)
                && model != null && !StrUtil.isBlank(model);
    }

    /**
     * 调用大模型对话接口
     *
     * @param systemPrompt 系统提示词
     * @param userPrompt   用户提示词
     * @return 模型返回的纯文本内容
     */
    public String chat(String systemPrompt, String userPrompt) {
        if (!isEnabled()) {
            throw new RuntimeException("AI 提取功能未启用，请在 application.yml 中配置 app.ai（base-url / api-key / model）");
        }
        try {
            String url = baseUrl.endsWith("/") ? baseUrl + "chat/completions" : baseUrl + "/chat/completions";

            JSONObject sysMsg = new JSONObject();
            sysMsg.put("role", "system");
            sysMsg.put("content", systemPrompt);

            JSONObject userMsg = new JSONObject();
            userMsg.put("role", "user");
            userMsg.put("content", userPrompt);

            JSONObject body = new JSONObject();
            body.put("model", model);
            body.put("messages", Arrays.asList(sysMsg, userMsg));
            body.put("temperature", temperature);
            body.put("max_tokens", maxTokens);
            body.put("stream", false);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            // api-key 为空时（如本地免鉴权部署）不发送 Authorization 头
            if (StrUtil.isNotBlank(apiKey)) {
                headers.setBearerAuth(apiKey);
            }
            HttpEntity<String> entity = new HttpEntity<>(body.toJSONString(), headers);

            org.springframework.http.ResponseEntity<String> response =
                    restTemplate.postForEntity(url, entity, String.class);

            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                throw new RuntimeException("AI 服务返回异常状态码: " + response.getStatusCode());
            }

            JSONObject resp = JSON.parseObject(response.getBody());
            JSONArray choices = resp.getJSONArray("choices");
            if (choices == null || choices.isEmpty()) {
                throw new RuntimeException("AI 服务未返回有效内容");
            }
            return choices.getJSONObject(0).getJSONObject("message").getString("content");
        } catch (RuntimeException re) {
            throw re;
        } catch (Exception e) {
            log.error("调用 AI 服务失败", e);
            throw new RuntimeException("调用 AI 服务失败: " + e.getMessage());
        }
    }
}
