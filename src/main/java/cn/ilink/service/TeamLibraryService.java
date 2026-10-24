package cn.ilink.service;

import cn.ilink.collab.TeamCollaborationIndexBridge;
import cn.ilink.entity.TaskSubmission;
import cn.ilink.entity.TeamApplication;
import cn.ilink.entity.TeamLibraryFile;
import cn.ilink.entity.User;
import cn.ilink.mapper.TaskSubmissionMapper;
import cn.ilink.mapper.TeamLibraryFileMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import cn.ilink.util.DisplayNameResolver;

/**
 * 团队资料库：文件树（parent_id 自引用）、同名冲突（保留两者/替换进回收站）、
 * 软删除回收站（仅队长可恢复，30 天自动彻底删除）、任务引用检查。
 * 权限模型：队长可处置全队文件；成员（含导师）只可处置自己上传的。
 */
@Slf4j
@Service
public class TeamLibraryService {

    @Autowired
    private TeamLibraryFileMapper libraryFileMapper;
    @Autowired
    private TaskSubmissionMapper taskSubmissionMapper;
    @Autowired
    private cn.ilink.mapper.TeamTaskMapper teamTaskMapper;
    @Autowired
    private UserService userService;
    @Autowired
    private cn.ilink.service.impl.TeamApplicationServiceImpl teamApplicationService;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private cn.ilink.service.NotificationService notificationService;
    @Autowired
    private cn.ilink.service.impl.TeamDemandServiceImpl teamDemandService;

    /** 彻底删除资料库文件时同步清理磁盘上的物理文件，避免只抹库、文件留在 uploads 里 */
    @Autowired
    private FileService fileService;

    @Autowired
    private TeamCollaborationIndexBridge collaborationIndexBridge;

    /** 上传文件的访问前缀，用于校验资料库登记的文件地址确实来自本站上传 */
    @org.springframework.beans.factory.annotation.Value("${file.access-url-prefix:/uploads/}")
    private String accessUrlPrefix;

    /** 允许的前缀形态：站内相对路径或 http(s) 站点根，避免前缀被配成 javascript: 之类 */
    private static final java.util.regex.Pattern ACCESS_PREFIX_PATTERN =
        java.util.regex.Pattern.compile("^(/[^/\\\\]*|[Hh][Tt][Tt][Pp][Ss]?://[^/\\\\]+)(/.*)?$");

    /** 归一化访问前缀：去首尾空白与结尾斜杠，空值退回 /uploads */
    private String normalizedAccessPrefix() {
        String prefix = accessUrlPrefix == null ? "" : accessUrlPrefix.trim().replace('\\', '/');
        if (prefix.isEmpty()) {
            return "/uploads";
        }
        if (!prefix.startsWith("/") && prefix.indexOf("://") < 0) {
            prefix = "/" + prefix;
        }
        while (prefix.length() > 1 && prefix.endsWith("/")) {
            prefix = prefix.substring(0, prefix.length() - 1);
        }
        return prefix;
    }

    /** 去掉 http(s)://host 部分，只留路径，用于同源绝对地址与相对前缀的比较 */
    private static String stripOrigin(String value) {
        String trimmed = value == null ? "" : value.trim();
        int scheme = trimmed.indexOf("://");
        if (scheme < 0) {
            return trimmed;
        }
        int slash = trimmed.indexOf('/', scheme + 3);
        return slash < 0 ? "" : trimmed.substring(slash);
    }

