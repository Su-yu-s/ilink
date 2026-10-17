package cn.ilink.vo;
import io.swagger.v3.oas.annotations.media.Schema;


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
    /** true=这是我（队长）发出的邀请，等待对方确认；false/absent=对方申请加入，等待我审批 */
    private Boolean invitedByMe;
    private List<SkillViewVO> skills;
}
