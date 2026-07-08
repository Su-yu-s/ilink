package cn.ilink.service.impl;

import cn.ilink.dto.NotificationDTO;
import cn.ilink.entity.Notification;
import cn.ilink.mapper.NotificationMapper;
import cn.ilink.service.NotificationPushService;
import cn.ilink.service.NotificationService;
import cn.ilink.util.CacheEvictUtils;
import cn.ilink.vo.NotificationVO;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 通知服务实现。
 * 未读数走 Caffeine 缓存，已读操作触发缓存淘汰。
 * 通知写入后通过 WebSocket 实时推送给接收者。
 */
@Service
public class NotificationServiceImpl implements NotificationService {

    @Autowired
    private NotificationMapper notificationMapper;

    @Autowired
    private NotificationPushService notificationPushService;

    @Override
    public Notification getById(Long id) {
        return notificationMapper.selectById(id);
    }

    @Override
    public List<NotificationVO> getByUser(Long userId, int limit) {
        int safeLimit = Math.min(Math.max(limit, 1), 200);
        Page<Notification> page = new Page<>(1, safeLimit);
        Page<Notification> result = notificationMapper.selectPage(page,
            new LambdaQueryWrapper<Notification>()
                .eq(Notification::getUserId, userId)
                .orderByDesc(Notification::getCreatedAt));
        return convertToVO(result.getRecords());
    }

    /**
     * 获取用户未读通知数量，结果缓存到 unreadCount。
     */
    @Override
    @Cacheable(value = "unreadCount", key = "#userId", unless = "#result == null || #result == 0")
    public int getUnreadCount(Long userId) {
        Long count = notificationMapper.selectCount(
            new LambdaQueryWrapper<Notification>()
                .eq(Notification::getUserId, userId)
                .eq(Notification::getIsRead, false));
        return count != null ? count.intValue() : 0;
    }

    @Override
    public boolean markAsRead(Long id) {
        Notification notification = notificationMapper.selectById(id);
        if (notification == null) return false;
        notification.setIsRead(true);
        boolean updated = notificationMapper.updateById(notification) > 0;
        if (updated) {
            // 淘汰未读数缓存，并推送未读数更新
            CacheEvictUtils.evictUnreadCount(notification.getUserId());
            int unreadCount = getUnreadCount(notification.getUserId());
            notificationPushService.sendUnreadCount(notification.getUserId(), unreadCount);
        }
        return updated;
    }

    @Override
    public boolean markAllAsRead(Long userId) {
        notificationMapper.update(null,
            new LambdaUpdateWrapper<Notification>()
                .eq(Notification::getUserId, userId)
                .eq(Notification::getIsRead, false)
                .set(Notification::getIsRead, true));
        // 批量淘汰未读数缓存
        CacheEvictUtils.evictUnreadCount(userId);
        // 推送批量已读消息
        notificationPushService.sendMarkAllRead(userId);
        notificationPushService.sendUnreadCount(userId, 0);
        return true;
    }

    @Override
    public boolean createNotification(NotificationDTO dto) {
        Notification n = new Notification();
        n.setUserId(dto.getUserId());
        n.setSenderId(dto.getSenderId());
        n.setType(dto.getType());
        n.setTitle(dto.getTitle());
        n.setContent(dto.getContent());
        n.setRelatedId(dto.getRelatedId());
        n.setRelatedType(dto.getRelatedType());
        n.setIsRead(false);
        n.setCreatedAt(new Date());
        boolean inserted = notificationMapper.insert(n) > 0;
        if (inserted) {
            // 写入后实时推送通知和未读数
            NotificationVO vo = convertToVO(Collections.singletonList(n)).get(0);
            notificationPushService.sendNotification(dto.getUserId(), vo);
            int unreadCount = getUnreadCount(dto.getUserId());
            notificationPushService.sendUnreadCount(dto.getUserId(), unreadCount);
        }
        return inserted;
    }

    @Override
    public List<NotificationVO> getUnreadByUserId(Long userId) {
        Page<Notification> page = new Page<>(1, 200);
        Page<Notification> result = notificationMapper.selectPage(page,
            new LambdaQueryWrapper<Notification>()
                .eq(Notification::getUserId, userId)
                .eq(Notification::getIsRead, false)
                .orderByDesc(Notification::getCreatedAt));
        return convertToVO(result.getRecords());
    }

    @Override
    public List<NotificationVO> getByUserId(Long userId, int page, int size) {
        int safeSize = Math.min(Math.max(size, 1), 200);
        int safePage = Math.max(page, 1);
        Page<Notification> pageObj = new Page<>(safePage, safeSize);
        Page<Notification> result = notificationMapper.selectPage(pageObj,
            new LambdaQueryWrapper<Notification>()
                .eq(Notification::getUserId, userId)
                .orderByDesc(Notification::getCreatedAt));
        return convertToVO(result.getRecords());
    }

    @Override
    public long countUnread(Long userId) {
        return notificationMapper.selectCount(
            new LambdaQueryWrapper<Notification>()
                .eq(Notification::getUserId, userId)
                .eq(Notification::getIsRead, false));
    }

    /**
     * 快捷创建通知（不传发送方，senderId 为 null，表示系统通知）。
     */
    @Override
    public void create(Long userId, String type, String title, String content, Long relatedId) {
        create(userId, null, type, title, content, relatedId);
    }

    /**
     * 快捷创建通知（传入发送方 userId）。
     * 写库后自动淘汰未读数缓存，并通过 WebSocket 推送。
     */
    @Override
    @CacheEvict(value = "unreadCount", key = "#userId")
    public void create(Long userId, Long senderId, String type, String title, String content, Long relatedId) {
        Notification n = new Notification();
        n.setUserId(userId);
        n.setSenderId(senderId);
        n.setType(type);
        n.setTitle(title);
        n.setContent(content);
        n.setRelatedId(relatedId);
        n.setIsRead(false);
        n.setCreatedAt(new Date());
        notificationMapper.insert(n);

        // 写入后立即推送通知和未读数变更
        NotificationVO vo = convertToVO(Collections.singletonList(n)).get(0);
        notificationPushService.sendNotification(userId, vo);
        notificationPushService.sendUnreadCount(userId, vo.getIsRead() ? 0 : 1);
    }

    @Override
    public void markAsRead(Long notificationId, Long userId) {
        notificationMapper.update(null,
            new LambdaUpdateWrapper<Notification>()
                .eq(Notification::getId, notificationId)
                .eq(Notification::getUserId, userId)
                .set(Notification::getIsRead, true));
    }

    private List<NotificationVO> convertToVO(List<Notification> notifications) {
        return notifications.stream().map(n -> {
            NotificationVO vo = new NotificationVO();
            BeanUtils.copyProperties(n, vo);
            vo.setTimeAgo(formatTimeAgo(n.getCreatedAt()));
            return vo;
        }).collect(Collectors.toList());
    }

    private String formatTimeAgo(Date date) {
        if (date == null) return "";
        long diff = System.currentTimeMillis() - date.getTime();
        long seconds = diff / 1000;
        if (seconds < 60) return "刚刚";
        long minutes = seconds / 60;
        if (minutes < 60) return minutes + "分钟前";
        long hours = minutes / 60;
        if (hours < 24) return hours + "小时前";
        long days = hours / 24;
        if (days < 7) return days + "天前";
        long weeks = days / 7;
        if (weeks < 4) return weeks + "周前";
        long months = days / 30;
        if (months < 12) return months + "月前";
        long years = days / 365;
        return years + "年前";
    }
}
