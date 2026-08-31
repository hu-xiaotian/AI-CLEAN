package com.aiclean.knowledgebase.client;

import com.aiclean.knowledgebase.config.KnowledgeBaseProperties;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
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

    private String urlEncode(String value) {
        try {
            return java.net.URLEncoder.encode(value == null ? "" : value, "UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            // UTF-8 为标准字符集，正常情况下不会触发
            throw new RuntimeException(e);
        }
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
     * 查询知识库文件入库情况（不落库，直接透传外部接口返回）。
     *
     * @return 外部接口原始响应（含 total 与 files 列表），失败时返回 null
     */
    public JSONObject listFiles() {
        String url = baseUrl() + "/api/v1/knowledge/files";
        HttpEntity<String> entity = new HttpEntity<>(headers());
        try {
            org.springframework.http.ResponseEntity<String> resp =
                    restTemplate.exchange(url, org.springframework.http.HttpMethod.GET, entity, String.class);
            if (!resp.getStatusCode().is2xxSuccessful() || resp.getBody() == null) {
                log.warn("知识库文件列表查询失败 HTTP={}", resp.getStatusCode());
                return null;
            }
            log.info("知识库文件列表查询成功 HTTP={}", resp.getStatusCode());
            return JSON.parseObject(resp.getBody());
        } catch (Exception e) {
            log.error("知识库文件列表查询异常 {}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * 上传文件到知识库（multipart/form-data，不落库，直接透传外部接口返回）。
     *
     * @param fileName        文件名（用于 fallback，当 relativePath 为空时使用）
     * @param content         文件二进制内容
     * @param relativePath    相对 KB_FILE_DIR 的路径，可空（空则外部用文件名）
     * @return 外部接口原始响应，失败时返回 null
     */
    public JSONObject uploadFile(String fileName, byte[] content, String relativePath) {
        String url = baseUrl() + "/api/v1/knowledge/files";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        if (properties.getApiKey() != null && !properties.getApiKey().isEmpty()) {
            headers.setBearerAuth(properties.getApiKey());
        }
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        final String name = fileName != null ? fileName : "file";
        ByteArrayResource resource = new ByteArrayResource(content) {
            @Override
            public String getFilename() {
                return name;
            }
        };
        body.add("file", resource);
        if (relativePath != null && !relativePath.isEmpty()) {
            body.add("relative_path", relativePath);
        }
        HttpEntity<MultiValueMap<String, Object>> entity = new HttpEntity<>(body, headers);
        try {
            org.springframework.http.ResponseEntity<String> resp =
                    restTemplate.exchange(url, org.springframework.http.HttpMethod.POST, entity, String.class);
            if (!resp.getStatusCode().is2xxSuccessful() || resp.getBody() == null) {
                log.warn("知识库文件上传失败 fileName={} HTTP={}", fileName, resp.getStatusCode());
                return null;
            }
            log.info("知识库文件上传成功 fileName={} HTTP={}", fileName, resp.getStatusCode());
            return JSON.parseObject(resp.getBody());
        } catch (Exception e) {
            log.error("知识库文件上传异常 fileName={} {}", fileName, e.getMessage(), e);
            return null;
        }
    }

    /**
     * 知识库检索（不落库，直接透传外部接口返回）。
     *
     * @param query        检索关键词
     * @param topK         返回条数，默认 5
     * @param categoryCode 分类代码过滤，可空
     * @param field        限定字段，可空
     * @return 外部接口原始响应（含 query 与 hits 列表），失败时返回 null
     */
    public JSONObject search(String query, Integer topK, String categoryCode, String field) {
        StringBuilder url = new StringBuilder(baseUrl()).append("/api/v1/knowledge/search")
                .append("?q=").append(urlEncode(query));
        if (topK != null) {
            url.append("&top_k=").append(topK);
        }
        if (categoryCode != null && !categoryCode.isEmpty()) {
            url.append("&category_code=").append(urlEncode(categoryCode));
        }
        if (field != null && !field.isEmpty()) {
            url.append("&field=").append(urlEncode(field));
        }
        HttpEntity<String> entity = new HttpEntity<>(headers());
        try {
            org.springframework.http.ResponseEntity<String> resp =
                    restTemplate.exchange(url.toString(), org.springframework.http.HttpMethod.GET, entity, String.class);
            if (!resp.getStatusCode().is2xxSuccessful() || resp.getBody() == null) {
                log.warn("知识库检索失败 query={} HTTP={}", query, resp.getStatusCode());
                return null;
            }
            log.info("知识库检索成功 query={} HTTP={}", query, resp.getStatusCode());
            return JSON.parseObject(resp.getBody());
        } catch (Exception e) {
            log.error("知识库检索异常 query={} {}", query, e.getMessage(), e);
            return null;
        }
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
