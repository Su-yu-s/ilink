package cn.ilink.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

@Data
@TableName("team_agent_audit_log")
public class TeamAgentAuditLog {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long teamId;
    private Long conversationId;
    private Long runId;
    private Long actionGroupId;
    private Long actionId;
    private String actorType;
    private Long actorUserId;
    private String eventType;
    private String outcome;
    private String requestId;
    private String metadataJson;
    private Date createdAt;
}
