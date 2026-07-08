package cn.ilink.vo;
import io.swagger.v3.oas.annotations.media.Schema;


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
    private boolean isFull;
    private boolean canEdit;
    private boolean canDelete;
    private boolean canMoveToTeaming;
    private boolean canClose;
    private List<TeamMemberViewVO> members;
}
