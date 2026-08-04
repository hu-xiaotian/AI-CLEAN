package com.aiclean.externalclean.client;

import com.aiclean.externalclean.config.ExternalCleanProperties;
import com.aiclean.externalclean.dto.CallbackPayload;
import com.aiclean.externalclean.dto.CleanOptions;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

/**
 * 外部数据清洗服务客户端
 * 严格按 api-design.md 调用外部服务：
 *  - POST /api/v1/clean/async   异步（202 响应，后台处理完后回调本系统）
 *  - POST /api/v1/clean         同步（≤10 条，单次返回结果）
 *  - GET  /api/v1/clean/{taskId} 查询进度/结果（兜底轮询）
 * 本类不依赖系统其他模块。
 */
@Slf4j
@Component
public class ExternalCleanApiClient {

    private final ExternalCleanProperties properties;
    private final RestTemplate restTemplate;

    public ExternalCleanApiClient(ExternalCleanProperties properties) {
        this.properties = properties;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.getTimeoutSeconds() * 1000);
        factory.setReadTimeout(properties.getTimeoutSeconds() * 1000);
        this.restTemplate = new RestTemplate(factory);
    }

    private String baseUrl() {
        String u = properties.getBaseUrl();
        return u.endsWith("/") ? u.substring(0, u.length() - 1) : u;
    }

    private HttpHeaders headers() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (cn.hutool.core.util.StrUtil.isNotBlank(properties.getApiKey())) {
            headers.setBearerAuth(properties.getApiKey());
        }
        return headers;
    }

    /**
     * 构建外部清洗请求体（CleanRequest）
     */
    private JSONObject buildRequestBody(String taskId, String callbackUrl,
                                        List<Map<String, String>> rows, CleanOptions options, boolean async) {
        JSONObject body = new JSONObject();
        body.put("task_id", taskId);
        if (async) {
            body.put("callback_url", callbackUrl);
        }
        JSONArray rowsArr = new JSONArray();
        int idx = 1;
        for (Map<String, String> columns : rows) {
            JSONObject row = new JSONObject();
            // 显式提供行号，与对方契约（api-design 示例）对齐
            row.put("index", idx++);
            row.put("columns", columns);
            rowsArr.add(row);
        }
        body.put("rows", rowsArr);
        return body;
    }

    /**
     * 提交异步清洗任务，返回 true 表示外部已接受（202）
     */
    public boolean submitAsync(String taskId, String callbackUrl,
                               List<Map<String, String>> rows, CleanOptions options) {
        String url = baseUrl() + "/api/v1/clean/async";
        JSONObject body = buildRequestBody(taskId, callbackUrl, rows, options, true);
        try {
            HttpEntity<String> entity = new HttpEntity<>(body.toJSONString(), headers());
            org.springframework.http.ResponseEntity<String> resp =
                    restTemplate.postForEntity(url, entity, String.class);
            boolean accepted = resp.getStatusCode() == HttpStatus.ACCEPTED;
            log.info("提交异步清洗任务 {} -> {}，HTTP={}", taskId, url, resp.getStatusCode());
            return accepted;
        } catch (Exception e) {
            log.error("提交异步清洗任务 {} 失败: {}", taskId, e.getMessage(), e);
            return false;
        }
    }

    /**
     * 提交同步清洗任务（≤10 条），直接返回结果
     */
    public CallbackPayload submitSync(String taskId,
                                      List<Map<String, String>> rows, CleanOptions options) {
        String url = baseUrl() + "/api/v1/clean";
        JSONObject body = buildRequestBody(taskId, null, rows, options, false);
        HttpEntity<String> entity = new HttpEntity<>(body.toJSONString(), headers());
        org.springframework.http.ResponseEntity<String> resp =
                restTemplate.postForEntity(url, entity, String.class);
        if (!resp.getStatusCode().is2xxSuccessful() || resp.getBody() == null) {
            throw new RuntimeException("外部同步清洗返回异常: " + resp.getStatusCode());
        }
        return JSON.parseObject(resp.getBody(), CallbackPayload.class);
    }

    /**
     * 查询任务进度/结果（兜底轮询，与文档 6.2 对齐）
     */
    public CallbackPayload queryTask(String taskId) {
        String url = baseUrl() + "/api/v1/clean/" + taskId;
        HttpEntity<String> entity = new HttpEntity<>(headers());
        org.springframework.http.ResponseEntity<String> resp =
                restTemplate.exchange(url, org.springframework.http.HttpMethod.GET, entity, String.class);
        if (!resp.getStatusCode().is2xxSuccessful() || resp.getBody() == null) {
            return null;
        }
        return JSON.parseObject(resp.getBody(), CallbackPayload.class);
    }

    /**
     * 取消外部任务
     */
    public boolean cancelTask(String taskId) {
        String url = baseUrl() + "/api/v1/clean/" + taskId + "/cancel";
        HttpEntity<String> entity = new HttpEntity<>(headers());
        try {
            org.springframework.http.ResponseEntity<String> resp =
                    restTemplate.exchange(url, org.springframework.http.HttpMethod.POST, entity, String.class);
            return resp.getStatusCode().is2xxSuccessful();
        } catch (Exception e) {
            log.warn("取消外部任务 {} 失败: {}", taskId, e.getMessage());
            return false;
        }
    }
}
