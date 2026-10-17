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
    /**
     * 展示名。历史上这里放的是内部 username，现已改为 {@link cn.ilink.util.DisplayNameResolver} 的解析结果。
     * 前端读 {@code username} 或 {@code displayName} 都能拿到同一个值。
     */
    private String username;
    /** 与 {@code username} 同值的显式展示名字段，供新前端代码使用 */
    private String displayName;
    /** 展示名首字，供头像占位使用 */
    private String avatarInitial;
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
