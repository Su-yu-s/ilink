package cn.ilink.controller;

import cn.ilink.common.ControllerUtils;
import cn.ilink.common.Result;
import cn.ilink.entity.TeamApplication;
import cn.ilink.entity.TeamDemand;
import cn.ilink.entity.TeamLibraryFile;
import cn.ilink.entity.TeamTask;
import cn.ilink.entity.User;
import cn.ilink.mapper.TeamLibraryFileMapper;
import cn.ilink.service.TeamLibraryService;
import cn.ilink.service.TeamTaskService;
import cn.ilink.service.impl.TeamApplicationServiceImpl;
import cn.ilink.service.impl.TeamDemandServiceImpl;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpSession;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 团队资料库 API。权限：读=团队成员；写=成员（处置限上传者本人或队长）；
 * 回收站的恢复/彻底删除=仅队长。导师只是展示标签，不参与权限判断。
 */
@Slf4j
@RestController
@RequestMapping("/api")
public class TeamLibraryController {

    private static final String MSG_TEAM_NOT_FOUND = "团队不存在";

    @Autowired
    private TeamLibraryService libraryService;
    @Autowired
    private TeamLibraryFileMapper libraryFileMapper;
    @Autowired
    private TeamDemandServiceImpl teamDemandService;
    @Autowired
    private TeamApplicationServiceImpl teamApplicationService;
    @Autowired
    private TeamTaskService teamTaskService;

    @GetMapping("/team/{teamId}/library")
    @ResponseBody
    public ResponseEntity<Result<?>> getLibrary(@PathVariable Long teamId,
                                                @RequestParam(value = "view", defaultValue = "active") String view,
                                                HttpSession session) {
        User user = ControllerUtils.requireUser(session);
        if (user == null) {
            return Result.unauthorized().toResponseEntity();
        }
        TeamDemand team = teamDemandService.getById(teamId);
        if (team == null) {
            return Result.notFound(MSG_TEAM_NOT_FOUND).toResponseEntity();
        }
        if (!isTeamParticipant(team, user)) {
            return Result.forbidden().toResponseEntity();
        }
        boolean trash = "trash".equalsIgnoreCase(view);
        if (trash && !isTeamLeader(team, user)) {
            return Result.fail(403, "只有队长可以访问回收站").toResponseEntity();
        }
        return Result.ok("加载成功", libraryService.getTree(teamId, trash)).toResponseEntity();
    }

    @PostMapping("/team/{teamId}/library/folders")
    @ResponseBody
    public ResponseEntity<Result<?>> createFolder(@PathVariable Long teamId,
                                                  @RequestBody Map<String, Object> body,
                                                  HttpSession session) {
        User user = ControllerUtils.requireUser(session);
        if (user == null) {
            return Result.unauthorized().toResponseEntity();
        }
        TeamDemand team = teamDemandService.getById(teamId);
        if (team == null) {
            return Result.notFound(MSG_TEAM_NOT_FOUND).toResponseEntity();
        }
        if (!isTeamParticipant(team, user)) {
            return Result.forbidden().toResponseEntity();
        }
        try {
            TeamLibraryFile folder = libraryService.createFolder(teamId, user.getId(),
                longOf(body == null ? null : body.get("parentId")),
                strOf(body == null ? null : body.get("name")));
            return Result.ok("文件夹已创建", folder).toResponseEntity();
        } catch (IllegalArgumentException e) {
            return Result.badRequest(e.getMessage()).toResponseEntity();
        }
    }

    /**
     * 登记上传的文件。同目录存在同名且未带 conflictStrategy 时返回 conflict=true 与现有文件信息；
     * conflictStrategy=KEEP_BOTH 自动改名 / REPLACE 把旧文件移入回收站。
     */
    @PostMapping("/team/{teamId}/library/files")
    @ResponseBody
    public ResponseEntity<Result<?>> registerFile(@PathVariable Long teamId,
                                                  @RequestBody Map<String, Object> body,
                                                  HttpSession session) {
        User user = ControllerUtils.requireUser(session);
        if (user == null) {
            return Result.unauthorized().toResponseEntity();
        }
        TeamDemand team = teamDemandService.getById(teamId);
        if (team == null) {
            return Result.notFound(MSG_TEAM_NOT_FOUND).toResponseEntity();
        }
        if (!isTeamParticipant(team, user)) {
            return Result.forbidden().toResponseEntity();
        }
        try {
            Map<String, Object> result = libraryService.registerFile(teamId, user.getId(),
                longOf(body == null ? null : body.get("parentId")),
                strOf(body == null ? null : body.get("name")),
                strOf(body == null ? null : body.get("url")),
                longOf(body == null ? null : body.get("size")),
                strOf(body == null ? null : body.get("conflictStrategy")));
            return Result.ok("上传成功", result).toResponseEntity();
        } catch (IllegalArgumentException e) {
            return Result.badRequest(e.getMessage()).toResponseEntity();
        }
    }

