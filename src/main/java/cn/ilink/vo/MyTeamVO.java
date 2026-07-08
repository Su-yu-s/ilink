package cn.ilink.vo;

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
    private boolean isCreator;
}
