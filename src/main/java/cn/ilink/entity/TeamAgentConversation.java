package cn.ilink.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

@Data
@TableName("team_agent_conversation")
public class TeamAgentConversation {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long teamId;
    private Long ownerUserId;
    private String clientRequestId;
    private String createRequestHash;
    private String title;
    private String titleSource;
    private String status;
    private String contextSnapshotJson;
    private Date lastMessageAt;
    private Long messageSeq;
    private Date createdAt;
    private Date updatedAt;
    private Integer version;

    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_DELETED = "DELETED";
    public static final String TITLE_USER = "USER";
    public static final String TITLE_AUTO = "AUTO";
}
