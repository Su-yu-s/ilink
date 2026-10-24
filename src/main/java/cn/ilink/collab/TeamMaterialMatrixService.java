package cn.ilink.collab;

import cn.ilink.entity.KgDocumentChunk;
import cn.ilink.entity.Competition;
import cn.ilink.entity.TeamCollabDocument;
import cn.ilink.entity.TeamCollabDocumentVersion;
import cn.ilink.entity.TeamCollabSpreadsheet;
import cn.ilink.entity.TeamCollabSpreadsheetVersion;
import cn.ilink.entity.TeamDemand;
import cn.ilink.entity.TeamLibraryFile;
import cn.ilink.entity.TeamMaterialEvidence;
import cn.ilink.entity.TeamMaterialRequirement;
import cn.ilink.entity.TeamMaterialReview;
import cn.ilink.entity.TeamTask;
import cn.ilink.entity.User;
import cn.ilink.mapper.KgDocumentChunkMapper;
import cn.ilink.mapper.CompetitionMapper;
import cn.ilink.mapper.TeamCollabDocumentMapper;
import cn.ilink.mapper.TeamCollabDocumentVersionMapper;
import cn.ilink.mapper.TeamCollabSpreadsheetMapper;
import cn.ilink.mapper.TeamCollabSpreadsheetVersionMapper;
import cn.ilink.mapper.TeamLibraryFileMapper;
import cn.ilink.mapper.TeamMaterialEvidenceMapper;
import cn.ilink.mapper.TeamMaterialRequirementMapper;
import cn.ilink.mapper.TeamMaterialReviewMapper;
import cn.ilink.mapper.TeamTaskMapper;
import cn.ilink.service.UserService;
import cn.ilink.util.DisplayNameResolver;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Competition material requirements, evidence and human review. Agent
 * suggestions may only reach NEEDS_REVIEW; MET/PARTIAL/MISSING always require
 * an explicit human review command.
 */
@Service
public class TeamMaterialMatrixService {
    private static final Set<String> STATUSES = Set.of(
        TeamMaterialRequirement.REVIEW_UNCHECKED,
        TeamMaterialRequirement.REVIEW_NEEDS_REVIEW,
        TeamMaterialRequirement.REVIEW_MET,
        TeamMaterialRequirement.REVIEW_PARTIAL,
        TeamMaterialRequirement.REVIEW_MISSING);
    private static final Set<String> EVIDENCE_TYPES = Set.of(
        "FILE", "DOCUMENT", "SPREADSHEET", "TASK");
    private final TeamMaterialRequirementMapper requirementMapper;
    private final TeamMaterialEvidenceMapper evidenceMapper;
    private final TeamMaterialReviewMapper reviewMapper;
    private final TeamLibraryFileMapper libraryMapper;
    private final TeamCollabDocumentMapper documentMapper;
    private final TeamCollabDocumentVersionMapper documentVersionMapper;
    private final TeamCollabSpreadsheetMapper spreadsheetMapper;
    private final TeamCollabSpreadsheetVersionMapper spreadsheetVersionMapper;
    private final TeamTaskMapper taskMapper;
    private final KgDocumentChunkMapper chunkMapper;
    private final CompetitionMapper competitionMapper;
    private final TeamCollaborationAuthorization authorization;
    private final TeamMaterialEvidenceValidator evidenceValidator;
    private final UserService userService;

    public TeamMaterialMatrixService(TeamMaterialRequirementMapper requirementMapper,
                                     TeamMaterialEvidenceMapper evidenceMapper,
                                     TeamMaterialReviewMapper reviewMapper,
                                     TeamLibraryFileMapper libraryMapper,
                                     TeamCollabDocumentMapper documentMapper,
                                     TeamCollabDocumentVersionMapper documentVersionMapper,
                                     TeamCollabSpreadsheetMapper spreadsheetMapper,
                                     TeamCollabSpreadsheetVersionMapper spreadsheetVersionMapper,
                                     TeamTaskMapper taskMapper,
                                     KgDocumentChunkMapper chunkMapper,
                                     CompetitionMapper competitionMapper,
                                     TeamCollaborationAuthorization authorization,
                                     TeamMaterialEvidenceValidator evidenceValidator,
                                     UserService userService) {
        this.requirementMapper = requirementMapper;
        this.evidenceMapper = evidenceMapper;
        this.reviewMapper = reviewMapper;
        this.libraryMapper = libraryMapper;
        this.documentMapper = documentMapper;
        this.documentVersionMapper = documentVersionMapper;
        this.spreadsheetMapper = spreadsheetMapper;
        this.spreadsheetVersionMapper = spreadsheetVersionMapper;
        this.taskMapper = taskMapper;
        this.chunkMapper = chunkMapper;
        this.competitionMapper = competitionMapper;
        this.authorization = authorization;
        this.evidenceValidator = evidenceValidator;
        this.userService = userService;
    }