    /**
     * 校验资料库文件地址必须是本站上传产物。
     *
     * <p>此前 {@code POST /api/team/{id}/library/files} 只校验文件名，任何外部链接都能原样入库，
     * 成员在团队空间点开即跳转到站外（钓鱼/垃圾内容风险，核实报告 P2-3）。
     * 这里限定为「配置的访问前缀」或「同源相对 {@code /uploads/}」两种形态。
     */
    private void validateFileUrl(String url) {
        String value = url == null ? "" : url.trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException("文件地址不能为空");
        }
        if (value.regionMatches(true, 0, "javascript:", 0, 11)
            || value.regionMatches(true, 0, "data:", 0, 5)
            || value.regionMatches(true, 0, "vbscript:", 0, 9)) {
            throw new IllegalArgumentException("文件地址仅支持站内上传文件");
        }
        String prefix = normalizedAccessPrefix();
        if (!ACCESS_PREFIX_PATTERN.matcher(prefix).matches()) {
            // 前缀本身配置异常时不放行任意链接，避免把配置错误放大成安全问题
            throw new IllegalArgumentException("上传访问前缀配置异常，无法校验文件地址");
        }
        String prefixPath = stripOrigin(prefix);
        String valuePath = stripOrigin(value);
        boolean sameManagedPrefix = prefixPath != null && !prefixPath.isEmpty()
            && (valuePath.equals(prefixPath) || valuePath.startsWith(prefixPath + "/"));
        boolean relativeUploads = value.startsWith("/uploads/");
        if (!sameManagedPrefix && !relativeUploads) {
            throw new IllegalArgumentException("文件地址仅支持站内上传文件");
        }
    }

    /** 回收站保留天数 */
    private static final int TRASH_RETENTION_DAYS = 30;
    /** 提醒提前天数（清理前 N 天通知队长） */
    private static final int PURGE_REMIND_DAYS = 3;

    private static final Set<String> IMAGE_EXT = Set.of("png", "jpg", "jpeg", "gif", "webp", "bmp", "svg");
    private static final Set<String> TEXT_EXT = Set.of("txt", "csv", "json", "xml", "yml", "yaml", "log",
        "sql", "ini", "conf", "properties", "html", "htm", "css", "js", "ts", "java", "py", "c", "cpp", "h",
        "sh", "bat", "cmd");
    private static final Set<String> WORD_EXT = Set.of("docx", "doc");
    private static final Set<String> EXCEL_EXT = Set.of("xlsx", "xls");
    private static final Set<String> PPT_EXT = Set.of("pptx", "ppt");
    private static final Set<String> ARCHIVE_EXT = Set.of("zip", "rar", "7z", "tar", "gz");

    /** 文件类型推断：img/pdf/md/text/word/excel/ppt/zip/other，供前端选择预览渲染器与图标 */
    public static String guessFileType(String name) {
        String ext = extOf(name);
        if (ext.isEmpty()) return "other";
        if (IMAGE_EXT.contains(ext)) return "img";
        if (ARCHIVE_EXT.contains(ext)) return "zip";
        if (ext.equals("md") || ext.equals("markdown")) return "md";
        if (ext.equals("pdf")) return "pdf";
        if (TEXT_EXT.contains(ext)) return "text";
        if (WORD_EXT.contains(ext)) return "word";
        if (EXCEL_EXT.contains(ext)) return "excel";
        if (PPT_EXT.contains(ext)) return "ppt";
        return "other";
    }

    private static String extOf(String name) {
        int dot = name == null ? -1 : name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) return "";
        return name.substring(dot + 1).toLowerCase();
    }

    /** 同一父节点下是否已有同名未删除节点 */
    public TeamLibraryFile findSiblingByName(Long teamId, Long parentId, String name) {
        LambdaQueryWrapper<TeamLibraryFile> wrapper = new LambdaQueryWrapper<TeamLibraryFile>()
            .eq(TeamLibraryFile::getTeamId, teamId)
            .eq(TeamLibraryFile::getName, name)
            .eq(TeamLibraryFile::getIsDeleted, 0);
        // parentId 为 null 时不能用 eq(col, null)：SQL 里 parent_id = NULL 永不成立，会漏掉根目录同名
        if (parentId == null) {
            wrapper.isNull(TeamLibraryFile::getParentId);
        } else {
            wrapper.eq(TeamLibraryFile::getParentId, parentId);
        }
        return libraryFileMapper.selectOne(wrapper.last("LIMIT 1"));
    }

    /** 新建文件夹 */
    public TeamLibraryFile createFolder(Long teamId, Long userId, Long parentId, String name) {
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty()) throw new IllegalArgumentException("文件夹名称不能为空");
        if (trimmed.length() > 100) throw new IllegalArgumentException("文件夹名称过长（最多100字）");
        validateParent(teamId, parentId);
        if (findSiblingByName(teamId, parentId, trimmed) != null) {
            throw new IllegalArgumentException("同目录下已存在同名文件或文件夹");
        }
        TeamLibraryFile folder = new TeamLibraryFile();
        folder.setTeamId(teamId);
        folder.setParentId(parentId);
        folder.setName(trimmed);
        folder.setIsFolder(true);
        folder.setUploaderId(userId);
        folder.setIsDeleted(false);
        folder.setIsPurged(false);
        folder.setCreatedAt(new Date());
        libraryFileMapper.insert(folder);
        return folder;
    }

    /**
     * 登记上传的文件。conflictStrategy 为空且同目录存在同名时，返回 conflict=true 及现有文件信息，
     * 不落库；KEEP_BOTH 自动改名「name (1).ext」；REPLACE 把旧文件软删除进回收站后落库新文件。
     */
    public Map<String, Object> registerFile(Long teamId, Long userId, Long parentId, String name,
                                            String url, Long size, String conflictStrategy) {
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty()) throw new IllegalArgumentException("文件名不能为空");
        validateFileUrl(url);
        validateParent(teamId, parentId);
        TeamLibraryFile existing = findSiblingByName(teamId, parentId, trimmed);
        if (existing != null && (conflictStrategy == null || conflictStrategy.isEmpty())) {
            Map<String, Object> conflict = new LinkedHashMap<>();
            conflict.put("conflict", true);
            conflict.put("existing", toNode(existing, uploadersOf(teamId, Set.of(existing.getUploaderId()))));
            return conflict;
        }
        String finalName = trimmed;
        if (existing != null) {
            if ("REPLACE".equalsIgnoreCase(conflictStrategy)) {
                softDeleteRecursive(existing.getId(), userId, new HashSet<>());
            } else if ("KEEP_BOTH".equalsIgnoreCase(conflictStrategy)) {
                finalName = uniqueName(teamId, parentId, trimmed);
            } else {
                throw new IllegalArgumentException("不支持的同名处理策略");
            }
        }
        TeamLibraryFile file = new TeamLibraryFile();
        file.setTeamId(teamId);
        file.setParentId(parentId);
        file.setName(finalName);
        file.setIsFolder(false);
        file.setFileUrl(url);
        file.setOriginalFileName(trimmed);
        file.setFileSize(size);
        file.setFileType(guessFileType(finalName));
        file.setUploaderId(userId);
        file.setIsDeleted(false);
        file.setIsPurged(false);
        file.setCreatedAt(new Date());
        libraryFileMapper.insert(file);
        enqueueIndexUpsert(file);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("conflict", false);
        result.put("file", toNode(file, uploadersOf(teamId, Set.of(userId))));
        return result;
    }

    /** 「name.ext 已存在 → name (1).ext → name (2).ext」，编号永远加在扩展名之前 */
    private String uniqueName(Long teamId, Long parentId, String name) {
        String base = name;
        String ext = "";
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            base = name.substring(0, dot);
            ext = name.substring(dot);
        }
        for (int i = 1; i < 1000; i++) {
            String candidate = base + " (" + i + ")" + ext;
            if (findSiblingByName(teamId, parentId, candidate) == null) return candidate;
        }
        return base + " (" + System.currentTimeMillis() + ")" + ext;
    }

    /** 重命名（队长或上传者） */
    public TeamLibraryFile rename(Long fileId, Long userId, boolean isLeader, String newName) {
        TeamLibraryFile file = requireFile(fileId);
        requireManagePermission(file, userId, isLeader);
        String trimmed = newName == null ? "" : newName.trim();
        if (trimmed.isEmpty()) throw new IllegalArgumentException("名称不能为空");
        if (trimmed.length() > 100) throw new IllegalArgumentException("名称过长（最多100字）");
        if (!trimmed.equals(file.getName()) && findSiblingByName(file.getTeamId(), file.getParentId(), trimmed) != null) {
            throw new IllegalArgumentException("同目录下已存在同名文件或文件夹");
        }
        TeamLibraryFile update = new TeamLibraryFile();
        update.setId(fileId);
        update.setName(trimmed);
        if (!file.getIsFolder()) {
            update.setFileType(guessFileType(trimmed));
        }
        libraryFileMapper.updateById(update);
        file.setName(trimmed);
        enqueueIndexUpsert(file);
        return file;
    }

    /**
     * 移动到目标目录（targetParentId=null 表示根目录）。
     * 目标目录必须存在且同团队；文件夹不能移进自己或自己的子孙（防成环）；目标目录同名则拒绝。
     */
    public void move(Long fileId, Long userId, boolean isLeader, Long targetParentId) {
        TeamLibraryFile file = requireFile(fileId);
        requireManagePermission(file, userId, isLeader);
        if (Boolean.TRUE.equals(file.getIsDeleted())) {
            throw new IllegalArgumentException("回收站中的内容不能移动");
        }
        if (java.util.Objects.equals(targetParentId, file.getParentId())) {
            throw new IllegalArgumentException("已在目标目录中");
        }
        validateParent(file.getTeamId(), targetParentId);
        if (Boolean.TRUE.equals(file.getIsFolder())) {
            requireNotDescendant(fileId, targetParentId);
        }
        if (findSiblingByName(file.getTeamId(), targetParentId, file.getName()) != null) {
            throw new IllegalArgumentException("目标目录下已存在同名文件或文件夹");
        }
        // updateById 会跳过 null 字段，parent_id 需要清空成根目录，只能用 UpdateWrapper 显式 set
        libraryFileMapper.update(null, new LambdaUpdateWrapper<TeamLibraryFile>()
            .eq(TeamLibraryFile::getId, fileId)
            .set(TeamLibraryFile::getParentId, targetParentId));
        file.setParentId(targetParentId);
        enqueueIndexUpsert(file);
    }

    /**
     * 重新建立检索索引（上传者或队长）：索引失败、已失效或被跳过的文件可重试。
     * 排队中的文件不重复入队，避免一次点击产生两条队列任务。
     */
    public void reindex(Long fileId, Long userId, boolean isLeader) {
        TeamLibraryFile file = requireFile(fileId);
        requireManagePermission(file, userId, isLeader);
        if (Boolean.TRUE.equals(file.getIsFolder())) {
            throw new IllegalArgumentException("文件夹不需要建立检索索引");
        }
        if (Boolean.TRUE.equals(file.getIsDeleted())) {
            throw new IllegalArgumentException("回收站中的文件需要先从回收站恢复");
        }
        String status = file.getIndexStatus();
        if (TeamLibraryFile.INDEX_PENDING.equals(status)
            || TeamLibraryFile.INDEX_INDEXING.equals(status)) {
            throw new IllegalArgumentException("索引正在进行中，请稍后查看结果");
        }
        enqueueIndexUpsert(file);
    }

    /** 目标目录不能是被移动文件夹自身或它的子孙，否则树会成环 */
    private void requireNotDescendant(Long folderId, Long targetParentId) {
        Set<Long> visited = new HashSet<>();
        Long cursor = targetParentId;
        while (cursor != null && visited.add(cursor)) {
            if (cursor.equals(folderId)) {
                throw new IllegalArgumentException("不能把文件夹移动到它自己或它的子目录里");
            }
            TeamLibraryFile node = libraryFileMapper.selectById(cursor);
            cursor = node == null ? null : node.getParentId();
        }
    }

    /**
     * 软删除（进回收站）。文件夹级联删除全部子孙。
     * 返回被任务引用而被删除的文件名列表（前端 Toast/提示用）。
     */
    public List<String> softDelete(Long fileId, Long userId, boolean isLeader) {
        TeamLibraryFile file = requireFile(fileId);
        requireManagePermission(file, userId, isLeader);
        Set<String> referencedNames = new HashSet<>();
        softDeleteRecursive(fileId, userId, referencedNames);
        return new ArrayList<>(referencedNames);
    }

    private void softDeleteRecursive(Long fileId, Long userId, Set<String> referencedNames) {
        TeamLibraryFile file = libraryFileMapper.selectById(fileId);
        if (file == null || Boolean.TRUE.equals(file.getIsDeleted())) return;
        if (!file.getIsFolder() && file.getFileUrl() != null) {
            if (!findReferencingTasks(file).isEmpty()) {
                referencedNames.add(file.getName());
            }
        }
        if (file.getIsFolder()) {
            for (TeamLibraryFile child : libraryFileMapper.selectList(new LambdaQueryWrapper<TeamLibraryFile>()
                    .eq(TeamLibraryFile::getParentId, fileId)
                    .eq(TeamLibraryFile::getIsDeleted, 0))) {
                softDeleteRecursive(child.getId(), userId, referencedNames);
            }
        }
        TeamLibraryFile update = new TeamLibraryFile();
        update.setId(fileId);
        update.setIsDeleted(true);
        update.setDeletedAt(new Date());
        update.setDeletedBy(userId);
        libraryFileMapper.updateById(update);
        enqueueIndexDelete(file);
    }

    /** 恢复（仅队长）：恢复节点及其所有已删除的祖先，保证树路径完整 */
    public void restore(Long fileId, Long userId, boolean isLeader) {
        if (!isLeader) throw new IllegalStateException("只有队长可以访问回收站");
        TeamLibraryFile file = libraryFileMapper.selectById(fileId);
        if (file == null) throw new IllegalArgumentException("文件不存在");
        // 恢复祖先链
        Deque<Long> chain = new ArrayDeque<>();
        Long cursor = file.getParentId();
        while (cursor != null) {
            TeamLibraryFile parent = libraryFileMapper.selectById(cursor);
            if (parent == null) break;
            if (Boolean.TRUE.equals(parent.getIsDeleted())) chain.push(parent.getId());
            cursor = parent.getParentId();
        }
        while (!chain.isEmpty()) {
            markRestored(chain.pop());
        }
        markRestoredWithChildren(fileId);
    }

    private void markRestoredWithChildren(Long fileId) {
        markRestored(fileId);
        for (TeamLibraryFile child : libraryFileMapper.selectList(new LambdaQueryWrapper<TeamLibraryFile>()
                .eq(TeamLibraryFile::getParentId, fileId)
                .eq(TeamLibraryFile::getIsDeleted, 1)
                .eq(TeamLibraryFile::getIsPurged, 0))) {
            markRestoredWithChildren(child.getId());
        }
    }

    private void markRestored(Long fileId) {
        libraryFileMapper.update(null, new LambdaUpdateWrapper<TeamLibraryFile>()
            .eq(TeamLibraryFile::getId, fileId)
            .set(TeamLibraryFile::getIsDeleted, false)
            .set(TeamLibraryFile::getDeletedAt, null)
            .set(TeamLibraryFile::getDeletedBy, null));
        TeamLibraryFile restored = libraryFileMapper.selectById(fileId);
        enqueueIndexUpsert(restored);
    }

    /** 彻底删除（仅队长，不可逆）：文件夹级联；抹去 file_url 使记录不可再访问 */
    public int purge(Long fileId, Long userId, boolean isLeader) {
        if (!isLeader) throw new IllegalStateException("只有队长可以彻底删除文件");
        TeamLibraryFile file = libraryFileMapper.selectById(fileId);
        if (file == null) throw new IllegalArgumentException("文件不存在");
        return purgeRecursive(file);
    }

    private int purgeRecursive(TeamLibraryFile file) {
        int count = 0;
        if (file.getIsFolder()) {
            for (TeamLibraryFile child : libraryFileMapper.selectList(new LambdaQueryWrapper<TeamLibraryFile>()
                    .eq(TeamLibraryFile::getParentId, file.getId())
                    .eq(TeamLibraryFile::getIsDeleted, 1))) {
                count += purgeRecursive(child);
            }
        }
        // 物理文件：文件URL取自删除前的记录，"彻底删除"必须同时把磁盘上的文件清掉，
        // 否则库记录消失但 uploads 里永久留档（历史实现只抹了 file_url，文件仍在）。
        if (!Boolean.TRUE.equals(file.getIsFolder())) {
            deletePhysicalFile(file.getFileUrl());
            enqueueIndexDelete(file);
        }
        TeamLibraryFile update = new TeamLibraryFile();
        update.setId(file.getId());
        update.setIsPurged(true);
        update.setFileUrl(null);
        libraryFileMapper.updateById(update);
        return count + 1;
    }

    /** 删除磁盘上的物理文件；失败只记日志，不影响库记录清理（清理任务不应因文件已丢失而回滚） */
    private void deletePhysicalFile(String fileUrl) {
        if (fileUrl == null || fileUrl.trim().isEmpty()) {
            return;
        }
        try {
            fileService.delete(fileUrl);
        } catch (Exception e) {
            log.warn("删除资料库物理文件失败 url={}: {}", fileUrl, e.getMessage());
        }
    }

    /** 任务引用检查：团队任务的提交材料（attachments JSON 含 libraryFileId 或 url） */
    public List<Map<String, Object>> findReferencingTasks(TeamLibraryFile file) {
        List<Map<String, Object>> refs = new ArrayList<>();
        if (file.getIsFolder() || file.getId() == null) return refs;
        List<Long> taskIds = taskIdsOf(file.getTeamId());
        if (taskIds.isEmpty()) return refs;
        List<TaskSubmission> submissions = taskSubmissionMapper.selectList(new LambdaQueryWrapper<TaskSubmission>()
            .in(TaskSubmission::getTaskId, taskIds));
        for (TaskSubmission submission : submissions) {
            if (referencesFile(submission.getAttachments(), file.getId(), file.getFileUrl())) {
                Map<String, Object> ref = new LinkedHashMap<>();
                ref.put("taskId", submission.getTaskId());
                refs.add(ref);
            }
        }
        return refs;
    }

    private boolean referencesFile(String attachmentsJson, Long fileId, String fileUrl) {
        if (attachmentsJson == null || attachmentsJson.isBlank()) return false;
        try {
            JsonNode root = objectMapper.readTree(attachmentsJson);
            if (root.isArray()) {
                for (JsonNode item : root) {
                    if (item.isObject()) {
                        if (item.hasNonNull("libraryFileId") && item.get("libraryFileId").asLong() == fileId) return true;
                        if (fileUrl != null && item.hasNonNull("url") && fileUrl.equals(item.get("url").asText())) return true;
                    } else if (item.isTextual() && fileUrl != null && item.asText().startsWith(fileUrl)) {
                        return true;
                    }
                }
            }
        } catch (Exception e) {
            // 兼容旧格式 url|name 的文本
            return fileUrl != null && attachmentsJson.contains(fileUrl);
        }
        return false;
    }

    /** 文件树（view=active 默认 | trash 回收站，回收站仅队长可访问） */
    public List<Map<String, Object>> getTree(Long teamId, boolean trash) {
        List<TeamLibraryFile> rows = libraryFileMapper.selectList(new LambdaQueryWrapper<TeamLibraryFile>()
            .eq(TeamLibraryFile::getTeamId, teamId)
            .eq(TeamLibraryFile::getIsDeleted, trash)
            .eq(TeamLibraryFile::getIsPurged, false)
            .orderByAsc(TeamLibraryFile::getName));
        Map<Long, User> uploaders = uploadersOf(teamId, rows.stream().map(TeamLibraryFile::getUploaderId).collect(java.util.stream.Collectors.toSet()));
        Map<Long, Set<Long>> taskRefs = taskReferencesByFileId(teamId);
        Map<Long, Map<String, Object>> nodeMap = new HashMap<>();
        List<Map<String, Object>> roots = new ArrayList<>();
        // 文件夹在前、文件在后，同层按名称排序
        rows.sort(Comparator
            .comparing((TeamLibraryFile f) -> !Boolean.TRUE.equals(f.getIsFolder()))
            .thenComparing(f -> f.getName() == null ? "" : f.getName().toLowerCase()));
        for (TeamLibraryFile row : rows) {
            nodeMap.put(row.getId(), toNode(row, uploaders));
        }
        for (TeamLibraryFile row : rows) {
            Map<String, Object> node = nodeMap.get(row.getId());
            node.put("referenced", taskRefs.containsKey(row.getId()));
            if (trash && row.getDeletedAt() != null) {
                long remainDays = TRASH_RETENTION_DAYS
                    - (System.currentTimeMillis() - row.getDeletedAt().getTime()) / (24L * 3600 * 1000);
                node.put("remainDays", Math.max(0, remainDays));
            }
            if (row.getParentId() != null && nodeMap.containsKey(row.getParentId())) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> siblings =
                    (List<Map<String, Object>>) nodeMap.get(row.getParentId()).get("children");
                siblings.add(node);
            } else {
                roots.add(node);
            }
        }
        return roots;
    }

    /**
     * 队伍被删除时清空其资料库记录。
     *
     * <p>两条硬删除路径（管理端 {@code AdminDataService.deleteTeamInternal}、队长删除招募中的队伍
     * {@code TeamController.deleteTeam}）都必须调用本方法：否则资料行会变成 {@code team_id} 指向
     * 不存在队伍的孤儿记录，且删除接口要求队伍存在 + 成员权限，孤儿再也无法经 API 清除
     * （核实报告 P2-4）。
     *
     * @return 实际删除的行数
     */
    @Transactional(rollbackFor = Exception.class)
    public int deleteAllForTeam(Long teamId) {
        if (teamId == null) {
            return 0;
        }
        // 队伍没了，资料库里这些文件也再没有入口访问，连同磁盘文件一起清掉
        List<TeamLibraryFile> files = libraryFileMapper.selectList(new LambdaQueryWrapper<TeamLibraryFile>()
            .eq(TeamLibraryFile::getTeamId, teamId));
        for (TeamLibraryFile file : files) {
            if (!Boolean.TRUE.equals(file.getIsFolder())) {
                deletePhysicalFile(file.getFileUrl());
                enqueueIndexDelete(file);
            }
        }
        return libraryFileMapper.delete(new LambdaQueryWrapper<TeamLibraryFile>()
            .eq(TeamLibraryFile::getTeamId, teamId));
    }

    /**
     * 回收站定时清理（每天 03:30）：超过保留期的记录彻底删除；
     * 剩余保留期不足 3 天的向队长推送提醒（文案含当天日期，每天至多一条）。
     */
    @org.springframework.scheduling.annotation.Scheduled(cron = "0 30 3 * * ?")
    public void purgeExpiredTrash() {
        List<TeamLibraryFile> expired = libraryFileMapper.selectList(new LambdaQueryWrapper<TeamLibraryFile>()
            .eq(TeamLibraryFile::getIsDeleted, 1)
            .eq(TeamLibraryFile::getIsPurged, 0)
            .lt(TeamLibraryFile::getDeletedAt, new Date(System.currentTimeMillis() - (long) TRASH_RETENTION_DAYS * 24 * 3600 * 1000)));
        for (TeamLibraryFile file : expired) {
            try {
                purgeRecursive(file);
            } catch (Exception e) {
                log.warn("回收站清理失败 fileId={}: {}", file.getId(), e.getMessage());
            }
        }
        remindUpcomingPurge();
    }

    private void remindUpcomingPurge() {
        Date remindFrom = new Date(System.currentTimeMillis()
            - (long) (TRASH_RETENTION_DAYS - PURGE_REMIND_DAYS) * 24 * 3600 * 1000);
        List<TeamLibraryFile> upcoming = libraryFileMapper.selectList(new LambdaQueryWrapper<TeamLibraryFile>()
            .eq(TeamLibraryFile::getIsDeleted, 1)
            .eq(TeamLibraryFile::getIsPurged, 0)
            .isNotNull(TeamLibraryFile::getDeletedAt)
            .lt(TeamLibraryFile::getDeletedAt, remindFrom));
        if (upcoming.isEmpty()) return;
        Map<Long, Integer> countByTeam = new HashMap<>();
        for (TeamLibraryFile file : upcoming) {
            countByTeam.merge(file.getTeamId(), 1, Integer::sum);
        }
        String today = java.time.LocalDate.now().toString();
        for (Map.Entry<Long, Integer> entry : countByTeam.entrySet()) {
            cn.ilink.entity.TeamDemand team = teamDemandService.getById(entry.getKey());
            if (team == null || team.getCreatorId() == null) continue;
            notificationService.create(team.getCreatorId(), "LIBRARY_PURGE_REMIND", "回收站即将清理",
                "团队「" + team.getTitle() + "」的回收站中有 " + entry.getValue()
                    + " 个文件将于 3 天内被彻底删除（" + today + "），如需保留请及时恢复。", null);
        }
    }

    /** 文件 ID → 引用它的任务 ID 集合（按团队全部任务提交聚合） */
    public Map<Long, Set<Long>> taskReferencesByFileId(Long teamId) {
        Map<Long, Set<Long>> result = new HashMap<>();
        List<Long> taskIds = taskIdsOf(teamId);
        if (taskIds.isEmpty()) return result;
        List<TaskSubmission> submissions = taskSubmissionMapper.selectList(new LambdaQueryWrapper<TaskSubmission>()
            .in(TaskSubmission::getTaskId, taskIds));
        for (TaskSubmission submission : submissions) {
            if (submission.getAttachments() == null || submission.getAttachments().isBlank()) continue;
            try {
                JsonNode root = objectMapper.readTree(submission.getAttachments());
                if (root.isArray()) {
                    for (JsonNode item : root) {
                        Long fileId = null;
                        if (item.isObject() && item.hasNonNull("libraryFileId")) {
                            fileId = item.get("libraryFileId").asLong();
                        }
                        if (fileId != null) {
                            result.computeIfAbsent(fileId, k -> new HashSet<>()).add(submission.getTaskId());
                        }
                    }
                }
            } catch (Exception ignored) {
                // 旧格式无 fileId，无法建立精确引用
            }
        }
        return result;
    }

    private List<Long> taskIdsOf(Long teamId) {
        List<Long> ids = new ArrayList<>();
        for (cn.ilink.entity.TeamTask task : teamTaskMapper.selectList(new LambdaQueryWrapper<cn.ilink.entity.TeamTask>()
                .eq(cn.ilink.entity.TeamTask::getTeamId, teamId))) {
            ids.add(task.getId());
        }
        return ids;
    }

    /** 上传者信息（含导师标签） */
    public Map<Long, User> uploadersOf(Long teamId, Set<Long> ids) {
        Map<Long, User> map = new HashMap<>();
        if (ids == null || ids.isEmpty()) return map;
        for (Long id : ids) {
            User user = userService.getById(id);
            if (user != null) map.put(id, user);
        }
        return map;
    }

    private Map<String, Object> toNode(TeamLibraryFile file, Map<Long, User> uploaders) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", file.getId());
        node.put("parentId", file.getParentId());
        node.put("name", file.getName());
        node.put("originalFileName", file.getOriginalFileName());
        node.put("isFolder", Boolean.TRUE.equals(file.getIsFolder()));
        node.put("fileType", file.getFileType());
        node.put("fileSize", file.getFileSize());
        node.put("fileUrl", file.getFileUrl());
        node.put("uploaderId", file.getUploaderId());
        User uploader = uploaders.get(file.getUploaderId());
        node.put("uploaderName", uploader != null
            ? DisplayNameResolver.resolve(uploader)
            : "未知成员");
        node.put("isMentor", isMentorUploader(file));
        node.put("createdAt", file.getCreatedAt());
        node.put("contentRevision", file.getContentRevision());
        node.put("indexStatus", file.getIndexStatus());
        node.put("indexError", file.getIndexError());
        node.put("indexedAt", file.getIndexedAt());
        node.put("children", new ArrayList<Map<String, Object>>());
        return node;
    }

    private void enqueueIndexUpsert(TeamLibraryFile file) {
        try {
            collaborationIndexBridge.enqueueUpsert(file);
        } catch (Exception e) {
            log.warn("团队资料索引入队失败（不影响文件操作） fileId={}: {}",
                file == null ? null : file.getId(), e.getMessage());
        }
    }

    private void enqueueIndexDelete(TeamLibraryFile file) {
        try {
            collaborationIndexBridge.enqueueDelete(file);
        } catch (Exception e) {
            log.warn("团队资料索引删除登记失败（不影响文件操作） fileId={}: {}",
                file == null ? null : file.getId(), e.getMessage());
        }
    }

    /** 导师标签：上传者是团队的 MENTOR 成员（只做展示，不参与权限） */
    private boolean isMentorUploader(TeamLibraryFile file) {
        Long count = teamApplicationService.count(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<TeamApplication>()
            .eq(TeamApplication::getTeamId, file.getTeamId())
            .eq(TeamApplication::getUserId, file.getUploaderId())
            .eq(TeamApplication::getStatus, "APPROVED")
            .eq(TeamApplication::getMemberRole, "MENTOR"));
        return count != null && count > 0;
    }

    private void validateParent(Long teamId, Long parentId) {
        if (parentId == null) return;
        TeamLibraryFile parent = libraryFileMapper.selectById(parentId);
        if (parent == null || !parent.getTeamId().equals(teamId) || !Boolean.TRUE.equals(parent.getIsFolder())
            || Boolean.TRUE.equals(parent.getIsDeleted())) {
            throw new IllegalArgumentException("目标文件夹不存在或已删除");
        }
    }

    private TeamLibraryFile requireFile(Long fileId) {
        TeamLibraryFile file = libraryFileMapper.selectById(fileId);
        if (file == null || Boolean.TRUE.equals(file.getIsPurged())) {
            throw new IllegalArgumentException("文件不存在或已彻底删除");
        }
        return file;
    }

    private void requireManagePermission(TeamLibraryFile file, Long userId, boolean isLeader) {
        if (!isLeader && !file.getUploaderId().equals(userId)) {
            throw new IllegalStateException("只有文件上传者或队长可以执行此操作");
        }
    }
}
