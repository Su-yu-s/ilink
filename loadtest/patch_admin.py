import io

# ============ AdminDataService：删除带原因 + 通知所有者 ============
p = 'src/main/java/cn/ilink/service/AdminDataService.java'
s = io.open(p, encoding='utf-8').read()

old = 'import cn.ilink.common.UserSessionRegistry;'
new = 'import cn.ilink.common.UserSessionRegistry;\nimport cn.ilink.service.NotificationService;'
assert old in s, 'import notification'
s = s.replace(old, new, 1)

old = '''    private final UserSkillMapper userSkillMapper;
    private final RememberMeService rememberMeService;
    private final UserSessionRegistry sessionRegistry;'''
new = '''    private final UserSkillMapper userSkillMapper;
    private final RememberMeService rememberMeService;
    private final UserSessionRegistry sessionRegistry;

    /** 管理删除时向内容所有者发通知；测试环境未装配时为 null，发通知处已判空跳过 */
    @Autowired(required = false)
    private NotificationService notificationService;'''
assert old in s, 'fields'
s = s.replace(old, new, 1)

if 'import org.springframework.beans.factory.annotation.Autowired;' not in s:
    s = s.replace('import org.springframework.stereotype.Service;',
                  'import org.springframework.beans.factory.annotation.Autowired;\nimport org.springframework.stereotype.Service;', 1)

# deleteTeam 加 reason
old = '''    public void deleteTeam(Long teamId) {
        if (teamDemandService.getById(teamId) == null) {
            throw new NoSuchElementException("\\u961f\\u4f0d\\u4e0d\\u5b58\\u5728");
        }
        deleteTeamInternal(teamId);'''
new = '''    public void deleteTeam(Long teamId, String reason) {
        TeamDemand team = teamDemandService.getById(teamId);
        if (team == null) {
            throw new NoSuchElementException("\\u961f\\u4f0d\\u4e0d\\u5b58\\u5728");
        }
        deleteTeamInternal(teamId, reason);
        // 管理删除后告知创建者被删事实与原因，体现管理员审核职责
        if (team.getCreatorId() != null && notificationService != null) {
            try {
                notificationService.create(team.getCreatorId(), null, "TEAM_DELETED", "组队需求已被删除",
                    "你的组队需求「" + TeamInviteService.displayName(team.getTitle()) + "」已被管理员删除。删除原因：" + reason,
                    teamId);
            } catch (Exception e) {
                log.warn("删除团队后通知创建者失败：teamId={}, creatorId={}", teamId, team.getCreatorId(), e);
            }
        }
    }'''
assert old in s, 'deleteTeam'
s = s.replace(old, new, 1)

# deletePost 加 reason
old = '''    public void deletePost(Long postId) {
        if (communityPostService.getById(postId) == null) {
            throw new NoSuchElementException("\\u5e16\\u5b50\\u4e0d\\u5b58\\u5728");
        }
        deletePostInternal(postId);
    }'''
new = '''    public void deletePost(Long postId, String reason) {
        CommunityPost post = communityPostService.getById(postId);
        if (post == null) {
            throw new NoSuchElementException("\\u5e16\\u5b50\\u4e0d\\u5b58\\u5728");
        }
        deletePostInternal(postId);
        if (post.getAuthorId() != null && notificationService != null) {
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
    }'''
assert old in s, 'deletePost'
s = s.replace(old, new, 1)

# deleteAsset 加 reason
old = '''    public void deleteAsset(Long assetId) {
        assetLifecycleService.deleteAssetAsAdmin(assetId);
    }'''
new = '''    public void deleteAsset(Long assetId, String reason) {
        Asset asset = assetService.getById(assetId);
        if (asset == null) {
            throw new NoSuchElementException("\\u6210\\u679c\\u4e0d\\u5b58\\u5728");
        }
        assetLifecycleService.deleteAssetAsAdmin(assetId);
        if (asset.getUserId() != null && notificationService != null) {
            try {
                notificationService.create(asset.getUserId(), null, "ASSET_DELETED", "成果已被删除",
                    "你的成果「" + displayTitle(asset.getTitle()) + "」已被管理员删除。删除原因：" + reason,
                    assetId);
            } catch (Exception e) {
                log.warn("删除成果后通知所有者失败：assetId={}, userId={}", assetId, asset.getUserId(), e);
            }
        }
    }'''
assert old in s, 'deleteAsset'
s = s.replace(old, new, 1)

# deleteTeamInternal 带原因；deleteUser 级联调用传 null
old = '''        teamIds.forEach(this::deleteTeamInternal);'''
new = '''        teamIds.forEach(id -> deleteTeamInternal(id, null));'''
assert old in s, 'cascade'
s = s.replace(old, new, 1)

old = '''    private void deleteTeamInternal(Long teamId) {
        TeamDemand team = teamDemandService.getById(teamId);
        // 管理端删除不可恢复，至少要让队员知道发生了什么（队长自行解散会通知，这里原来不发任何通知）
        notifyTeamMembersBeforeDelete(teamId, team == null ? null : team.getTitle());'''
new = '''    private void deleteTeamInternal(Long teamId, String reason) {
        TeamDemand team = teamDemandService.getById(teamId);
        // 管理端删除不可恢复，至少要让队员知道发生了什么（队长自行解散会通知，这里原来不发任何通知）
        notifyTeamMembersBeforeDelete(teamId, team == null ? null : team.getTitle(), reason);'''
