package cn.ilink.vo;

import lombok.Data;
import java.util.Date;

@Data
public class ProjectMilestoneVO {
    private Long id;
    private Long teamId;
    private String milestoneName;
    private String milestoneDescription;
    private Date dueDate;
    private Date completedDate;
    private Integer completionRate;
    private String deliverables;
    private Long createdBy;
    private String creatorName;
    private Date createdAt;
    private Date updatedAt;
    private String status;
    private String statusDesc;
}
