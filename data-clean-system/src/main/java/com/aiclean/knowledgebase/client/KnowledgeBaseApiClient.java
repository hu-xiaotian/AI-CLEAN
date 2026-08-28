package com.aiclean.knowledgebase.client;

import com.aiclean.knowledgebase.config.KnowledgeBaseProperties;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

/**
 * 知识库系统客户端
 * 调用 api-design.md 4.4.4 接口 POST /api/v1/knowledge/files（JSON relative_path 方式）：
 *  - 请求体 {"relative_path": "category/property/a.docx"}
 *  - 成功响应 200 {"message":"入库成功", "relative_path": "...", "status":"active", ...}
 * 本系统据返回判断是否入库成功，成功则由上层服务回写 file_record 的导入状态。
 */
@Slf4j
@Component
public class KnowledgeBaseApiClient {

    private final KnowledgeBaseProperties properties;
    private final RestTemplate restTemplate;

    public KnowledgeBaseApiClient(KnowledgeBaseProperties properties) {
        this.properties = properties;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.getTimeoutSeconds() * 1000);
        factory.setReadTimeout(properties.getTimeoutSeconds() * 1000);
        this.restTemplate = new RestTemplate(factory);
    }

    private String baseUrl() {
        String u = properties.getBaseUrl();
        if (u == null || u.isEmpty()) {
            throw new IllegalStateException("知识库服务地址未配置（app.knowledge-base.base-url）");
        }
        return u.endsWith("/") ? u.substring(0, u.length() - 1) : u;
    }

    private HttpHeaders headers() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (properties.getApiKey() != null && !properties.getApiKey().isEmpty()) {
            headers.setBearerAuth(properties.getApiKey());
        }
        return headers;
    }

    /**
     * 按相对路径把文件入库到知识库。
     *
     * @param relativePath 相对于 KB_FILE_DIR 的路径
     * @return 入库是否成功
     */
    public boolean ingest(String relativePath) {
        String url = baseUrl() + "/api/v1/knowledge/files";
        JSONObject body = new JSONObject();
        body.put("relative_path", relativePath);
        HttpEntity<String> entity = new HttpEntity<>(body.toJSONString(), headers());
        try {
            org.springframework.http.ResponseEntity<String> resp =
                    restTemplate.postForEntity(url, entity, String.class);
            if (!resp.getStatusCode().is2xxSuccessful() || resp.getBody() == null) {
                log.warn("知识库入库失败 relativePath={} HTTP={}", relativePath, resp.getStatusCode());
                return false;
            }
            JSONObject root = JSON.parseObject(resp.getBody());
            // 4.4.4 成功响应 message="入库成功"，status="active"
            boolean ok = resp.getStatusCode() == HttpStatus.OK
                    && "入库成功".equals(root.getString("message"));
            log.info("知识库入库 relativePath={} HTTP={} message={} status={}",
                    relativePath, resp.getStatusCode(), root.getString("message"), root.getString("status"));
            return ok;
        } catch (Exception e) {
            log.error("知识库入库异常 relativePath={} {}", relativePath, e.getMessage(), e);
            return false;
        }
    }
}
