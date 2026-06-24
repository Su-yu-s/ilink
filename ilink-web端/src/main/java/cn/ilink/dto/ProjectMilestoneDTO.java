package cn.ilink.dto;

import lombok.Data;
import java.util.Date;

@Data
public class ProjectMilestoneDTO {
    private Long teamId;
    private String milestoneName;
    private String milestoneDescription;
    private Date dueDate;
    private Integer completionRate;
    private String deliverables;
    private Long createdBy;
}
