package cn.ilink.vo;
import io.swagger.v3.oas.annotations.media.Schema;


import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import java.util.Date;

/**
 * 我的团队列表项 VO — 替代 TeamController.myJoinedTeams() 中的 Map
 */
@Data
public class MyTeamVO {
    private Long teamId;
    private String teamTitle;
    private String status;
    private Date joinedAt;
    /** JSON 里必须叫 isCreator：Lombok 的 isCreator() 会被 Jackson 认成 creator，前端读的一直是 undefined */
    @JsonProperty("isCreator")
    private boolean isCreator;
    /** 我在该团队里的角色：MENTOR / STUDENT（创建者由 User.role 推导） */
    private String memberRole;
    /** 我能否邀请成员 / 审批申请 */
    private boolean canManageMembers;
    /** 待确认的邀请数，用于卡片上的琥珀色提示 */
    private long pendingInviteCount;
}
