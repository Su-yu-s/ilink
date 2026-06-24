package cn.ilink.vo;

import lombok.Data;
import java.util.Date;

@Data
public class TaskParticipantVO {
    private Long id;
    private Long taskId;
    private Long userId;
    private String username;
    private String realName;
    private String avatar;
    private String role;
    private String roleDesc;
    private String major;
    private Double contributionHours;
    private Double contributionRate;
    private Date joinedAt;
    private String status;
}