    public MatrixView matrix(Long teamId, Long userId, String status, Boolean required,
                             String category) {
        TeamCollaborationAuthorization.TeamContext teamContext =
            authorization.requireTeam(teamId, userId);
        TeamDemand team = teamContext.team();
        LambdaQueryWrapper<TeamMaterialRequirement> query =
            new LambdaQueryWrapper<TeamMaterialRequirement>()
                .eq(TeamMaterialRequirement::getTeamId, teamId)
                .eq(TeamMaterialRequirement::getStatus, "ACTIVE");
        if (status != null && !status.isBlank()) {
            String normalized = status.trim().toUpperCase(Locale.ROOT);
            if (!STATUSES.contains(normalized)) throw invalid("材料状态筛选不合法");
            query.eq(TeamMaterialRequirement::getReviewStatus, normalized);
        }
        if (required != null) query.eq(TeamMaterialRequirement::getRequired, required);
        if (category != null && !category.isBlank()) {
            query.eq(TeamMaterialRequirement::getCategory, category.trim());
        }
        List<TeamMaterialRequirement> requirements = requirementMapper.selectList(
            query.orderByAsc(TeamMaterialRequirement::getSortOrder)
                .orderByAsc(TeamMaterialRequirement::getId));
        List<RequirementView> rows = requirements.stream()
            .map(row -> requirementView(row, teamContext)).toList();
        Map<String, Long> counts = new LinkedHashMap<>();
        counts.put("total", (long) rows.size());
        for (String key : List.of("UNCHECKED", "NEEDS_REVIEW", "MET", "PARTIAL", "MISSING")) {
            counts.put(key, rows.stream().filter(row -> key.equals(row.reviewStatus())).count());
        }
        long requiredTotal = rows.stream().filter(row -> row.required()).count();
        long requiredMet = rows.stream()
            .filter(row -> row.required() && TeamMaterialRequirement.REVIEW_MET.equals(row.reviewStatus()))
            .count();
        Map<String, Object> context = new LinkedHashMap<>();
        Integer competitionId = team == null ? null : team.getCompetitionId();
        context.put("competitionId", competitionId);
        context.put("catalogName", competitionName(competitionId));
        context.put("customCompetition", team == null ? null : team.getCustomCompetition());
        context.put("canMaintain", canMaintain(teamContext));
        context.put("canCreate", teamContext.canWrite());
        context.put("readOnly", !teamContext.canWrite());
        return new MatrixView(rows, counts, requiredTotal, requiredMet, context);
    }

    private String competitionName(Integer competitionId) {
        if (competitionId == null) return null;
        Competition competition = competitionMapper.selectById(competitionId);
        return competition == null ? null : competition.getName();
    }

    @Transactional(rollbackFor = Exception.class)
    public RequirementView createRequirement(Long teamId, Long userId, RequirementRequest request) {
        return createRequirementInternal(teamId, userId, request, false);
    }

    /**
     * Internal command for Agent-confirmed suggestions. The source type and the
     * review state are forced server-side, so a model can never register a
     * requirement as already satisfied.
     */
    @Transactional(rollbackFor = Exception.class)
    public RequirementView createRequirement(Long teamId, Long userId,
                                             RequirementRequest request, boolean agent) {
        return createRequirementInternal(teamId, userId, request, agent);
    }

    /** Single authorized read used by the Agent action layer and the API. */
    public RequirementView requirementView(Long teamId, Long requirementId, Long userId) {
        authorization.requireTeam(teamId, userId);
        TeamMaterialRequirement row = requirementMapper.selectById(requirementId);
        requireRequirement(teamId, row);
        return requirementView(row, authorization.requireTeam(teamId, userId));
    }

