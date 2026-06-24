package cn.ilink.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.util.Date;

@Data
@TableName("project_milestones")
public class ProjectMilestone {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long teamId;
    private String milestoneName;
    private String milestoneDescription;
    private Date dueDate;
    private Date completedDate;
    private Integer completionRate;
    private String deliverables;
    private Long createdBy;
    private Date createdAt;
    private Date updatedAt;
    private String status;

    public enum MilestoneStatus {
        PENDING("待处理"),
        IN_PROGRESS("进行中"),
        COMPLETED("已完成"),
        DELAYED("已延期");

        private final String description;
        MilestoneStatus(String description) {
            this.description = description;
        }
        public String getDescription() {
            return description;
        }
    }
}
