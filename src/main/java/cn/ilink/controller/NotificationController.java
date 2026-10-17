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
            @RequestParam(required = false) Long userId,
            @RequestParam(defaultValue = "20") int limit,
            HttpSession session) {
        User user = ControllerUtils.requireUser(session);
        if (user == null) return Result.unauthorized().toResponseEntity();
        // userId 可省略：置空时直接按当前会话推导；显式传了别人的才拒绝
        if (userId == null) {
            userId = user.getId();
        } else if (!user.getId().equals(userId)) {
            return Result.forbidden().toResponseEntity();
        }
        // 修复 D-P2-8：limit 封顶，防止 limit=100000 拉全量
        List<NotificationVO> list = notificationService.getByUser(userId, ControllerUtils.safeSize(limit, 100));
        return Result.ok(list).toResponseEntity();
    }

    @GetMapping("/unread-count")
    public ResponseEntity<Result<?>> getUnreadCount(
            @RequestParam(required = false) Long userId,
            HttpSession session) {
        User user = ControllerUtils.requireUser(session);
        if (user == null) return Result.unauthorized().toResponseEntity();
        if (userId == null) {
            userId = user.getId();
        } else if (!user.getId().equals(userId)) {
            return Result.forbidden().toResponseEntity();
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
            @RequestParam(required = false) Long userId,
            HttpSession session) {
        User user = ControllerUtils.requireUser(session);
        if (user == null) return Result.unauthorized().toResponseEntity();
        if (userId == null) {
            userId = user.getId();
        } else if (!user.getId().equals(userId)) {
            return Result.forbidden().toResponseEntity();
        }
        notificationService.markAllAsRead(userId);
        return Result.ok("全部已读", null).toResponseEntity();
    }

    @PostMapping
    public ResponseEntity<Result<?>> create(@RequestBody NotificationDTO dto, HttpSession session) {
        User user = ControllerUtils.requireUser(session);
        if (user == null) return Result.unauthorized().toResponseEntity();
        // 修复 D-P2-8：业务错误原来走 Result.error → HTTP 500，污染错误监控；改为正确的 400/403
        if (dto.getUserId() == null) {
            return Result.badRequest("用户ID不能为空").toResponseEntity();
        }
        // C-02: 校验权限——只有系统管理员才能给他人发通知，普通用户只能给自己发
        if (!user.getId().equals(dto.getUserId()) && !"ADMIN".equals(user.getRole())) {
            return Result.forbidden().toResponseEntity();
        }
        boolean success = notificationService.createNotification(dto);
        if (success) {
            return Result.ok("通知已创建", null).toResponseEntity();
        } else {
            return Result.badRequest("创建通知失败").toResponseEntity();
        }
    }
}
