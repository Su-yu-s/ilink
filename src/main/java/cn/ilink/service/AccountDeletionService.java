package cn.ilink.service;

import cn.ilink.entity.TeamApplication;
import cn.ilink.entity.TeamDemand;
import cn.ilink.service.impl.TeamApplicationServiceImpl;
import cn.ilink.service.impl.TeamDemandServiceImpl;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 自助注销账号的前置校验。
 *
 * <p>真正的级联删除复用 {@link AdminDataService#deleteUser(Long)}——管理员删号与用户自助注销
 * 走同一条清理路径，避免两处逻辑漂移导致某些表被漏清（历史 bug 类型：资料库文件、
 * 任务子表、推荐日志都曾各自漏过一次）。
 *
 * <p>本类只负责「能不能注销」的判断，不碰数据。
 */
@Service
public class AccountDeletionService {

    /** 未解散的团队状态：与 TeamDemand 的语义一致，DISSOLVED 之外都算仍存在 */
    private static final String TEAM_STATUS_DISSOLVED = "DISSOLVED";
    private static final String APPLICATION_STATUS_APPROVED = "APPROVED";

    private final TeamDemandServiceImpl teamDemandService;
    private final TeamApplicationServiceImpl teamApplicationService;
    private final AdminDataService adminDataService;

    public AccountDeletionService(TeamDemandServiceImpl teamDemandService,
                                  TeamApplicationServiceImpl teamApplicationService,
                                  AdminDataService adminDataService) {
        this.teamDemandService = teamDemandService;
        this.teamApplicationService = teamApplicationService;
        this.adminDataService = adminDataService;
    }

    /**
     * 该用户作为创建者、且队内还有其他已通过成员的未解散团队。
     *
     * <p>返回空列表表示可以注销。非空时调用方应拒绝注销并把这些团队名回给用户，
     * 提示先转让或解散——否则这些成员会在无通知的情况下失去队伍。
     */
    public List<String> findBlockingTeams(Long userId) {
        if (userId == null) {
            return Collections.emptyList();
        }
        List<TeamDemand> created = teamDemandService.list(new LambdaQueryWrapper<TeamDemand>()
            .eq(TeamDemand::getCreatorId, userId));
        if (created.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> blocking = new ArrayList<>();
        for (TeamDemand team : created) {
            if (team.getId() == null) {
                continue;
            }
            if (TEAM_STATUS_DISSOLVED.equalsIgnoreCase(team.getStatus())) {
                continue;
            }
            long approved = teamApplicationService.count(new LambdaQueryWrapper<TeamApplication>()
                .eq(TeamApplication::getTeamId, team.getId())
                .eq(TeamApplication::getStatus, APPLICATION_STATUS_APPROVED));
            if (approved > 0) {
                blocking.add(team.getTitle() == null || team.getTitle().trim().isEmpty()
                    ? "未命名团队"
                    : team.getTitle().trim());
            }
        }
        return blocking.stream().distinct().collect(Collectors.toList());
    }

    /**
     * 执行注销：级联删除 + 吊销 remember-me + 失效全部会话。
     *
     * <p>调用方必须先完成密码与确认文本校验；本方法不再做身份校验。
     */
    public void deleteAccount(Long userId) {
        List<String> blocking = findBlockingTeams(userId);
        if (!blocking.isEmpty()) {
            throw new AccountDeletionBlockedException("你仍是以下团队的创建者：" + String.join("、", blocking)
                + "。请先转让或解散团队后再注销账号。");
        }
        adminDataService.deleteUser(userId);
    }
}
