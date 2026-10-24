package cn.ilink.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

@Data
@TableName("team_agent_run")
public class TeamAgentRun {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long teamId;
    private Long conversationId;
    private Long userId;
    private Long requestMessageId;
    private Long responseMessageId;
    private String clientRequestId;
    private String requestedMode;
    private String resolvedRoute;
    private String status;
    private String inputHash;
    private String contextSnapshotJson;
    private String modelName;
    private String promptVersion;
    private Integer rounds;
    private Integer knowledgeToolCalls;
    private Integer businessToolCalls;
    private Integer modelCallCount;
    private Long eventSeq;
    private Integer promptTokens;
    private Integer completionTokens;
    private Long estimatedCostMicros;
    private Long elapsedMs;
    private String cancelRequestId;
    private Date cancelRequestedAt;
    private String workerId;
    private String workerLeaseToken;
    private Date leaseUntil;
    private String errorCode;
    private String errorMessage;
    private Date createdAt;
    private Date startedAt;
    private Date finishedAt;
    private Integer version;

    public static final String STATUS_QUEUED = "QUEUED";
    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_COMPLETED = "COMPLETED";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_CANCELLED = "CANCELLED";
}
