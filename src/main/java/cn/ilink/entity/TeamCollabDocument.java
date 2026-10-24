package cn.ilink.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

@Data
@TableName("team_collab_document")
public class TeamCollabDocument {
    public static final String VISIBILITY_TEAM = "TEAM";
    public static final String VISIBILITY_RESTRICTED = "RESTRICTED";
    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_ARCHIVED = "ARCHIVED";

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long teamId;
    private String title;
    private String contentMarkdown;
    private String visibility;
    private String status;
    private Integer version;
    private String contentHash;
    private Long creatorUserId;
    private Long lastEditorUserId;
    private Date createdAt;
    private Date updatedAt;
    private Date deletedAt;
}
