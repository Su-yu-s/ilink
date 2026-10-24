package cn.ilink.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

@Data
@TableName("team_agent_run_event")
public class TeamAgentRunEvent {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long teamId;
    private Long runId;
    private Long seq;
    private String eventType;
    private String payloadJson;
    private Date createdAt;
}
