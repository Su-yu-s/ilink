package cn.ilink.service;

import cn.ilink.vo.NotificationVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 通知 WebSocket 推送服务。
 * 通过 SimpMessagingTemplate 向指定用户的 /topic/notification/{userId} 频道推送实时消息。
 */
@Service
public class NotificationPushService {

    @Autowired
    private SimpMessagingTemplate messagingTemplate;

    /**
     * 向指定用户推送单条通知。
     *
     * @param receiverId 接收者用户 ID
     * @param notification 通知 VO
     */
    public void sendNotification(Long receiverId, NotificationVO notification) {
        String destination = "/topic/notification/" + receiverId;
        messagingTemplate.convertAndSend(destination, notification);
    }

    /**
     * 向指定用户推送未读数变更。
     *
     * @param receiverId 接收者用户 ID
     * @param unreadCount 新的未读数量
     */
    public void sendUnreadCount(Long receiverId, int unreadCount) {
        String destination = "/topic/notification/" + receiverId + "/unread";
        Map<String, Object> payload = Map.of("count", unreadCount);
        messagingTemplate.convertAndSend(destination, payload);
    }

    /**
     * 向指定用户推送批量已读（全部已读后）。
     *
     * @param receiverId 接收者用户 ID
     */
    public void sendMarkAllRead(Long receiverId) {
        String destination = "/topic/notification/" + receiverId + "/mark-all-read";
        messagingTemplate.convertAndSend(destination, Map.of("status", "ALL_READ"));
    }
}
