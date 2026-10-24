package cn.ilink.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

@Data
@TableName("team_agent_action_group")
public class TeamAgentActionGroup {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long teamId;
    private Long conversationId;
    private Long runId;
    private Long initiatorUserId;
    private String status;
    private String clientRequestId;
    private String decisionType;
    private String requestHash;
    private Date expiresAt;
    private Date decidedAt;
    private Date executedAt;
    private String errorCode;
    private String errorMessage;
    private Date createdAt;
    private Date updatedAt;
    private Integer version;

    public static final String PENDING = "PENDING";
    public static final String CONFIRMING = "CONFIRMING";
    public static final String EXECUTED = "EXECUTED";
    public static final String FAILED = "FAILED";
    public static final String EXPIRED = "EXPIRED";
    public static final String CANCELLED = "CANCELLED";
}
