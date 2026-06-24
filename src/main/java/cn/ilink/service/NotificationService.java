package cn.ilink.service;

import cn.ilink.dto.NotificationDTO;
import cn.ilink.vo.NotificationVO;
import java.util.List;

public interface NotificationService {
    List<NotificationVO> getByUser(Long userId, int limit);
    int getUnreadCount(Long userId);
    boolean markAsRead(Long id);
    boolean markAllAsRead(Long userId);
    boolean createNotification(NotificationDTO dto);
    List<NotificationVO> getUnreadByUserId(Long userId);
    List<NotificationVO> getByUserId(Long userId, int page, int size);
    long countUnread(Long userId);
    void create(Long userId, String type, String title, String content, Long relatedId);
    void markAsRead(Long notificationId, Long userId);
}
