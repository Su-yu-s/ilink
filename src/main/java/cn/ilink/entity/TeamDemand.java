package cn.ilink.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.util.Date;

@Data
@TableName("team_demand")
public class TeamDemand {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String title;
    private String description;
    private Integer competitionId;
    private String requiredSkills;
    private Integer requiredMemberCount;
    private Date deadline;
    private String status;
    private Long creatorId;
    private Date createdAt;
    private Date updatedAt;
}
