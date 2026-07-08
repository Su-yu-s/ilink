package cn.ilink.service.impl;

import cn.ilink.entity.Notification;
import cn.ilink.mapper.NotificationMapper;
import cn.ilink.service.NotificationPushService;
import cn.ilink.vo.NotificationVO;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * NotificationService 核心业务单元测试。
 * 覆盖通知创建、未读计数、标记已读等核心流程。
 */
@ExtendWith(MockitoExtension.class)
class NotificationServiceImplTest {

    @Mock
    private NotificationMapper notificationMapper;

    @Mock
    private NotificationPushService notificationPushService;

    @InjectMocks
    private NotificationServiceImpl notificationService;

    private Notification sampleNotification;

    @BeforeEach
    void setUp() {
        sampleNotification = new Notification();
        sampleNotification.setId(1L);
        sampleNotification.setUserId(100L);
        sampleNotification.setType("SYSTEM");
        sampleNotification.setTitle("测试通知");
        sampleNotification.setContent("这是一条测试通知");
        sampleNotification.setIsRead(false);
        sampleNotification.setCreatedAt(new Date());
    }

    // ===== 创建通知 =====

    @Test
    void createWithoutSenderIdInsertsNotification() {
        when(notificationMapper.insert(any(Notification.class))).thenReturn(1);

        notificationService.create(100L, "SYSTEM", "标题", "内容", 1L);

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationMapper).insert(captor.capture());
        Notification saved = captor.getValue();
        assertEquals(100L, saved.getUserId());
        assertNull(saved.getSenderId());  // 无发送方=系统通知
        assertEquals("SYSTEM", saved.getType());
        assertEquals("标题", saved.getTitle());
        assertEquals("内容", saved.getContent());
        assertEquals(1L, saved.getRelatedId());
        assertFalse(saved.getIsRead());
        assertNotNull(saved.getCreatedAt());
    }

    @Test
    void createWithSenderIdIncludesSender() {
        when(notificationMapper.insert(any(Notification.class))).thenReturn(1);

        notificationService.create(100L, 200L, "TEAM_APPLY", "组队申请", "用户A申请加入", 5L);

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationMapper).insert(captor.capture());
        assertEquals(200L, captor.getValue().getSenderId());
    }

    @Test
    void createPushesNotificationViaWebSocket() {
        when(notificationMapper.insert(any(Notification.class))).thenReturn(1);

        notificationService.create(100L, "SYSTEM", "标题", "内容", null);

        verify(notificationPushService).sendNotification(eq(100L), any(NotificationVO.class));
        verify(notificationPushService).sendUnreadCount(eq(100L), anyInt());
    }

    // ===== 未读计数 =====

    @Test
    void getUnreadCountReturnsZeroWhenNoUnread() {
        when(notificationMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);

        int count = notificationService.getUnreadCount(100L);

        assertEquals(0, count);
    }

    @Test
    void getUnreadCountReturnsCorrectNumber() {
        when(notificationMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(5L);

        int count = notificationService.getUnreadCount(100L);

        assertEquals(5, count);
    }

    // ===== 标记已读 =====

    @Test
    void markAsReadReturnsFalseWhenNotificationNotFound() {
        when(notificationMapper.selectById(999L)).thenReturn(null);

        boolean result = notificationService.markAsRead(999L);

        assertFalse(result);
        verify(notificationMapper, never()).updateById(any());
    }

    @Test
    void markAsReadUpdatesAndPushesUnreadCount() {
        when(notificationMapper.selectById(1L)).thenReturn(sampleNotification);
        when(notificationMapper.updateById(any(Notification.class))).thenReturn(1);
        when(notificationMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);

        boolean result = notificationService.markAsRead(1L);

        assertTrue(result);
        assertTrue(sampleNotification.getIsRead());
        verify(notificationPushService).sendUnreadCount(eq(100L), eq(0));
    }

    // ===== 格式化时间 =====

    @Test
    void createNotificationSetsCreatedAtToCurrentTime() {
        when(notificationMapper.insert(any(Notification.class))).thenReturn(1);

        notificationService.create(100L, "TEST", "标题", "内容", null);

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationMapper).insert(captor.capture());
        assertNotNull(captor.getValue().getCreatedAt());
        // 创建时间应该在最近1秒内
        long diff = System.currentTimeMillis() - captor.getValue().getCreatedAt().getTime();
        assertTrue(diff < 5000, "创建时间应在当前时间附近");
    }
}