assert old in s, 'internal'
s = s.replace(old, new, 1)

old = '''    private void notifyTeamMembersBeforeDelete(Long teamId, String teamTitle) {'''
new = '''    private void notifyTeamMembersBeforeDelete(Long teamId, String teamTitle, String reason) {'''
assert old in s, 'notify sig'
s = s.replace(old, new, 1)

old = '''            notification.setContent("你所在的团队「" + displayName + "」已被管理员移除，相关任务与里程碑一并删除");'''
new = '''            notification.setContent("你所在的团队「" + displayName + "」已被管理员移除，相关任务与里程碑一并删除"
                + (reason == null || reason.trim().isEmpty() ? "" : "。删除原因：" + reason));'''
assert old in s, 'notify content'
s = s.replace(old, new, 1)

io.open(p, 'w', encoding='utf-8', newline='\n').write(s)
print('AdminDataService patched')

# ============ AdminController：三端点加 reason 必填 + 审计带原因 ============
p = 'src/main/java/cn/ilink/controller/AdminController.java'
s = io.open(p, encoding='utf-8').read()

old = '''    public ResponseEntity<Result<?>> deleteTeam(@PathVariable Long id, HttpSession session,
                                                HttpServletRequest request) {
        User user = ControllerUtils.requireUser(session);
        if (!ControllerUtils.isAdmin(user)) {
            return Result.forbidden().toResponseEntity();
        }

        try {
            adminDataService.deleteTeam(id);
            adminAuditService.recordSafely(user, "DELETE", "TEAM", id, "", request);'''
new = '''    public ResponseEntity<Result<?>> deleteTeam(@PathVariable Long id, HttpSession session,
                                                HttpServletRequest request,
                                                @RequestParam(required = false) String reason) {
        User user = ControllerUtils.requireUser(session);
        if (!ControllerUtils.isAdmin(user)) {
            return Result.forbidden().toResponseEntity();
        }
        String normalizedReason = normalizeDeleteReason(reason);
        if (normalizedReason == null) {
            return Result.badRequest("请填写删除原因").toResponseEntity();
        }

        try {
            adminDataService.deleteTeam(id, normalizedReason);
            adminAuditService.recordSafely(user, "DELETE", "TEAM", id, "reason=" + normalizedReason, request);'''
assert old in s, 'ctrl team'
s = s.replace(old, new, 1)

old = '''    public ResponseEntity<Result<?>> deleteAsset(@PathVariable Long id, HttpSession session,
                                                 HttpServletRequest request) {
        User user = ControllerUtils.requireUser(session);
        if (!ControllerUtils.isAdmin(user)) {
            return Result.forbidden().toResponseEntity();
        }

        try {
            adminDataService.deleteAsset(id);
            adminAuditService.recordSafely(user, "DELETE", "ASSET", id, "", request);'''
new = '''    public ResponseEntity<Result<?>> deleteAsset(@PathVariable Long id, HttpSession session,
                                                 HttpServletRequest request,
                                                 @RequestParam(required = false) String reason) {
        User user = ControllerUtils.requireUser(session);
        if (!ControllerUtils.isAdmin(user)) {
            return Result.forbidden().toResponseEntity();
        }
        String normalizedReason = normalizeDeleteReason(reason);
        if (normalizedReason == null) {
            return Result.badRequest("请填写删除原因").toResponseEntity();
        }

        try {
            adminDataService.deleteAsset(id, normalizedReason);
            adminAuditService.recordSafely(user, "DELETE", "ASSET", id, "reason=" + normalizedReason, request);'''
assert old in s, 'ctrl asset'
s = s.replace(old, new, 1)

old = '''    public ResponseEntity<Result<?>> deleteCommunityPost(@PathVariable Long id, HttpSession session,
                                                         HttpServletRequest request) {
        User user = ControllerUtils.requireUser(session);
        if (!ControllerUtils.isAdmin(user)) {
            return Result.forbidden().toResponseEntity();
        }

        try {
            adminDataService.deletePost(id);
            adminAuditService.recordSafely(user, "DELETE", "COMMUNITY_POST", id, "", request);'''
new = '''    public ResponseEntity<Result<?>> deleteCommunityPost(@PathVariable Long id, HttpSession session,
                                                         HttpServletRequest request,
                                                         @RequestParam(required = false) String reason) {
        User user = ControllerUtils.requireUser(session);
        if (!ControllerUtils.isAdmin(user)) {
            return Result.forbidden().toResponseEntity();
        }
        String normalizedReason = normalizeDeleteReason(reason);
        if (normalizedReason == null) {
            return Result.badRequest("请填写删除原因").toResponseEntity();
        }

        try {
            adminDataService.deletePost(id, normalizedReason);
            adminAuditService.recordSafely(user, "DELETE", "COMMUNITY_POST", id, "reason=" + normalizedReason, request);'''
assert old in s, 'ctrl post'
s = s.replace(old, new, 1)

old_tail = s.rstrip()
helper = '''
    /** 删除原因规范化：缺失/空白返回 null，超 200 字返回空串（视为非法） */
    private String normalizeDeleteReason(String reason) {
        if (reason == null) {
            return null;
        }
        String trimmed = reason.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.length() > 200) {
            return "";
        }
        return trimmed;
    }
}'''
s = old_tail[:old_tail.rfind('}')] + helper
io.open(p, 'w', encoding='utf-8', newline='\n').write(s)
print('AdminController patched')
