package com.aiclean.ai;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

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

    /** Embedding 模型名称（OpenAI 兼容 /embeddings 接口），默认复用对话模型；可单独配置 app.ai.embedding-model */
    @Value("${app.ai.embedding-model:}")
    private String embeddingModel;

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

    /** 当前使用的 Embedding 模型名称（配置了 embedding-model 则用之，否则复用对话模型） */
    public String getEmbeddingModel() {
        return StrUtil.isNotBlank(embeddingModel) ? embeddingModel : model;
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

    /**
     * 调用 Embedding 接口，把一段文本向量化为 double 数组（OpenAI 兼容 /embeddings）。
     *
     * @param text 待向量化文本
     * @return 该文本的向量（double[]）
     */
    public double[] embedding(String text) {
        if (!isEnabled()) {
            throw new RuntimeException("AI 提取功能未启用，请在 application.yml 中配置 app.ai（base-url / api-key / model）");
        }
        if (StrUtil.isBlank(text)) {
            throw new RuntimeException("Embedding 输入文本为空");
        }
        try {
            String url = baseUrl.endsWith("/") ? baseUrl + "embeddings" : baseUrl + "/embeddings";
            String model = getEmbeddingModel();

            JSONObject body = new JSONObject();
            body.put("model", model);
            body.put("input", text);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            if (StrUtil.isNotBlank(apiKey)) {
                headers.setBearerAuth(apiKey);
            }
            HttpEntity<String> entity = new HttpEntity<>(body.toJSONString(), headers);

            org.springframework.http.ResponseEntity<String> response =
                    restTemplate.postForEntity(url, entity, String.class);

            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                throw new RuntimeException("Embedding 服务返回异常状态码: " + response.getStatusCode());
            }

            JSONObject resp = JSON.parseObject(response.getBody());
            JSONArray dataArr = resp.getJSONArray("data");
            if (dataArr == null || dataArr.isEmpty()) {
                throw new RuntimeException("Embedding 服务未返回有效内容");
            }
            JSONObject first = dataArr.getJSONObject(0);
            JSONArray vec = first.getJSONArray("embedding");
            if (vec == null) {
                throw new RuntimeException("Embedding 服务未返回 embedding 向量");
            }
            double[] arr = new double[vec.size()];
            for (int i = 0; i < vec.size(); i++) {
                arr[i] = vec.getDoubleValue(i);
            }
            return arr;
        } catch (RuntimeException re) {
            throw re;
        } catch (Exception e) {
            log.error("调用 Embedding 服务失败", e);
            throw new RuntimeException("调用 Embedding 服务失败: " + e.getMessage());
        }
    }

    /**
     * 批量调用 Embedding 接口，把一批文本一次性向量化为 double 数组（OpenAI 兼容 /embeddings，input 传数组）。
     * <p>
     * 返回列表顺序与输入 texts 一致；若某个文本为空则对应位置返回 null。相比逐条调用大幅减少 HTTP 请求次数，
     * 显著提升全表/大批量向量化速度。
     *
     * @param texts 待向量化文本列表
     * @return 与输入顺序一致的向量列表（空文本对应 null）
     */
    public List<double[]> embeddingBatch(List<String> texts) {
        List<double[]> out = new ArrayList<>();
        if (texts == null || texts.isEmpty()) return out;
        if (!isEnabled()) {
            throw new RuntimeException("AI 提取功能未启用，请在 application.yml 中配置 app.ai（base-url / api-key / model）");
        }
        // 剔除空文本并记录其位置
        List<Integer> validIdx = new ArrayList<>();
        List<String> validTexts = new ArrayList<>();
        for (int i = 0; i < texts.size(); i++) {
            if (StrUtil.isBlank(texts.get(i))) {
                out.add(null);
            } else {
                out.add(null); // 占位，稍后填充
                validIdx.add(i);
                validTexts.add(texts.get(i));
            }
        }
        if (validTexts.isEmpty()) return out;
        try {
            String url = baseUrl.endsWith("/") ? baseUrl + "embeddings" : baseUrl + "/embeddings";
            String model = getEmbeddingModel();

            JSONObject body = new JSONObject();
            body.put("model", model);
            body.put("input", validTexts);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            if (StrUtil.isNotBlank(apiKey)) {
                headers.setBearerAuth(apiKey);
            }
            HttpEntity<String> entity = new HttpEntity<>(body.toJSONString(), headers);

            org.springframework.http.ResponseEntity<String> response =
                    restTemplate.postForEntity(url, entity, String.class);

            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                throw new RuntimeException("Embedding 服务返回异常状态码: " + response.getStatusCode());
            }

            JSONObject resp = JSON.parseObject(response.getBody());
            JSONArray dataArr = resp.getJSONArray("data");
            if (dataArr == null || dataArr.isEmpty()) {
                throw new RuntimeException("Embedding 服务未返回有效内容");
            }
            // 按返回的 index 填充到对应位置（接口通常按输入顺序返回 index 0..n-1）
            for (int i = 0; i < dataArr.size(); i++) {
                JSONObject item = dataArr.getJSONObject(i);
                int idx = item.getIntValue("index", i);
                if (idx < 0 || idx >= validIdx.size()) continue;
                JSONArray vec = item.getJSONArray("embedding");
                if (vec == null) continue;
                double[] arr = new double[vec.size()];
                for (int j = 0; j < vec.size(); j++) {
                    arr[j] = vec.getDoubleValue(j);
                }
                out.set(validIdx.get(idx), arr);
            }
            return out;
        } catch (RuntimeException re) {
            throw re;
        } catch (Exception e) {
            log.error("批量调用 Embedding 服务失败", e);
            throw new RuntimeException("批量调用 Embedding 服务失败: " + e.getMessage());
        }
    }

    /**
     * 多轮对话：支持传入完整对话历史（system 由 systemPrompt 指定，其余消息来自 messages）
     *
     * @param systemPrompt 系统提示词
     * @param messages     对话历史，元素为包含 role/content 的 Map（role 取值 user/assistant）
     * @return 模型返回的纯文本内容
     */
    public String chatWithHistory(String systemPrompt, List<Map<String, String>> messages) {
        if (!isEnabled()) {
            throw new RuntimeException("AI 提取功能未启用，请在 application.yml 中配置 app.ai（base-url / api-key / model）");
        }
        try {
            String url = baseUrl.endsWith("/") ? baseUrl + "chat/completions" : baseUrl + "/chat/completions";

            JSONObject sysMsg = new JSONObject();
            sysMsg.put("role", "system");
            sysMsg.put("content", systemPrompt);

            List<JSONObject> msgList = new ArrayList<>();
            msgList.add(sysMsg);
            if (messages != null) {
                for (Map<String, String> m : messages) {
                    if (m == null) continue;
                    JSONObject jm = new JSONObject();
                    jm.put("role", m.get("role"));
                    jm.put("content", m.get("content"));
                    msgList.add(jm);
                }
            }

            JSONObject body = new JSONObject();
            body.put("model", model);
            body.put("messages", msgList);
            body.put("temperature", temperature);
            body.put("max_tokens", maxTokens);
            body.put("stream", false);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
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
