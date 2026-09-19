package cn.ilink.vo;
import io.swagger.v3.oas.annotations.media.Schema;


import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import java.util.Date;

/**
 * 队伍成员视图 VO — 替代 TeamController.userToMemberView()
 */
@Data
public class TeamMemberViewVO {
    private Long userId;
    private String username;
    private String avatar;
    private String major;
    /** 展示用身份：队长 / 队员（沿用旧字段，前端已有判断依赖它） */
    private String role;
    /** 团队内角色：MENTOR（导师）/ STUDENT（学生） */
    private String memberRole;
    /**
     * 是否为团队创建者。
     * 同 MyTeamVO：不加注解会被序列化成 owner，前端读 isOwner 拿不到。
     */
    @JsonProperty("isOwner")
    private boolean isOwner;
    /** APPROVED=在队，PENDING=邀请待确认（仅对有权管理成员的人返回） */
    private String status;
    /** 待确认行是否由团队邀请产生（false 表示是本人申请） */
    private boolean initiatedByInvite;
    private Date joinedAt;
}