    /** 文件的任务引用列表（删除确认弹窗展示用） */
    @GetMapping("/team/{teamId}/library/files/{fileId}/references")
    @ResponseBody
    public ResponseEntity<Result<?>> getFileReferences(@PathVariable Long teamId,
                                                       @PathVariable Long fileId,
                                                       HttpSession session) {
        User user = ControllerUtils.requireUser(session);
        if (user == null) {
            return Result.unauthorized().toResponseEntity();
        }
        TeamDemand team = teamDemandService.getById(teamId);
        if (team == null) {
            return Result.notFound(MSG_TEAM_NOT_FOUND).toResponseEntity();
        }
        if (!isTeamParticipant(team, user)) {
            return Result.forbidden().toResponseEntity();
        }
        TeamLibraryFile file = libraryFileMapper.selectById(fileId);
        if (file == null || !file.getTeamId().equals(teamId)) {
            return Result.notFound("文件不存在").toResponseEntity();
        }
        List<Map<String, Object>> refs = new ArrayList<>();
        for (Map<String, Object> ref : libraryService.findReferencingTasks(file)) {
            Long taskId = (Long) ref.get("taskId");
            TeamTask task = teamTaskService.getById(taskId);
            if (task == null) continue;
            refs.add(Map.of("taskId", taskId, "taskTitle", task.getTaskTitle(), "taskStatus", task.getStatus()));
        }
        return Result.ok("加载成功", refs).toResponseEntity();
    }

    @PutMapping("/team/{teamId}/library/files/{fileId}")
    @ResponseBody
    public ResponseEntity<Result<?>> rename(@PathVariable Long teamId,
                                            @PathVariable Long fileId,
                                            @RequestBody Map<String, Object> body,
                                            HttpSession session) {
        User user = ControllerUtils.requireUser(session);
        if (user == null) {
            return Result.unauthorized().toResponseEntity();
        }
        TeamDemand team = teamDemandService.getById(teamId);
        if (team == null) {
            return Result.notFound(MSG_TEAM_NOT_FOUND).toResponseEntity();
        }
        if (!isTeamParticipant(team, user)) {
            return Result.forbidden().toResponseEntity();
        }
        try {
            TeamLibraryFile file = libraryService.rename(fileId, user.getId(),
                isTeamLeader(team, user), strOf(body == null ? null : body.get("name")));
            return Result.ok("重命名成功", file).toResponseEntity();
        } catch (IllegalArgumentException e) {
            return Result.badRequest(e.getMessage()).toResponseEntity();
        } catch (IllegalStateException e) {
            return Result.fail(403, e.getMessage()).toResponseEntity();
        }
    }

    /** 移动到其他目录：body.parentId 为目标文件夹 ID，null 表示根目录 */
    @PutMapping("/team/{teamId}/library/files/{fileId}/move")
    @ResponseBody
    public ResponseEntity<Result<?>> move(@PathVariable Long teamId,
                                          @PathVariable Long fileId,
                                          @RequestBody Map<String, Object> body,
                                          HttpSession session) {
        User user = ControllerUtils.requireUser(session);
        if (user == null) {
            return Result.unauthorized().toResponseEntity();
        }
        TeamDemand team = teamDemandService.getById(teamId);
        if (team == null) {
            return Result.notFound(MSG_TEAM_NOT_FOUND).toResponseEntity();
        }
        if (!isTeamParticipant(team, user)) {
            return Result.forbidden().toResponseEntity();
        }
        try {
            libraryService.move(fileId, user.getId(), isTeamLeader(team, user),
                longOf(body == null ? null : body.get("parentId")));
            return Result.ok("已移动", null).toResponseEntity();
        } catch (IllegalArgumentException e) {
            return Result.badRequest(e.getMessage()).toResponseEntity();
        } catch (IllegalStateException e) {
            return Result.fail(403, e.getMessage()).toResponseEntity();
        }
    }

