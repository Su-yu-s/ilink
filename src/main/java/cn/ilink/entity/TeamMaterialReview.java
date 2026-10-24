package cn.ilink.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

@Data
@TableName("team_material_review")
public class TeamMaterialReview {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long teamId;
    private Long requirementId;
    private String status;
    private String note;
    private Long reviewedBy;
    private Date reviewedAt;
    private Integer version;
}