    /**
     * Undo for an Agent review suggestion. Only a conclusion that is still
     * NEEDS_REVIEW can be reverted, and the restored status must be a state the
     * matrix itself previously recorded, so undo cannot invent a human verdict.
     */
    @Transactional(rollbackFor = Exception.class)
    public RequirementView restoreReviewStatus(Long teamId, Long requirementId, Long userId,
                                               String previousStatus, String note) {
        authorization.requireWritableTeam(teamId, userId);
        TeamMaterialRequirement row = requirementMapper.selectByIdForUpdate(requirementId);
        requireRequirement(teamId, row);
        if (!TeamMaterialRequirement.REVIEW_NEEDS_REVIEW.equals(row.getReviewStatus())) {
            throw new TeamCollabException(TeamCollabException.Code.AGENT_ACTION_CONFLICT,
                "该材料要求已被重新复核，撤销已停止");
        }
        String restored = previousStatus == null ? TeamMaterialRequirement.REVIEW_UNCHECKED
            : previousStatus.trim().toUpperCase(Locale.ROOT);
        if (!STATUSES.contains(restored)) {
            throw new TeamCollabException(TeamCollabException.Code.AGENT_ACTION_CONFLICT,
                "可恢复的复核状态已失效");
        }
        // The prior conclusion has to exist in the append-only review trail.
        long recorded = reviewMapper.selectCount(new LambdaQueryWrapper<TeamMaterialReview>()
            .eq(TeamMaterialReview::getRequirementId, requirementId)
            .eq(TeamMaterialReview::getStatus, restored));
        if (recorded == 0 && !TeamMaterialRequirement.REVIEW_UNCHECKED.equals(restored)) {
            throw new TeamCollabException(TeamCollabException.Code.AGENT_ACTION_CONFLICT,
                "原始复核记录不存在，无法恢复");
        }
        updateReviewSummary(row, restored, trimToNull(note, 1000), userId);
        return requirementView(requirementMapper.selectById(requirementId),
            authorization.requireTeam(teamId, userId));
    }

    private RequirementView createRequirementInternal(Long teamId, Long userId,
                                                     RequirementRequest request, boolean agent) {
        TeamCollaborationAuthorization.TeamContext team =
            authorization.requireWritableTeam(teamId, userId);
        // Every active team member may record a material requirement they must
        // prepare; leaders and mentors maintain the whole matrix.
        String title = text(request == null ? null : request.title(), 200, "材料要求标题");
        String sourceType = agent ? "AGENT_SUGGESTED"
            : sourceType(request == null ? null : request.sourceType(), false);
        if (!agent && "AGENT_SUGGESTED".equals(sourceType)) {
            throw invalid("Agent 建议只能由动作确认流程创建");
        }
        Integer competitionId = request == null ? null : request.competitionId();
        String custom = trimToNull(request == null ? null : request.customCompetition(), 200);
        if (competitionId != null && custom != null) {
            throw invalid("目录竞赛与自定义竞赛只能填写一个");
        }
        if (competitionId != null) {
            if (team.team().getCompetitionId() == null
                || !competitionId.equals(team.team().getCompetitionId())) {
                throw invalid("材料要求只能绑定当前团队已选择的目录竞赛");
            }
        }
        Date now = new Date();
        TeamMaterialRequirement row = new TeamMaterialRequirement();
        row.setTeamId(teamId);
        row.setCompetitionId(competitionId);
        row.setCustomCompetition(custom);
        row.setTitle(title);
        row.setDescription(trimToNull(request == null ? null : request.description(), 2000));
        row.setCategory(trimToNull(request == null ? null : request.category(), 64));
        row.setRequired(request == null || request.required() == null || request.required());
        row.setSourceType(sourceType);
        row.setStatus("ACTIVE");
        row.setReviewStatus(agent ? TeamMaterialRequirement.REVIEW_NEEDS_REVIEW
            : TeamMaterialRequirement.REVIEW_UNCHECKED);
        row.setReviewVersion(0);
        row.setVersion(0);
        row.setSortOrder(request == null || request.sortOrder() == null ? 0 : request.sortOrder());
        row.setCreatedBy(userId);
        row.setUpdatedBy(userId);
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        requirementMapper.insert(row);
        if (agent) {
            updateReviewSummary(row, TeamMaterialRequirement.REVIEW_NEEDS_REVIEW,
                request == null ? null : trimToNull(request.description(), 1000), userId);
            row = requirementMapper.selectById(row.getId());
        }
        return requirementView(row, team);
    }

