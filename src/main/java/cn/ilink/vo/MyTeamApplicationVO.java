package cn.ilink.vo;
import io.swagger.v3.oas.annotations.media.Schema;


import lombok.Data;
import java.util.Date;

/**
 * 我的组队申请 VO — 替代 TeamController.myTeamApplications() 中的 Map
 */
@Data
public class MyTeamApplicationVO {
    private Long id;
    private Long teamId;
    private String teamTitle;
    private String status;
    private Date createdAt;
}
