package cn.ilink.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 知识图谱 Embedding 配置（Qwen 文本向量模型，OpenAI 兼容接口）。
 * 与 Agnes 聊天模型解耦：聊天走 app.ai，向量走 app.ai.embedding。
 * 优先级：环境变量 EMBEDDING_API_KEY / EMBEDDING_BASE_URL / EMBEDDING_MODEL &gt; 配置文件。
 * 未配置时 isConfigured()=false，向量化与向量检索自动降级（抽取入库不受影响）。
 */
@Component
@ConfigurationProperties(prefix = "app.ai.embedding")
public class AiEmbeddingProperties {

    /** OpenAI 兼容 Embedding 接口基地址（阿里云 DashScope 兼容模式） */
    private String baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1";

    /** API Key，从环境变量 EMBEDDING_API_KEY 注入 */
    private String apiKey = "";

    /** Embedding 模型 ID */
    private String model = "qwen3.7-text-embedding-flash";

    /** 读取超时（毫秒） */
    private int timeoutMs = 30000;

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public int getTimeoutMs() {
        return timeoutMs;
    }

    public void setTimeoutMs(int timeoutMs) {
        this.timeoutMs = timeoutMs;
    }

    /** 配置是否完整可用 */
    public boolean isConfigured() {
        return apiKey != null && !apiKey.trim().isEmpty()
            && baseUrl != null && !baseUrl.trim().isEmpty()
            && model != null && !model.trim().isEmpty();
    }
}
