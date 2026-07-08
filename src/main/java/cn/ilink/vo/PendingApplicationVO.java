package cn.ilink.vo;

import lombok.Data;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * 待审核组队申请 VO — 替代 TeamController.getPendingApplications() 中的 Map
 */
@Data
public class PendingApplicationVO {
    private Long id;
    private Long teamId;
    private String message;
    private Date createdAt;
    private String teamName;
    private String applicantName;
    private String applicantAvatar;
    private String applicantMajor;
    private String applicantGrade;
    private String applicantSchool;
    private Long applicantUserId;
    private List<SkillViewVO> skills;
}
