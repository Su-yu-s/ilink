package cn.ilink.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * AI 助手配置。优先级：环境变量 AGNES_API_KEY / AGNES_BASE_URL &gt; 配置文件。
 * 本地开发的私有 Key 放在不入库的 application-local.yml（dev 通过 spring.profiles.include 引入）；
 * prod profile 不提供任何默认值，必须由环境变量注入（见 ProductionSafetyValidator）。
 */
@Component
@ConfigurationProperties(prefix = "app.ai")
public class AiProperties {

    /** 功能总开关 */
    private boolean enabled = true;

    /** OpenAI 兼容接口基地址（Agnes 国内节点；国际站为 https://apihub.agnes-ai.com/v1） */
    private String baseUrl = "https://apihub.agnes-ai.cn/v1";

    /** API Key，从环境变量 AGNES_API_KEY 注入 */
    private String apiKey = "";

    /** 模型 ID */
    private String model = "agnes-2.5-flash";

    /** 单次生成 token 上限 */
    private int maxTokens = 2048;

    /** 读取超时（毫秒） */
    private int timeoutMs = 60000;

    /** 每用户每日调用上限 */
    private int dailyQuota = 20;

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

    public int getDailyQuota() {
        return dailyQuota;
    }

    public void setDailyQuota(int dailyQuota) {
        this.dailyQuota = dailyQuota;
    }

    /** 配置是否完整可用 */
    public boolean isConfigured() {
        return enabled && apiKey != null && !apiKey.trim().isEmpty()
            && baseUrl != null && !baseUrl.trim().isEmpty();
    }
}
