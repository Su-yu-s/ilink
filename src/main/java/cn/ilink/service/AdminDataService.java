package cn.ilink.service;

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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.cache.annotation.CacheEvict;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class AdminDataService {

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
    private final RecommendationLogMapper recommendationLogMapper;
    private final NotificationMapper notificationMapper;
    private final UserSkillMapper userSkillMapper;

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
                            RecommendationLogMapper recommendationLogMapper,
                            NotificationMapper notificationMapper,
                            UserSkillMapper userSkillMapper) {
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
        this.recommendationLogMapper = recommendationLogMapper;
        this.notificationMapper = notificationMapper;
        this.userSkillMapper = userSkillMapper;
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
        teamIds.forEach(this::deleteTeamInternal);

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
    }

    @Transactional(rollbackFor = Exception.class)
    @CacheEvict(cacheNames = {"teamDetail", "recommendedUsers", "unreadCount"},
        allEntries = true)
    public void deleteTeam(Long teamId) {
        if (teamDemandService.getById(teamId) == null) {
            throw new NoSuchElementException("\u961f\u4f0d\u4e0d\u5b58\u5728");
        }
        deleteTeamInternal(teamId);
    }

    @Transactional(rollbackFor = Exception.class)
    public void deletePost(Long postId) {
        if (communityPostService.getById(postId) == null) {
            throw new NoSuchElementException("\u5e16\u5b50\u4e0d\u5b58\u5728");
        }
        deletePostInternal(postId);
    }

    @Transactional(rollbackFor = Exception.class)
    @CacheEvict(cacheNames = "assetDetail", allEntries = true)
    public void deleteAsset(Long assetId) {
        assetLifecycleService.deleteAssetAsAdmin(assetId);
    }

    private void deleteTeamInternal(Long teamId) {
        Set<Long> taskIds = teamTaskMapper.selectList(
            new LambdaQueryWrapper<TeamTask>().eq(TeamTask::getTeamId, teamId))
            .stream().map(TeamTask::getId).collect(Collectors.toCollection(LinkedHashSet::new));
        deleteTasks(taskIds);
        projectMilestoneMapper.delete(new LambdaQueryWrapper<ProjectMilestone>().eq(ProjectMilestone::getTeamId, teamId));
        chatMessageMapper.delete(new LambdaQueryWrapper<ChatMessage>().eq(ChatMessage::getTeamId, teamId));
        teamApplicationService.remove(new LambdaQueryWrapper<TeamApplication>().eq(TeamApplication::getTeamId, teamId));
        recommendationLogMapper.delete(new LambdaQueryWrapper<RecommendationLog>()
            .eq(RecommendationLog::getRecommendedTeamId, teamId));
        notificationMapper.delete(new LambdaQueryWrapper<Notification>()
            .eq(Notification::getRelatedId, teamId)
            .in(Notification::getType, "TEAM_APPLY", "TEAM_APPROVED", "TEAM_REJECTED", "TASK_ASSIGNED", "TASK_SUBMITTED"));
        if (!teamDemandService.removeById(teamId)) {
            throw new IllegalStateException("\u961f\u4f0d\u5220\u9664\u5931\u8d25");
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
