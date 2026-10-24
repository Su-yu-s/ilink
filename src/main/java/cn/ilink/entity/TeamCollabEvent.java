package cn.ilink.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

@Data
@TableName("team_collab_event")
public class TeamCollabEvent {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long teamId;
    private String assetType;
    private Long assetId;
    private Long actorUserId;
    private String eventType;
    private String outcome;
    private Integer version;
    private String requestId;
    private String metadataJson;
    private Date createdAt;
}
