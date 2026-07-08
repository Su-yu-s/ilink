package cn.ilink.service;

import cn.ilink.dto.NotificationDTO;
import cn.ilink.entity.Notification;
import cn.ilink.vo.NotificationVO;
import java.util.List;

/**
 * 通知服务接口。
 * 提供通知的创建、查询、已读标记等功能。
 */
public interface NotificationService {
    /** 根据 ID 获取通知 */
    Notification getById(Long id);

    /** 获取用户最近通知（不超过 limit 条） */
    List<NotificationVO> getByUser(Long userId, int limit);

    /** 获取用户未读通知数量 */
    int getUnreadCount(Long userId);

    /** 标记单条通知为已读（不校验所有权） */
    boolean markAsRead(Long id);

    /** 标记用户所有通知为已读 */
    boolean markAllAsRead(Long userId);

    /** 从 DTO 创建通知 */
    boolean createNotification(NotificationDTO dto);

    /** 获取用户未读通知列表 */
    List<NotificationVO> getUnreadByUserId(Long userId);

    /** 分页获取用户通知 */
    List<NotificationVO> getByUserId(Long userId, int page, int size);

    /** 统计未读数量（返回 long） */
    long countUnread(Long userId);

    /** 快捷创建通知（fire-and-forget，不传 senderId） */
    void create(Long userId, String type, String title, String content, Long relatedId);

    /** 快捷创建通知（传入 senderId） */
    void create(Long userId, Long senderId, String type, String title, String content, Long relatedId);

    /** 标记指定用户的某条通知为已读（带所有权校验） */
    void markAsRead(Long notificationId, Long userId);
}