    /** 重新建立检索索引：索引失败/失效/跳过的文件可由上传者或队长重试 */
    @PostMapping("/team/{teamId}/library/files/{fileId}/reindex")
    @ResponseBody
    public ResponseEntity<Result<?>> reindex(@PathVariable Long teamId,
                                             @PathVariable Long fileId,
                                             HttpSession session) {
        User user = ControllerUtils.requireUser(session);
        if (user == null) {
            return Result.unauthorized().toResponseEntity();
        }
        TeamDemand team = teamDemandService.getById(teamId);
        if (team == null) {
            return Result.notFound(MSG_TEAM_NOT_FOUND).toResponseEntity();
        }
        if (!isTeamParticipant(team, user)) {
            return Result.forbidden().toResponseEntity();
        }
        try {
            libraryService.reindex(fileId, user.getId(), isTeamLeader(team, user));
            return Result.ok("已重新提交检索索引", null).toResponseEntity();
        } catch (IllegalArgumentException e) {
            return Result.badRequest(e.getMessage()).toResponseEntity();
        } catch (IllegalStateException e) {
            return Result.fail(403, e.getMessage()).toResponseEntity();
        }
    }

    @DeleteMapping("/team/{teamId}/library/files/{fileId}")
    @ResponseBody
    public ResponseEntity<Result<?>> delete(@PathVariable Long teamId,
                                            @PathVariable Long fileId,
                                            @RequestParam(value = "purge", defaultValue = "false") boolean purge,
                                            HttpSession session) {
        User user = ControllerUtils.requireUser(session);
        if (user == null) {
            return Result.unauthorized().toResponseEntity();
        }
        TeamDemand team = teamDemandService.getById(teamId);
        if (team == null) {
            return Result.notFound(MSG_TEAM_NOT_FOUND).toResponseEntity();
        }
        if (!isTeamParticipant(team, user)) {
            return Result.forbidden().toResponseEntity();
        }
        try {
            if (purge) {
                int count = libraryService.purge(fileId, user.getId(), isTeamLeader(team, user));
                return Result.ok("已彻底删除 " + count + " 项", null).toResponseEntity();
            }
            List<String> referencedNames = libraryService.softDelete(fileId, user.getId(), isTeamLeader(team, user));
            return Result.ok(referencedNames.isEmpty()
                ? "已删除，30 天内可在回收站恢复"
                : "已删除。以下文件仍被任务引用，任务中将显示为「文件已删除」：" + String.join("、", referencedNames),
                null).toResponseEntity();
        } catch (IllegalArgumentException e) {
            return Result.badRequest(e.getMessage()).toResponseEntity();
        } catch (IllegalStateException e) {
            return Result.fail(403, e.getMessage()).toResponseEntity();
        }
    }

    @PutMapping("/team/{teamId}/library/files/{fileId}/restore")
    @ResponseBody
    public ResponseEntity<Result<?>> restore(@PathVariable Long teamId,
                                             @PathVariable Long fileId,
                                             HttpSession session) {
        User user = ControllerUtils.requireUser(session);
        if (user == null) {
            return Result.unauthorized().toResponseEntity();
        }
        TeamDemand team = teamDemandService.getById(teamId);
        if (team == null) {
            return Result.notFound(MSG_TEAM_NOT_FOUND).toResponseEntity();
        }
        try {
            libraryService.restore(fileId, user.getId(), isTeamLeader(team, user));
            return Result.ok("已恢复", null).toResponseEntity();
        } catch (IllegalArgumentException e) {
            return Result.badRequest(e.getMessage()).toResponseEntity();
        } catch (IllegalStateException e) {
            return Result.fail(403, e.getMessage()).toResponseEntity();
        }
    }

    // ---------- 通用 ----------

    private boolean isTeamLeader(TeamDemand team, User user) {
        return team != null && user != null && team.getCreatorId() != null && team.getCreatorId().equals(user.getId());
    }

    private boolean isTeamParticipant(TeamDemand team, User user) {
        return isTeamLeader(team, user) || (team != null && isApprovedMember(team.getId(), user.getId()));
    }

    private boolean isApprovedMember(Long teamId, Long userId) {
        if (teamId == null || userId == null) return false;
        Long count = teamApplicationService.count(new LambdaQueryWrapper<TeamApplication>()
            .eq(TeamApplication::getTeamId, teamId)
            .eq(TeamApplication::getUserId, userId)
            .eq(TeamApplication::getStatus, "APPROVED"));
        return count != null && count > 0;
    }

    private static String strOf(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static Long longOf(Object value) {
        if (value == null) return null;
        try {
            return Long.valueOf(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
