package cn.ilink.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

@Data
@TableName("team_agent_message")
public class TeamAgentMessage {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long teamId;
    private Long conversationId;
    private Long seq;
    private String role;
    private Long authorUserId;
    private String content;
    private String contentFormat;
    private String status;
    private String citationsJson;
    private Long runId;
    private Date createdAt;
    private Date updatedAt;
    private Integer version;

    public static final String ROLE_USER = "USER";
    public static final String ROLE_ASSISTANT = "ASSISTANT";
    public static final String ROLE_SYSTEM_NOTICE = "SYSTEM_NOTICE";
    public static final String STATUS_STREAMING = "STREAMING";
    public static final String STATUS_COMPLETE = "COMPLETE";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_CANCELLED = "CANCELLED";
    public static final String FORMAT_MARKDOWN = "MARKDOWN";
}
