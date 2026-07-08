package cn.ilink.vo;

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
    private String role;
    private Date joinedAt;
}
