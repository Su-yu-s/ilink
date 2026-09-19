package cn.ilink.service;

import cn.ilink.entity.TeamApplication;
import cn.ilink.entity.TeamDemand;
import cn.ilink.mapper.TeamApplicationMapper;
import cn.ilink.mapper.TeamDemandMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;

@Service
public class TeamApplicationWorkflowService {

    private final TeamApplicationMapper teamApplicationMapper;
    private final TeamDemandMapper teamDemandMapper;
    private final NotificationService notificationService;

    public TeamApplicationWorkflowService(TeamApplicationMapper teamApplicationMapper,
                                          TeamDemandMapper teamDemandMapper,
                                          NotificationService notificationService) {
        this.teamApplicationMapper = teamApplicationMapper;
        this.teamDemandMapper = teamDemandMapper;
        this.notificationService = notificationService;
    }

    @Transactional(rollbackFor = Exception.class)
    public TeamApplication review(Long applicationId, Long reviewerId, String action, String note) {
        TeamApplication application = teamApplicationMapper.selectByIdForUpdate(applicationId);
        if (application == null) throw WorkflowException.notFound("\u7533\u8bf7\u4e0d\u5b58\u5728");

        TeamDemand team = teamDemandMapper.selectByIdForUpdate(application.getTeamId());
        if (team == null) throw WorkflowException.notFound("\u961f\u4f0d\u4e0d\u5b58\u5728");
        // \u521b\u5efa\u8005\u4e00\u5b9a\u53ef\u5ba1\u6279\uff1b\u975e\u521b\u5efa\u8005\u8d70\u5bfc\u5e08\u5224\u5b9a\u3002\u987a\u5e8f\u4e0a\u5148\u5224\u521b\u5efa\u8005\uff0c\u662f\u4e3a\u4e86\u8ba9\u5386\u53f2\u6d4b\u8bd5
        // \u91cc\u7684 selectCount \u6253\u6869\u5e8f\u5217\u4e0d\u88ab\u65b0\u589e\u67e5\u8be2\u6253\u4e71\u3002
        if (team.getCreatorId() == null || !team.getCreatorId().equals(reviewerId)) {
            if (!isMentorMember(team.getId(), reviewerId)) {
                throw WorkflowException.forbidden("\u65e0\u6743\u5904\u7406\u8be5\u7533\u8bf7");
            }
        }
        if (!"PENDING".equals(application.getStatus())) {
            throw WorkflowException.badRequest("\u8be5\u7533\u8bf7\u5df2\u88ab\u5904\u7406");
        }
        // \u56e2\u961f\u53d1\u51fa\u7684\u9080\u8bf7\u5fc5\u987b\u7531\u88ab\u9080\u8bf7\u4eba\u81ea\u5df1\u786e\u8ba4\uff0c\u521b\u5efa\u8005\u4e0d\u80fd\u66ff\u4ed6\u70b9\u540c\u610f
        if (application.getInitiatorId() != null
            && !application.getInitiatorId().equals(application.getUserId())) {
            throw WorkflowException.badRequest("\u8fd9\u662f\u56e2\u961f\u53d1\u51fa\u7684\u9080\u8bf7\uff0c\u9700\u8981\u5bf9\u65b9\u786e\u8ba4");
        }

        String normalizedAction = action == null ? "" : action.trim().toUpperCase();
        if (!"APPROVED".equals(normalizedAction) && !"REJECTED".equals(normalizedAction)) {
            throw WorkflowException.badRequest("\u65e0\u6548\u7684\u5ba1\u6279\u64cd\u4f5c");
        }
        String normalizedNote = note == null ? null : note.trim();
        if ("REJECTED".equals(normalizedAction)
            && (normalizedNote == null || normalizedNote.length() < 10)) {
            throw WorkflowException.badRequest("\u62d2\u7edd\u7406\u7531\u81f3\u5c11\u586b\u5199 10 \u4e2a\u5b57");
        }
        if (normalizedNote != null && normalizedNote.length() > 500) {
            throw WorkflowException.badRequest("\u5907\u6ce8\u4e0d\u80fd\u8d85\u8fc7 500 \u5b57");
        }

        if ("APPROVED".equals(normalizedAction) && isFull(team)) {
            throw WorkflowException.badRequest("\u961f\u4f0d\u5df2\u6ee1\uff0c\u65e0\u6cd5\u901a\u8fc7\u66f4\u591a\u7533\u8bf7");
        }

        application.setStatus(normalizedAction);
        application.setReviewerNote(normalizedNote);
        application.setReviewedAt(new Date());
        if (teamApplicationMapper.updateById(application) != 1) {
            throw new IllegalStateException("\u7533\u8bf7\u72b6\u6001\u66f4\u65b0\u5931\u8d25");
        }

        if ("APPROVED".equals(normalizedAction) && "OPEN".equals(team.getStatus()) && isFull(team)) {
            team.setStatus("TEAMING");
            team.setUpdatedAt(new Date());
            if (teamDemandMapper.updateById(team) != 1) {
                throw new IllegalStateException("\u961f\u4f0d\u72b6\u6001\u66f4\u65b0\u5931\u8d25");
            }
        }

        String teamTitle = team.getTitle() == null ? "\u672a\u77e5\u961f\u4f0d" : team.getTitle();
        String content;
        if ("APPROVED".equals(normalizedAction)) {
            content = "\u4f60\u5df2\u6210\u529f\u52a0\u5165\u961f\u4f0d\u300c" + teamTitle + "\u300d";
        } else {
            content = "\u4f60\u7684\u7533\u8bf7\u672a\u88ab\u901a\u8fc7\u300c" + teamTitle + "\u300d";
        }
        if (normalizedNote != null && !normalizedNote.isEmpty()) {
            content += "\n\u961f\u957f\u7559\u8a00\uff1a" + normalizedNote;
        }
        notificationService.create(
            application.getUserId(), reviewerId,
            "APPROVED".equals(normalizedAction) ? "TEAM_APPROVED" : "TEAM_REJECTED",
            "APPROVED".equals(normalizedAction) ? "\u7533\u8bf7\u901a\u8fc7" : "\u7533\u8bf7\u672a\u901a\u8fc7",
            content, team.getId());
        return application;
    }

