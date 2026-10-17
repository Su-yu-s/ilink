package cn.ilink.service;

import cn.ilink.common.UserSessionRegistry;
import cn.ilink.service.NotificationService;
import cn.ilink.entity.Asset;
import cn.ilink.entity.ChatMessage;
import cn.ilink.entity.CommunityComment;
import cn.ilink.entity.CommunityPost;
import cn.ilink.entity.CommunityPostFavorite;
import cn.ilink.entity.CommunityPostLike;
import cn.ilink.entity.Notification;
import cn.ilink.entity.ProjectApplication;
import cn.ilink.entity.ProjectMilestone;
import cn.ilink.entity.RecommendationLog;
import cn.ilink.entity.TaskComment;
import cn.ilink.entity.TaskParticipant;
import cn.ilink.entity.TaskSubmission;
import cn.ilink.entity.TeacherApplication;
import cn.ilink.entity.TeamApplication;
import cn.ilink.entity.TeamDemand;
import cn.ilink.entity.TeamLibraryFile;
import cn.ilink.entity.TeamTask;
import cn.ilink.entity.User;
import cn.ilink.entity.UserSkill;
import cn.ilink.mapper.ChatMessageMapper;
import cn.ilink.mapper.CommunityCommentMapper;
import cn.ilink.mapper.CommunityPostFavoriteMapper;
import cn.ilink.mapper.CommunityPostLikeMapper;
import cn.ilink.mapper.NotificationMapper;
import cn.ilink.mapper.ProjectMilestoneMapper;
import cn.ilink.mapper.RecommendationLogMapper;
import cn.ilink.mapper.TaskCommentMapper;
import cn.ilink.mapper.TaskParticipantMapper;
import cn.ilink.mapper.TaskSubmissionMapper;
import cn.ilink.mapper.TeamLibraryFileMapper;
import cn.ilink.mapper.TeamTaskMapper;
import cn.ilink.mapper.UserSkillMapper;
import cn.ilink.service.impl.AssetServiceImpl;
import cn.ilink.service.impl.CommunityPostServiceImpl;
import cn.ilink.service.impl.ProjectApplicationServiceImpl;
import cn.ilink.service.impl.TeacherApplicationServiceImpl;
import cn.ilink.service.impl.TeamApplicationServiceImpl;
import cn.ilink.service.impl.TeamDemandServiceImpl;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.cache.annotation.CacheEvict;