    @Transactional(rollbackFor = Exception.class)
    public RequirementView updateRequirement(Long teamId, Long requirementId, Long userId,
                                             RequirementRequest request) {
        TeamCollaborationAuthorization.TeamContext team =
            authorization.requireWritableTeam(teamId, userId);
        TeamMaterialRequirement row = requirementMapper.selectByIdForUpdate(requirementId);
        requireRequirement(teamId, row);
        if (!canMaintainRequirement(team, row)) {
            throw forbidden("只有队长、导师或要求创建者可以修改材料要求");
        }
        if (request == null || request.version() == null
            || !Objects.equals(request.version(), row.getVersion())) {
            throw versionConflict();
        }
        if (request.title() != null) row.setTitle(text(request.title(), 200, "材料要求标题"));
        if (request.description() != null) {
            row.setDescription(trimToNull(request.description(), 2000));
        }
        if (request.category() != null) row.setCategory(trimToNull(request.category(), 64));
        if (request.required() != null) row.setRequired(request.required());
        if (request.sortOrder() != null) row.setSortOrder(request.sortOrder());
        row.setUpdatedBy(userId);
        row.setUpdatedAt(new Date());
        row.setVersion((row.getVersion() == null ? 0 : row.getVersion()) + 1);
        requirementMapper.updateById(row);
        return requirementView(row, team);
    }

    @Transactional(rollbackFor = Exception.class)
    public void archiveRequirement(Long teamId, Long requirementId, Long userId, int version) {
        TeamCollaborationAuthorization.TeamContext team =
            authorization.requireWritableTeam(teamId, userId);
        TeamMaterialRequirement row = requirementMapper.selectByIdForUpdate(requirementId);
        requireRequirement(teamId, row);
        if (!canMaintainRequirement(team, row)) {
            throw forbidden("只有队长、导师或要求创建者可以归档材料要求");
        }
        if (!Objects.equals(version, row.getVersion())) throw versionConflict();
        row.setStatus("ARCHIVED");
        row.setUpdatedBy(userId);
        row.setUpdatedAt(new Date());
        row.setVersion((row.getVersion() == null ? 0 : row.getVersion()) + 1);
        requirementMapper.updateById(row);
    }

    @Transactional(rollbackFor = Exception.class)
    public EvidenceView addEvidence(Long teamId, Long requirementId, Long userId,
                                    EvidenceRequest request) {
        TeamCollaborationAuthorization.TeamContext team =
            authorization.requireWritableTeam(teamId, userId);
        TeamMaterialRequirement requirement = requirementMapper.selectByIdForUpdate(requirementId);
        requireRequirement(teamId, requirement);
        if (request == null) throw invalid("证据内容不能为空");
        String type = request.assetType() == null ? ""
            : request.assetType().trim().toUpperCase(Locale.ROOT);
        if (!EVIDENCE_TYPES.contains(type)) throw invalid("证据来源类型不合法");
        Long assetId = request.assetId();
        EvidenceSnapshot snapshot = evidenceValidator.validate(teamId, userId, type, assetId,
            request.evidenceChunkId());
        String matchStatus = request.matchStatus() == null ? "UNVERIFIED"
            : request.matchStatus().trim().toUpperCase(Locale.ROOT);
        if (!Set.of("UNVERIFIED", "CANDIDATE").contains(matchStatus)) {
            throw invalid("证据匹配状态不合法");
        }
        TeamMaterialEvidence existing = evidenceMapper.selectOne(
            new LambdaQueryWrapper<TeamMaterialEvidence>()
                .eq(TeamMaterialEvidence::getRequirementId, requirementId)
                .eq(TeamMaterialEvidence::getAssetType, type)
                .eq(TeamMaterialEvidence::getAssetId, assetId)
                .last("LIMIT 1"));
        if (existing != null) {
            return evidenceView(existing, snapshot);
        }
        TeamMaterialEvidence row = new TeamMaterialEvidence();
        row.setTeamId(teamId);
        row.setRequirementId(requirementId);
        row.setAssetType(type);
        row.setAssetId(assetId);
        row.setEvidenceChunkId(request.evidenceChunkId());
        row.setEvidenceExcerpt(trimToNull(request.evidenceExcerpt(), 1000));
        row.setMatchStatus(matchStatus);
        row.setCreatedBy(userId);
        row.setCreatedAt(new Date());
        evidenceMapper.insert(row);
        if ("CANDIDATE".equals(matchStatus)
            && TeamMaterialRequirement.REVIEW_UNCHECKED.equals(requirement.getReviewStatus())) {
            updateReviewSummary(requirement, TeamMaterialRequirement.REVIEW_NEEDS_REVIEW,
                null, userId);
        }
        return evidenceView(row, snapshot);
    }