    /** 在队的导师也有审批权：能邀请的人就能审批，两处保持同一套判定 */
    private boolean isMentorMember(Long teamId, Long userId) {
        if (teamId == null || userId == null) {
            return false;
        }
        return teamApplicationMapper.selectCount(
            new LambdaQueryWrapper<TeamApplication>()
                .eq(TeamApplication::getTeamId, teamId)
                .eq(TeamApplication::getUserId, userId)
                .eq(TeamApplication::getStatus, "APPROVED")
                .eq(TeamApplication::getMemberRole, TeamMembershipService.ROLE_MENTOR)) > 0;
    }

    private boolean isFull(TeamDemand team) {
        Integer requiredCount = team.getRequiredMemberCount();
        if (requiredCount == null || requiredCount <= 0) return false;
        Long approved = teamApplicationMapper.selectCount(
            new LambdaQueryWrapper<TeamApplication>()
                .eq(TeamApplication::getTeamId, team.getId())
                .eq(TeamApplication::getStatus, "APPROVED"));
        return approved != null && approved >= requiredCount;
    }

    public static class WorkflowException extends RuntimeException {
        private final int status;

        private WorkflowException(int status, String message) {
            super(message);
            this.status = status;
        }

        public int getStatus() {
            return status;
        }

        static WorkflowException notFound(String message) {
            return new WorkflowException(404, message);
        }

        static WorkflowException forbidden(String message) {
            return new WorkflowException(403, message);
        }

        static WorkflowException badRequest(String message) {
            return new WorkflowException(400, message);
        }
    }
}