import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class AdminDataService {

    private static final Logger log = LoggerFactory.getLogger(AdminDataService.class);

    private final UserService userService;
    private final TeamDemandServiceImpl teamDemandService;
    private final TeamApplicationServiceImpl teamApplicationService;
    private final TeacherApplicationServiceImpl teacherApplicationService;
    private final ProjectApplicationServiceImpl projectApplicationService;
    private final AssetServiceImpl assetService;
    private final AssetLifecycleService assetLifecycleService;
    private final CommunityPostServiceImpl communityPostService;
    private final CommunityCommentMapper communityCommentMapper;
    private final CommunityPostLikeMapper communityPostLikeMapper;
    private final CommunityPostFavoriteMapper communityPostFavoriteMapper;
    private final TeamTaskMapper teamTaskMapper;
    private final TaskParticipantMapper taskParticipantMapper;
    private final TaskCommentMapper taskCommentMapper;
    private final TaskSubmissionMapper taskSubmissionMapper;
    private final ProjectMilestoneMapper projectMilestoneMapper;
    private final ChatMessageMapper chatMessageMapper;
    private final TeamLibraryFileMapper teamLibraryFileMapper;
    private final RecommendationLogMapper recommendationLogMapper;
    private final NotificationMapper notificationMapper;
    private final UserSkillMapper userSkillMapper;
    private final RememberMeService rememberMeService;
    private final UserSessionRegistry sessionRegistry;

    /** 管理删除时向内容所有者发通知；测试环境未装配时为 null，发通知处已判空跳过 */
    @Autowired(required = false)
    private NotificationService notificationService;

    public AdminDataService(UserService userService,
                            TeamDemandServiceImpl teamDemandService,
                            TeamApplicationServiceImpl teamApplicationService,
                            TeacherApplicationServiceImpl teacherApplicationService,
                            ProjectApplicationServiceImpl projectApplicationService,
                            AssetServiceImpl assetService,
                            AssetLifecycleService assetLifecycleService,
                            CommunityPostServiceImpl communityPostService,
                            CommunityCommentMapper communityCommentMapper,
                            CommunityPostLikeMapper communityPostLikeMapper,
                            CommunityPostFavoriteMapper communityPostFavoriteMapper,
                            TeamTaskMapper teamTaskMapper,
                            TaskParticipantMapper taskParticipantMapper,
                            TaskCommentMapper taskCommentMapper,
                            TaskSubmissionMapper taskSubmissionMapper,
                            ProjectMilestoneMapper projectMilestoneMapper,
                            ChatMessageMapper chatMessageMapper,
                            TeamLibraryFileMapper teamLibraryFileMapper,
                            RecommendationLogMapper recommendationLogMapper,
                            NotificationMapper notificationMapper,
                            UserSkillMapper userSkillMapper,
                            RememberMeService rememberMeService,
                            UserSessionRegistry sessionRegistry) {
        this.userService = userService;
        this.teamDemandService = teamDemandService;
        this.teamApplicationService = teamApplicationService;
        this.teacherApplicationService = teacherApplicationService;
        this.projectApplicationService = projectApplicationService;
        this.assetService = assetService;
        this.assetLifecycleService = assetLifecycleService;
        this.communityPostService = communityPostService;
        this.communityCommentMapper = communityCommentMapper;
        this.communityPostLikeMapper = communityPostLikeMapper;
        this.communityPostFavoriteMapper = communityPostFavoriteMapper;
        this.teamTaskMapper = teamTaskMapper;
        this.taskParticipantMapper = taskParticipantMapper;
        this.taskCommentMapper = taskCommentMapper;
        this.taskSubmissionMapper = taskSubmissionMapper;
        this.projectMilestoneMapper = projectMilestoneMapper;
        this.chatMessageMapper = chatMessageMapper;
        this.teamLibraryFileMapper = teamLibraryFileMapper;
        this.recommendationLogMapper = recommendationLogMapper;
        this.notificationMapper = notificationMapper;
        this.userSkillMapper = userSkillMapper;
        this.rememberMeService = rememberMeService;
        this.sessionRegistry = sessionRegistry;
    }

    @Transactional(rollbackFor = Exception.class)
    @CacheEvict(cacheNames = {"teacherDetail", "teamDetail", "assetDetail",
        "recommendedUsers", "unreadCount"}, allEntries = true)
    public void deleteUser(Long userId) {
        User user = userService.getById(userId);
        if (user == null) throw new NoSuchElementException("\u7528\u6237\u4e0d\u5b58\u5728");

        List<Long> teamIds = teamDemandService.list(
            new LambdaQueryWrapper<TeamDemand>().eq(TeamDemand::getCreatorId, userId))
            .stream().map(TeamDemand::getId).collect(Collectors.toList());
        teamIds.forEach(id -> deleteTeamInternal(id, null));

        List<Long> postIds = communityPostService.list(
            new LambdaQueryWrapper<CommunityPost>().eq(CommunityPost::getAuthorId, userId))
            .stream().map(CommunityPost::getId).collect(Collectors.toList());
        postIds.forEach(this::deletePostInternal);

        List<Long> assetIds = assetService.list(
            new LambdaQueryWrapper<Asset>().eq(Asset::getUserId, userId))
            .stream().map(Asset::getId).collect(Collectors.toList());
        assetIds.forEach(assetLifecycleService::deleteAssetAsAdmin);

        Set<Long> createdTaskIds = teamTaskMapper.selectList(
            new LambdaQueryWrapper<TeamTask>().eq(TeamTask::getCreatedBy, userId))
            .stream().map(TeamTask::getId).collect(Collectors.toCollection(LinkedHashSet::new));
        deleteTasks(createdTaskIds);

        Set<Long> userCommentIds = taskCommentMapper.selectList(
            new LambdaQueryWrapper<TaskComment>().eq(TaskComment::getUserId, userId))
            .stream().map(TaskComment::getId).collect(Collectors.toCollection(LinkedHashSet::new));
        if (!userCommentIds.isEmpty()) {
            taskCommentMapper.update(null, new LambdaUpdateWrapper<TaskComment>()
                .in(TaskComment::getParentId, userCommentIds)
                .set(TaskComment::getParentId, null));
        }
        taskParticipantMapper.delete(new LambdaQueryWrapper<TaskParticipant>().eq(TaskParticipant::getUserId, userId));
        taskCommentMapper.delete(new LambdaQueryWrapper<TaskComment>().eq(TaskComment::getUserId, userId));
        taskSubmissionMapper.delete(new LambdaQueryWrapper<TaskSubmission>().eq(TaskSubmission::getSubmitterId, userId));
        teamTaskMapper.update(null, new LambdaUpdateWrapper<TeamTask>()
            .eq(TeamTask::getAssignedTo, userId).set(TeamTask::getAssignedTo, null));
        projectMilestoneMapper.delete(new LambdaQueryWrapper<ProjectMilestone>().eq(ProjectMilestone::getCreatedBy, userId));

        teamApplicationService.remove(new LambdaQueryWrapper<TeamApplication>().eq(TeamApplication::getUserId, userId));
        projectApplicationService.remove(new LambdaQueryWrapper<ProjectApplication>().eq(ProjectApplication::getUserId, userId));

        TeacherApplication profile = teacherApplicationService.getOne(
            new LambdaQueryWrapper<TeacherApplication>().eq(TeacherApplication::getUserId, userId));
        if (profile != null) {
            projectApplicationService.remove(
                new LambdaQueryWrapper<ProjectApplication>().eq(ProjectApplication::getTeacherId, profile.getId()));
            teacherApplicationService.removeById(profile.getId());
        }

        Set<Long> affectedPosts = new LinkedHashSet<>();
        communityPostLikeMapper.selectList(new LambdaQueryWrapper<CommunityPostLike>()
            .eq(CommunityPostLike::getUserId, userId)).forEach(row -> affectedPosts.add(row.getPostId()));
        communityPostFavoriteMapper.selectList(new LambdaQueryWrapper<CommunityPostFavorite>()
            .eq(CommunityPostFavorite::getUserId, userId)).forEach(row -> affectedPosts.add(row.getPostId()));
        communityPostLikeMapper.delete(new LambdaQueryWrapper<CommunityPostLike>().eq(CommunityPostLike::getUserId, userId));
        communityPostFavoriteMapper.delete(new LambdaQueryWrapper<CommunityPostFavorite>().eq(CommunityPostFavorite::getUserId, userId));
        communityCommentMapper.delete(new LambdaQueryWrapper<CommunityComment>().eq(CommunityComment::getUserId, userId));
        affectedPosts.forEach(this::refreshPostInteractionCounts);

        chatMessageMapper.delete(new LambdaQueryWrapper<ChatMessage>().eq(ChatMessage::getSenderId, userId));
        notificationMapper.delete(new LambdaQueryWrapper<Notification>().eq(Notification::getUserId, userId));
        notificationMapper.update(null, new LambdaUpdateWrapper<Notification>()
            .eq(Notification::getSenderId, userId).set(Notification::getSenderId, null));
        recommendationLogMapper.delete(new LambdaQueryWrapper<RecommendationLog>()
            .eq(RecommendationLog::getUserId, userId)
            .or().eq(RecommendationLog::getRecommendedUserId, userId));
        userSkillMapper.delete(new LambdaQueryWrapper<UserSkill>().eq(UserSkill::getUserId, userId));

        if (!userService.removeById(userId)) {
            throw new IllegalStateException("\u7528\u6237\u5220\u9664\u5931\u8d25");
        }
        // 修复 D-P1-1：删号后立即吊销 remember-me 并失效其全部会话，
        // 防止被删用户凭旧会话继续发帖/发消息（外键列留存已删 userId）
        try {
            rememberMeService.revokeAllForUser(userId);
        } catch (Exception e) {
            log.warn("吊销被删用户 {} 的 remember-me token 失败", userId, e);
        }
        sessionRegistry.invalidateAll(userId);
    }

    @Transactional(rollbackFor = Exception.class)
    @CacheEvict(cacheNames = {"teamDetail", "recommendedUsers", "unreadCount"},
        allEntries = true)
    public void deleteTeam(Long teamId, String reason) {
        TeamDemand team = teamDemandService.getById(teamId);
        if (team == null) {
            throw new NoSuchElementException("\u961f\u4f0d\u4e0d\u5b58\u5728");
        }
        deleteTeamInternal(teamId, reason);
        // 管理删除后告知创建者被删事实与原因，体现管理员审核职责
        if (reason != null && team.getCreatorId() != null && notificationService != null) {
            try {
                notificationService.create(team.getCreatorId(), null, "TEAM_DELETED", "组队需求已被删除",
                    "你的组队需求「" + TeamInviteService.displayName(team.getTitle()) + "」已被管理员删除。删除原因：" + reason,
                    teamId);
            } catch (Exception e) {
                log.warn("删除团队后通知创建者失败：teamId={}, creatorId={}", teamId, team.getCreatorId(), e);
            }
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public void deletePost(Long postId, String reason) {
        CommunityPost post = communityPostService.getById(postId);
        if (post == null) {
            throw new NoSuchElementException("\u5e16\u5b50\u4e0d\u5b58\u5728");
        }
        deletePostInternal(postId);
        if (reason != null && post.getAuthorId() != null && notificationService != null) {
            try {
                notificationService.create(post.getAuthorId(), null, "POST_DELETED", "帖子已被删除",
                    "你的帖子《" + displayTitle(post.getTitle()) + "》已被管理员删除。删除原因：" + reason,
                    postId);
            } catch (Exception e) {
                log.warn("删除帖子后通知作者失败：postId={}, authorId={}", postId, post.getAuthorId(), e);
            }
        }
    }

    /** 通知文案里的标题空值兜底 */
    private static String displayTitle(String title) {
        return title == null || title.trim().isEmpty() ? "无标题" : title.trim();
    }

    @Transactional(rollbackFor = Exception.class)
    @CacheEvict(cacheNames = "assetDetail", allEntries = true)
    public void deleteAsset(Long assetId, String reason) {
        Asset asset = assetService.getById(assetId);
        if (asset == null) {
            throw new NoSuchElementException("\u6210\u679c\u4e0d\u5b58\u5728");
        }
        assetLifecycleService.deleteAssetAsAdmin(assetId);
        if (reason != null && asset.getUserId() != null && notificationService != null) {
            try {
                notificationService.create(asset.getUserId(), null, "ASSET_DELETED", "成果已被删除",
                    "你的成果「" + displayTitle(asset.getTitle()) + "」已被管理员删除。删除原因：" + reason,
                    assetId);
            } catch (Exception e) {
                log.warn("删除成果后通知所有者失败：assetId={}, userId={}", assetId, asset.getUserId(), e);
            }
        }
    }

    private void deleteTeamInternal(Long teamId, String reason) {
        TeamDemand team = teamDemandService.getById(teamId);
        // 管理端删除不可恢复，至少要让队员知道发生了什么（队长自行解散会通知，这里原来不发任何通知）
        notifyTeamMembersBeforeDelete(teamId, team == null ? null : team.getTitle(), reason);
        Set<Long> taskIds = teamTaskMapper.selectList(
            new LambdaQueryWrapper<TeamTask>().eq(TeamTask::getTeamId, teamId))
            .stream().map(TeamTask::getId).collect(Collectors.toCollection(LinkedHashSet::new));
        deleteTasks(taskIds);
        projectMilestoneMapper.delete(new LambdaQueryWrapper<ProjectMilestone>().eq(ProjectMilestone::getTeamId, teamId));
        chatMessageMapper.delete(new LambdaQueryWrapper<ChatMessage>().eq(ChatMessage::getTeamId, teamId));
        // 资料库文件：此前漏清理，队伍删除后文件行变成 team_id 指向不存在队伍的孤儿记录，
        // 且因为删除接口要求队伍存在+成员权限，孤儿再也无法经 API 清除（核实报告 P2-4）。
        teamLibraryFileMapper.delete(new LambdaQueryWrapper<TeamLibraryFile>().eq(TeamLibraryFile::getTeamId, teamId));
        teamApplicationService.remove(new LambdaQueryWrapper<TeamApplication>().eq(TeamApplication::getTeamId, teamId));
        // 清理推荐日志属于附带动作：部分环境（手工建库）的 recommendation_log 没有 recommended_team_id 列，
        // 一旦报错就会让「删除队伍」整体失败。这里兜住异常，保证删除主流程不受影响。
        try {
            recommendationLogMapper.delete(new LambdaQueryWrapper<RecommendationLog>()
                .eq(RecommendationLog::getRecommendedTeamId, teamId));
        } catch (DataAccessException e) {
            Throwable root = e.getMostSpecificCause();
            log.warn("清理团队推荐日志失败，已跳过（不影响队伍删除）：teamId={}, cause={}",
                teamId, root != null ? root.getMessage() : e.getMessage());
        }
        notificationMapper.delete(new LambdaQueryWrapper<Notification>()
            .eq(Notification::getRelatedId, teamId)
            .in(Notification::getType, "TEAM_APPLY", "TEAM_APPROVED", "TEAM_REJECTED", "TASK_ASSIGNED", "TASK_SUBMITTED"));
        if (!teamDemandService.removeById(teamId)) {
            throw new IllegalStateException("\u961f\u4f0d\u5220\u9664\u5931\u8d25");
        }
    }

    /** 删除团队前给已通过的成员各发一条通知（排除操作者本人） */
    private void notifyTeamMembersBeforeDelete(Long teamId, String teamTitle, String reason) {
        List<TeamApplication> approved = teamApplicationService.list(
            new LambdaQueryWrapper<TeamApplication>()
                .eq(TeamApplication::getTeamId, teamId)
                .eq(TeamApplication::getStatus, "APPROVED"));
        if (approved.isEmpty()) {
            return;
        }
        String displayName = TeamInviteService.displayName(teamTitle);
        Date now = new Date();
        for (TeamApplication row : approved) {
            Long memberId = row.getUserId();
            if (memberId == null) {
                continue;
            }
            Notification notification = new Notification();
            notification.setUserId(memberId);
            notification.setType("TEAM_DISSOLVED");
            notification.setTitle("团队已被移除");
            notification.setContent("你所在的团队「" + displayName + "」已被管理员移除，相关任务与里程碑一并删除"
                + (reason == null || reason.trim().isEmpty() ? "" : "。删除原因：" + reason));
            notification.setRelatedId(teamId);
            notification.setIsRead(false);
            notification.setCreatedAt(now);
            notificationMapper.insert(notification);
        }
    }

    private void deletePostInternal(Long postId) {
        communityCommentMapper.delete(new LambdaQueryWrapper<CommunityComment>().eq(CommunityComment::getPostId, postId));
        communityPostLikeMapper.delete(new LambdaQueryWrapper<CommunityPostLike>().eq(CommunityPostLike::getPostId, postId));
        communityPostFavoriteMapper.delete(new LambdaQueryWrapper<CommunityPostFavorite>().eq(CommunityPostFavorite::getPostId, postId));
        notificationMapper.delete(new LambdaQueryWrapper<Notification>()
            .eq(Notification::getRelatedId, postId)
            .in(Notification::getType, "COMMENT", "LIKE", "FAVORITE"));
        if (!communityPostService.removeById(postId)) {
            throw new IllegalStateException("\u5e16\u5b50\u5220\u9664\u5931\u8d25");
        }
    }

    private void deleteTasks(Set<Long> taskIds) {
        if (taskIds == null || taskIds.isEmpty()) return;
        taskSubmissionMapper.delete(new LambdaQueryWrapper<TaskSubmission>().in(TaskSubmission::getTaskId, taskIds));
        taskCommentMapper.delete(new LambdaQueryWrapper<TaskComment>().in(TaskComment::getTaskId, taskIds));
        taskParticipantMapper.delete(new LambdaQueryWrapper<TaskParticipant>().in(TaskParticipant::getTaskId, taskIds));
        teamTaskMapper.deleteBatchIds(taskIds);
    }

    private void refreshPostInteractionCounts(Long postId) {
        if (postId == null || communityPostService.getById(postId) == null) return;
        long likes = communityPostLikeMapper.selectCount(
            new LambdaQueryWrapper<CommunityPostLike>().eq(CommunityPostLike::getPostId, postId));
        long favorites = communityPostFavoriteMapper.selectCount(
            new LambdaQueryWrapper<CommunityPostFavorite>().eq(CommunityPostFavorite::getPostId, postId));
        communityPostService.update(new LambdaUpdateWrapper<CommunityPost>()
            .eq(CommunityPost::getId, postId)
            .set(CommunityPost::getLikeCount, likes)
            .set(CommunityPost::getFavoriteCount, favorites));
    }
}
