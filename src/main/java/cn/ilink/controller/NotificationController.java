package cn.ilink.controller;

import cn.ilink.common.ControllerUtils;
import cn.ilink.common.Result;
import cn.ilink.dto.NotificationDTO;
import cn.ilink.entity.Notification;
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
    public ResponseEntity<Result<?>> getList(
            @RequestParam Long userId,
            @RequestParam(defaultValue = "20") int limit,
            HttpSession session) {
        User user = ControllerUtils.requireUser(session);
        if (user == null) return Result.unauthorized().toResponseEntity();
        if (!user.getId().equals(userId)) {
            return Result.error("无权限访问").toResponseEntity();
        }
        List<NotificationVO> list = notificationService.getByUser(userId, limit);
        return Result.ok(list).toResponseEntity();
    }

    @GetMapping("/unread-count")
    public ResponseEntity<Result<?>> getUnreadCount(
            @RequestParam Long userId,
            HttpSession session) {
        User user = ControllerUtils.requireUser(session);
        if (user == null) return Result.unauthorized().toResponseEntity();
        if (!user.getId().equals(userId)) {
            return Result.error("无权限访问").toResponseEntity();
        }
        int count = notificationService.getUnreadCount(userId);
        Map<String, Object> data = new HashMap<>();
        data.put("count", count);
        return Result.ok(data).toResponseEntity();
    }

    @PutMapping("/{id}/read")
    public ResponseEntity<Result<?>> markAsRead(@PathVariable Long id, HttpSession session) {
        User user = ControllerUtils.requireUser(session);
        if (user == null) return Result.unauthorized().toResponseEntity();
        // 验证通知所有权：只能标记自己的通知为已读
        Notification notification = notificationService.getById(id);
        if (notification == null || !notification.getUserId().equals(user.getId())) {
            return Result.forbidden().toResponseEntity();
        }
        notificationService.markAsRead(id, user.getId());
        return Result.ok("已标记为已读", null).toResponseEntity();
    }

    @PutMapping("/read-all")
    public ResponseEntity<Result<?>> markAllAsRead(
            @RequestParam Long userId,
            HttpSession session) {
        User user = ControllerUtils.requireUser(session);
        if (user == null) return Result.unauthorized().toResponseEntity();
        if (!user.getId().equals(userId)) {
            return Result.error("无权限访问").toResponseEntity();
        }
        notificationService.markAllAsRead(userId);
        return Result.ok("全部已读", null).toResponseEntity();
    }

    @PostMapping
    public ResponseEntity<Result<?>> create(@RequestBody NotificationDTO dto, HttpSession session) {
        User user = ControllerUtils.requireUser(session);
        if (user == null) return Result.unauthorized().toResponseEntity();
        if (dto.getUserId() == null) {
            return Result.error("用户ID不能为空").toResponseEntity();
        }
        // C-02: 校验权限——只有系统管理员才能给他人发通知，普通用户只能给自己发
        if (!user.getId().equals(dto.getUserId()) && !"ADMIN".equals(user.getRole())) {
            return Result.error("无权限创建通知").toResponseEntity();
        }
        boolean success = notificationService.createNotification(dto);
        if (success) {
            return Result.ok("通知已创建", null).toResponseEntity();
        } else {
            return Result.error("创建通知失败").toResponseEntity();
        }
    }
}
