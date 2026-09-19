package cn.ilink.vo;
import io.swagger.v3.oas.annotations.media.Schema;


import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * 组队需求详情 VO — 替代 TeamController.teamDemandToMap()
 */
@Data
public class TeamDemandVO {
    private Long id;
    private String title;
    private String description;
    private Long competitionId;
    private String requiredSkills;
    private Integer requiredMemberCount;
    private Object deadline;
    private String status;
    private String statusLabel;
    private Long creatorId;
    private Date createdAt;
    private Date updatedAt;
    private Map<String, Object> creatorPreview;
    private long applicationCount;
    private long approvedMemberCount;
    private long currentMemberCount;
    /** 同 MyTeamVO.isCreator：不注解会变成 full，前端 profile.js 读的就是 isFull */
    @JsonProperty("isFull")
    private boolean isFull;
    private boolean canEdit;
    private boolean canDelete;
    private boolean canMoveToTeaming;
    private boolean canClose;
    /** 当前用户能否邀请成员 / 审批申请（创建者或在队导师）；仅「我的团队」接口返回 */
    private boolean canManageMembers;
    /** 待确认的邀请数，用于卡片上的琥珀色提示；仅「我的团队」接口返回 */
    private long pendingInviteCount;
    private List<TeamMemberViewVO> members;
}
