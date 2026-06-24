package cn.ilink.controller;

import cn.ilink.common.ControllerUtils;
import cn.ilink.common.Result;
import cn.ilink.dto.NotificationDTO;
import cn.ilink.entity.User;
import cn.ilink.service.NotificationService;
import cn.ilink.vo.NotificationVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpSession;
import java.util.*;

@RestController
@RequestMapping("/api/notifications")
public class NotificationController {

    @Autowired
    private NotificationService notificationService;

    @GetMapping
    public ResponseEntity<Result<List<NotificationVO>>> getList(
            @RequestParam Long userId,
            @RequestParam(defaultValue = "20") int limit,
            HttpSession session) {
        User user = ControllerUtils.requireUser(session);
        if (user == null) return ResponseEntity.ok(Result.unauthorized());
        if (!user.getId().equals(userId)) {
            return ResponseEntity.ok(Result.error("无权限访问"));
        }
        List<NotificationVO> list = notificationService.getByUser(userId, limit);
        return ResponseEntity.ok(Result.ok(list));
    }

    @GetMapping("/unread-count")
    public ResponseEntity<Result<Map<String, Object>>> getUnreadCount(
            @RequestParam Long userId,
            HttpSession session) {
        User user = ControllerUtils.requireUser(session);
        if (user == null) return ResponseEntity.ok(Result.unauthorized());
        if (!user.getId().equals(userId)) {
            return ResponseEntity.ok(Result.error("无权限访问"));
        }
        int count = notificationService.getUnreadCount(userId);
        Map<String, Object> data = new HashMap<>();
        data.put("count", count);
        return ResponseEntity.ok(Result.ok(data));
    }

    @PutMapping("/{id}/read")
    public ResponseEntity<Result<Void>> markAsRead(@PathVariable Long id, HttpSession session) {
        User user = ControllerUtils.requireUser(session);
        if (user == null) return ResponseEntity.ok(Result.unauthorized());
        notificationService.markAsRead(id, user.getId());
        return ResponseEntity.ok(Result.ok("已标记为已读", null));
    }

    @PutMapping("/read-all")
    public ResponseEntity<Result<Void>> markAllAsRead(
            @RequestParam Long userId,
            HttpSession session) {
        User user = ControllerUtils.requireUser(session);
        if (user == null) return ResponseEntity.ok(Result.unauthorized());
        if (!user.getId().equals(userId)) {
            return ResponseEntity.ok(Result.error("无权限访问"));
        }
        notificationService.markAllAsRead(userId);
        return ResponseEntity.ok(Result.ok("全部已读", null));
    }

    @PostMapping
    public ResponseEntity<Result<Void>> create(@RequestBody NotificationDTO dto, HttpSession session) {
        User user = ControllerUtils.requireUser(session);
        if (user == null) return ResponseEntity.ok(Result.unauthorized());
        if (dto.getUserId() == null) {
            return ResponseEntity.ok(Result.error("用户ID不能为空"));
        }
        // C-02: 校验权限——只有系统管理员才能给他人发通知，普通用户只能给自己发
        if (!user.getId().equals(dto.getUserId()) && !"ADMIN".equals(user.getRole())) {
            return ResponseEntity.ok(Result.error("无权限创建通知"));
        }
        boolean success = notificationService.createNotification(dto);
        if (success) {
            return ResponseEntity.ok(Result.ok("通知已创建", null));
        } else {
            return ResponseEntity.ok(Result.error("创建通知失败"));
        }
    }
}