    @Transactional(rollbackFor = Exception.class)
    public void removeEvidence(Long teamId, Long evidenceId, Long userId) {
        TeamCollaborationAuthorization.TeamContext team =
            authorization.requireWritableTeam(teamId, userId);
        TeamMaterialEvidence evidence = evidenceMapper.selectById(evidenceId);
        if (evidence == null || !Objects.equals(evidence.getTeamId(), teamId)) {
            throw new TeamCollabException(TeamCollabException.Code.MATERIAL_EVIDENCE_INVALID,
                "证据不存在");
        }
        if (!canMaintain(team) && !Objects.equals(evidence.getCreatedBy(), userId)) {
            throw forbidden("只有队长、导师或证据提交人可以移除材料证据");
        }
        TeamMaterialRequirement requirement = requirementMapper.selectByIdForUpdate(
            evidence.getRequirementId());
        requireRequirement(teamId, requirement);
        evidenceMapper.deleteById(evidenceId);
        long count = evidenceMapper.selectCount(new LambdaQueryWrapper<TeamMaterialEvidence>()
            .eq(TeamMaterialEvidence::getRequirementId, requirement.getId()));
        if (count == 0 && TeamMaterialRequirement.REVIEW_NEEDS_REVIEW
            .equals(requirement.getReviewStatus())) {
            updateReviewSummary(requirement, TeamMaterialRequirement.REVIEW_UNCHECKED,
                "证据已全部移除", userId);
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public RequirementView review(Long teamId, Long requirementId, Long userId,
                                  ReviewRequest request) {
        TeamCollaborationAuthorization.TeamContext team =
            authorization.requireWritableTeam(teamId, userId);
        TeamMaterialRequirement row = requirementMapper.selectByIdForUpdate(requirementId);
        requireRequirement(teamId, row);
        if (request == null || request.status() == null) throw invalid("请选择复核结论");
        String status = request.status().trim().toUpperCase(Locale.ROOT);
        if (!Set.of("MET", "PARTIAL", "MISSING", "NEEDS_REVIEW").contains(status)) {
            throw invalid("人工复核结论仅支持 MET/PARTIAL/MISSING/NEEDS_REVIEW");
        }
        String note = trimToNull(request.note(), 1000);
        if (("PARTIAL".equals(status) || "MISSING".equals(status))
            && note == null) {
            throw invalid("部分满足或缺失必须填写说明");
        }
        if (!TeamMaterialRequirement.REVIEW_NEEDS_REVIEW.equals(status)) {
            // A positive or negative conclusion is a human judgement made by
            // whoever maintains the requirement, and must cite real evidence.
            if (!canMaintainRequirement(team, row)) {
                throw forbidden("只有队长、导师或要求创建者可以作出材料复核结论");
            }
            if (evidenceMapper.selectCount(new LambdaQueryWrapper<TeamMaterialEvidence>()
                .eq(TeamMaterialEvidence::getRequirementId, requirementId)) == 0) {
                throw new TeamCollabException(TeamCollabException.Code.MATERIAL_EVIDENCE_INVALID,
                    "没有关联证据时不能标记为已满足");
            }
        }
        updateReviewSummary(row, status, note, userId);
        return requirementView(requirementMapper.selectById(requirementId), team);
    }

    public Export exportCsv(Long teamId, Long userId) {
        TeamCollaborationAuthorization.TeamContext team =
            authorization.requireTeam(teamId, userId);
        if (!team.canExport()) throw new TeamCollabException(
            TeamCollabException.Code.TEAM_READ_ONLY, "当前团队状态不允许导出材料矩阵");
        MatrixView matrix = matrix(teamId, userId, null, null, null);
        StringBuilder csv = new StringBuilder("\uFEFF");
        csv.append("要求,类别,必需,状态,证据数,说明\r\n");
        for (RequirementView row : matrix.requirements()) {
            csv.append(csvValue(row.title())).append(',')
                .append(csvValue(row.category())).append(',')
                .append(row.required() ? "是" : "否").append(',')
                .append(row.reviewStatus()).append(',')
                .append(row.evidence().size()).append(',')
                .append(csvValue(row.reviewNote())).append("\r\n");
        }
        return new Export("材料对照.csv", "text/csv;charset=UTF-8",
            csv.toString().getBytes(StandardCharsets.UTF_8));
    }

    @Transactional(rollbackFor = Exception.class)
    public int deleteAllForTeam(Long teamId) {
        evidenceMapper.delete(new LambdaQueryWrapper<TeamMaterialEvidence>()
            .eq(TeamMaterialEvidence::getTeamId, teamId));
        reviewMapper.delete(new LambdaQueryWrapper<TeamMaterialReview>()
            .eq(TeamMaterialReview::getTeamId, teamId));
        return requirementMapper.delete(new LambdaQueryWrapper<TeamMaterialRequirement>()
            .eq(TeamMaterialRequirement::getTeamId, teamId));
    }

    private boolean canMaintain(TeamCollaborationAuthorization.TeamContext team) {
        return team.canWrite() && (team.isLeader() || team.isMentor());
    }

    /** Leaders and mentors maintain the whole matrix; members own their rows. */
    private boolean canMaintainRequirement(TeamCollaborationAuthorization.TeamContext team,
                                           TeamMaterialRequirement requirement) {
        return canMaintain(team)
            || Objects.equals(requirement.getCreatedBy(), team.userId());
    }

    private void requireRequirement(Long teamId, TeamMaterialRequirement row) {
        if (row == null || !Objects.equals(row.getTeamId(), teamId)
            || !"ACTIVE".equals(row.getStatus())) {
            throw new TeamCollabException(TeamCollabException.Code.ASSET_NOT_FOUND,
                "材料要求不存在");
        }
    }

    private void updateReviewSummary(TeamMaterialRequirement requirement, String status,
                                     String note, Long userId) {
        Date now = new Date();
        int reviewVersion = (requirement.getReviewVersion() == null
            ? 0 : requirement.getReviewVersion()) + 1;
        TeamMaterialReview review = new TeamMaterialReview();
        review.setTeamId(requirement.getTeamId());
        review.setRequirementId(requirement.getId());
        review.setStatus(status);
        review.setNote(note);
        review.setReviewedBy(userId);
        review.setReviewedAt(now);
        review.setVersion(reviewVersion);
        reviewMapper.insert(review);
        requirementMapper.update(null, new LambdaUpdateWrapper<TeamMaterialRequirement>()
            .eq(TeamMaterialRequirement::getId, requirement.getId())
            .set(TeamMaterialRequirement::getReviewStatus, status)
            .set(TeamMaterialRequirement::getCurrentReviewId, review.getId())
            .set(TeamMaterialRequirement::getReviewVersion, reviewVersion)
            .set(TeamMaterialRequirement::getUpdatedBy, userId)
            .set(TeamMaterialRequirement::getUpdatedAt, now)
            .setSql("version = version + 1"));
    }

    private RequirementView requirementView(TeamMaterialRequirement row) {
        return requirementView(row, null);
    }

    private RequirementView requirementView(TeamMaterialRequirement row,
                                            TeamCollaborationAuthorization.TeamContext team) {
        List<EvidenceView> evidence = evidenceMapper.selectList(
                new LambdaQueryWrapper<TeamMaterialEvidence>()
                    .eq(TeamMaterialEvidence::getRequirementId, row.getId())
                    .orderByAsc(TeamMaterialEvidence::getId))
            .stream().map(item -> evidenceView(item, null, team)).toList();
        TeamMaterialReview review = row.getCurrentReviewId() == null ? null
            : reviewMapper.selectById(row.getCurrentReviewId());
        User creator = row.getCreatedBy() == null ? null : userService.getById(row.getCreatedBy());
        boolean canMaintain = team != null && canMaintainRequirement(team, row);
        // Any writable member may request a human review. Only leaders,
        // mentors and the requirement creator may record the full verdicts.
        boolean canReview = team != null && team.canWrite();
        return new RequirementView(row.getId(), row.getCompetitionId(),
            row.getCustomCompetition(), row.getTitle(), row.getDescription(),
            row.getCategory(), Boolean.TRUE.equals(row.getRequired()), row.getSourceType(),
            row.getReviewStatus(), row.getReviewVersion(), row.getVersion(),
            creator == null ? "已注销用户" : DisplayNameResolver.resolve(creator),
            review == null ? null : review.getNote(), row.getUpdatedAt(), row.getCreatedBy(),
            canMaintain, canReview, evidence);
    }

    private EvidenceView evidenceView(TeamMaterialEvidence row, EvidenceSnapshot snapshot) {
        return evidenceView(row, snapshot, null);
    }

    private EvidenceView evidenceView(TeamMaterialEvidence row, EvidenceSnapshot snapshot,
                                      TeamCollaborationAuthorization.TeamContext team) {
        EvidenceSnapshot info = snapshot == null
            ? evidenceValidator.describe(row.getTeamId(), row.getAssetType(), row.getAssetId())
            : snapshot;
        boolean canRemove = team != null && team.canWrite()
            && (canMaintain(team) || Objects.equals(row.getCreatedBy(), team.userId()));
        return new EvidenceView(row.getId(), row.getAssetType(), row.getAssetId(),
            row.getEvidenceChunkId(), info.label(), info.version(), info.deleted(),
            info.url(), row.getEvidenceExcerpt(), row.getMatchStatus(), row.getCreatedAt(),
            canRemove);
    }

    private String sourceType(String raw, boolean agent) {
        String value = raw == null || raw.isBlank() ? "MANUAL"
            : raw.trim().toUpperCase(Locale.ROOT);
        if (agent && !"AGENT_SUGGESTED".equals(value)) {
            throw invalid("Agent 建议必须标记为 AGENT_SUGGESTED");
        }
        if (!Set.of("CATALOG", "CUSTOM", "MANUAL", "AGENT_SUGGESTED").contains(value)) {
            throw invalid("材料来源类型不合法");
        }
        return value;
    }

    private String text(String raw, int max, String label) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) throw invalid(label + "不能为空");
        if (value.length() > max) throw invalid(label + "过长");
        return value;
    }

