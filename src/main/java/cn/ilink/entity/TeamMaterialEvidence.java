package cn.ilink.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

@Data
@TableName("team_material_evidence")
public class TeamMaterialEvidence {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long teamId;
    private Long requirementId;
    private String assetType;
    private Long assetId;
    private Long evidenceChunkId;
    private String evidenceExcerpt;
    private String matchStatus;
    private Long createdBy;
    private Date createdAt;
}
