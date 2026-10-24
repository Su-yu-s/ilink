package cn.ilink.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

@Data
@TableName("team_collab_acl")
public class TeamCollabAcl {
    public static final String PERMISSION_VIEW = "VIEW";
    public static final String PERMISSION_EDIT = "EDIT";
    public static final String PERMISSION_MANAGE = "MANAGE";

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long teamId;
    private String assetType;
    private Long assetId;
    private Long userId;
    private String permission;
    private Long grantedBy;
    private Date createdAt;
    private Date updatedAt;
}
