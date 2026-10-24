package cn.ilink.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

@Data
@TableName("team_collab_edit_lease")
public class TeamCollabEditLease {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long teamId;
    private String assetType;
    private Long assetId;
    private Long userId;
    private String tokenHash;
    private Integer baseVersion;
    private Date acquiredAt;
    private Date renewedAt;
    private Date expiresAt;
}
