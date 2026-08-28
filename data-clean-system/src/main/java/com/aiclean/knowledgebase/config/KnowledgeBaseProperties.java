package com.aiclean.knowledgebase.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 知识库系统对接配置
 * 对应 application.yml 中 app.knowledge-base.* 配置项。
 * 本系统作为客户端，调用 api-design.md 4.4 章节定义的外部知识库接口，
 * 把 /opt/kb 下的文件入库（向量化），并根据返回结果回写 file_record 导入状态。
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.knowledge-base")
public class KnowledgeBaseProperties {

    /** 是否启用知识库入库（默认开启） */
    private boolean enabled = true;

    /** 知识库服务地址，如 http://kb-service:8000 */
    private String baseUrl = "";

    /** 调用知识库接口的 API Key（Bearer Token，仅原始 Key） */
    private String apiKey = "";

    /** 调用超时（秒） */
    private int timeoutSeconds = 30;
}
