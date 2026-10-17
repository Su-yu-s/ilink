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
    /** 项目方向：技术开发/创意设计/市场营销/学术研究 */
    private String category;
    /** 自填竞赛名称（目录中没有时使用，不回写竞赛目录） */
    private String customCompetition;
    private String requiredSkills;
    private Integer requiredMemberCount;
    private Date deadline;
    private String status;
    private Long creatorId;
    private Date createdAt;
    private Date updatedAt;
}
