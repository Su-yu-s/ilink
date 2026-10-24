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
