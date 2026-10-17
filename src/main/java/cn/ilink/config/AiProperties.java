package cn.ilink.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * AI 助手配置。优先级：环境变量 DASHSCOPE_API_KEY / DASHSCOPE_BASE_URL &gt; 配置文件。
 * 模型走阿里云百炼 DashScope 的 OpenAI 兼容模式（聊天/抽取 = qwen-flash，Embedding = qwen3.7-text-embedding-flash，同账号 Key 通用）。
 * 本地开发的私有 Key 放在不入库的 application-local.yml（dev 通过 spring.profiles.include 引入）；
 * prod profile 不提供任何默认值，必须由环境变量注入（见 ProductionSafetyValidator）。
 */
@Component
@ConfigurationProperties(prefix = "app.ai")
public class AiProperties {

    /** 功能总开关 */
    private boolean enabled = true;

    /** OpenAI 兼容接口基地址（DashScope 兼容模式） */
    private String baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1";

    /** API Key，从环境变量 DASHSCOPE_API_KEY 注入 */
    private String apiKey = "";

    /** 模型 ID（思考模式已在 AiClient 统一关闭） */
    private String model = "qwen3.7-flash";

    /** 单次生成 token 上限 */
    private int maxTokens = 2048;

    /** 读取超时（毫秒） */
    private int timeoutMs = 60000;

    /** Shared answer-generation temperature for B1/B2/B3 evaluation. */
    private double temperature = 0.3;

    /** 联网搜索开关（AI 答疑时补充公开网页上下文） */
    private boolean searchEnabled = true;

    /** 单次注入 prompt 的最大搜索结果条数 */
    private int searchMaxResults = 5;

    public boolean isSearchEnabled() {
        return searchEnabled;
    }

    public void setSearchEnabled(boolean searchEnabled) {
        this.searchEnabled = searchEnabled;
    }

    public int getSearchMaxResults() {
        return searchMaxResults;
    }

    public void setSearchMaxResults(int searchMaxResults) {
        this.searchMaxResults = searchMaxResults;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

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

    public int getMaxTokens() {
        return maxTokens;
    }

    public void setMaxTokens(int maxTokens) {
        this.maxTokens = maxTokens;
    }

    public int getTimeoutMs() {
        return timeoutMs;
    }

    public void setTimeoutMs(int timeoutMs) {
        this.timeoutMs = timeoutMs;
    }

    public double getTemperature() {
        return temperature;
    }

    public void setTemperature(double temperature) {
        if (!Double.isFinite(temperature) || temperature < 0 || temperature > 2) {
            throw new IllegalArgumentException("temperature must be between 0 and 2");
        }
        this.temperature = temperature;
    }

    /** 配置是否完整可用 */
    public boolean isConfigured() {
        return enabled && apiKey != null && !apiKey.trim().isEmpty()
            && baseUrl != null && !baseUrl.trim().isEmpty();
    }
}
