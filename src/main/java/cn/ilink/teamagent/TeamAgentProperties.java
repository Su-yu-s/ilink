package cn.ilink.teamagent;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.team-agent")
public class TeamAgentProperties {
    private boolean enabled = true;
    private boolean actionsEnabled = true;
    private boolean deepGraphRagEnabled = true;
    private int workerConcurrency = 4;
    private int queueCapacity = 32;
    private long leaseSeconds = 120;
    /** 供 pi Agent 运行时调用的内部工具端点；默认关闭，避免误暴露。 */
    private Internal internal = new Internal();
    /** 深度检索改由 pi 执行；默认关闭，失败回退到进程内 Agentic GraphRAG。 */
    private PiAgent piAgent = new PiAgent();

    public static class PiAgent {
        private boolean enabled = false;
        private String baseUrl = "http://127.0.0.1:8787";
        private String token = "";
        private int connectTimeoutMs = 3000;
        private int readTimeoutSeconds = 120;
        /** 为空表示所有团队；非空只对这些团队启用。 */
        private java.util.List<Long> teamAllowlist = java.util.List.of();

        public boolean allows(Long teamId) {
            return enabled && teamId != null
                && (teamAllowlist == null || teamAllowlist.isEmpty() || teamAllowlist.contains(teamId));
        }

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public String getToken() { return token; }
        public void setToken(String token) { this.token = token == null ? "" : token; }
        public int getConnectTimeoutMs() { return connectTimeoutMs; }
        public void setConnectTimeoutMs(int value) { this.connectTimeoutMs = Math.max(500, value); }
        public int getReadTimeoutSeconds() { return readTimeoutSeconds; }
        public void setReadTimeoutSeconds(int value) { this.readTimeoutSeconds = Math.max(10, value); }
        public java.util.List<Long> getTeamAllowlist() { return teamAllowlist; }
        public void setTeamAllowlist(java.util.List<Long> value) {
            this.teamAllowlist = value == null ? java.util.List.of() : java.util.List.copyOf(value);
        }
    }

    public PiAgent getPiAgent() { return piAgent; }
    public void setPiAgent(PiAgent value) { this.piAgent = value == null ? new PiAgent() : value; }

    public static class Internal {
        private boolean enabled = false;
        /** 共享令牌，必须通过环境变量注入，不得写入配置文件。 */
        private String token = "";

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getToken() { return token; }
        public void setToken(String token) { this.token = token == null ? "" : token; }
    }

    public Internal getInternal() { return internal; }
    public void setInternal(Internal internal) {
        this.internal = internal == null ? new Internal() : internal;
    }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public boolean isActionsEnabled() { return actionsEnabled; }
    public void setActionsEnabled(boolean actionsEnabled) { this.actionsEnabled = actionsEnabled; }
    public boolean isDeepGraphRagEnabled() { return deepGraphRagEnabled; }
    public void setDeepGraphRagEnabled(boolean deepGraphRagEnabled) {
        this.deepGraphRagEnabled = deepGraphRagEnabled;
    }
    public int getWorkerConcurrency() { return workerConcurrency; }
    public void setWorkerConcurrency(int workerConcurrency) {
        this.workerConcurrency = Math.max(1, Math.min(workerConcurrency, 16));
    }
    public int getQueueCapacity() { return queueCapacity; }
    public void setQueueCapacity(int queueCapacity) {
        this.queueCapacity = Math.max(4, Math.min(queueCapacity, 256));
    }
    public long getLeaseSeconds() { return leaseSeconds; }
    public void setLeaseSeconds(long leaseSeconds) {
        this.leaseSeconds = Math.max(60, Math.min(leaseSeconds, 600));
    }
}