    private String trimToNull(String raw, int max) {
        if (raw == null) return null;
        String value = raw.trim();
        if (value.isEmpty()) return null;
        if (value.length() > max) throw invalid("字段内容过长");
        return value;
    }

    private String csvValue(String raw) {
        String value = raw == null ? "" : raw;
        if (!value.isEmpty() && "=+-@\t\r".indexOf(value.charAt(0)) >= 0) {
            value = "'" + value;
        }
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }

    private TeamCollabException invalid(String message) {
        return new TeamCollabException(TeamCollabException.Code.REQUEST_INVALID, message);
    }

    private TeamCollabException forbidden(String message) {
        return new TeamCollabException(TeamCollabException.Code.ASSET_FORBIDDEN, message);
    }

    private TeamCollabException versionConflict() {
        return new TeamCollabException(TeamCollabException.Code.EDIT_VERSION_CONFLICT,
            "材料要求已被其他成员更新，请刷新后重试");
    }

    public record RequirementRequest(String title, String description, String category,
                                     Boolean required, String sourceType,
                                     Integer competitionId, String customCompetition,
                                     Integer sortOrder, Integer version) {}
    public record EvidenceRequest(String assetType, Long assetId, Long evidenceChunkId,
                                  String evidenceExcerpt, String matchStatus) {}
    public record ReviewRequest(String status, String note) {}
    public record RequirementView(Long id, Integer competitionId, String customCompetition,
                                  String title, String description, String category,
                                  boolean required, String sourceType, String reviewStatus,
                                  Integer reviewVersion, Integer version, String createdByName,
                                  String reviewNote, Date updatedAt, Long createdBy,
                                  boolean canMaintain, boolean canReview,
                                  List<EvidenceView> evidence) {}
    public record EvidenceView(Long id, String assetType, Long assetId, Long evidenceChunkId,
                               String label, Integer version, boolean deleted, String url,
                               String excerpt, String matchStatus, Date createdAt,
                               boolean canRemove) {}
    public record MatrixView(List<RequirementView> requirements, Map<String, Long> counts,
                             long requiredTotal, long requiredMet,
                             Map<String, Object> competition) {}
    public record Export(String filename, String contentType, byte[] bytes) {}
    public record EvidenceSnapshot(String label, Integer version, boolean deleted, String url) {}
}
