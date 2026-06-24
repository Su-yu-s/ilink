package cn.ilink.service.impl;

import cn.ilink.dto.NotificationDTO;
import cn.ilink.entity.Notification;
import cn.ilink.mapper.NotificationMapper;
import cn.ilink.service.NotificationService;
import cn.ilink.vo.NotificationVO;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class NotificationServiceImpl implements NotificationService {

    @Autowired
    private NotificationMapper notificationMapper;

    @Override
    public List<NotificationVO> getByUser(Long userId, int limit) {
        int safeLimit = Math.min(Math.max(limit, 1), 200);
        List<Notification> notifications = notificationMapper.selectList(
            new LambdaQueryWrapper<Notification>()
                .eq(Notification::getUserId, userId)
                .orderByDesc(Notification::getCreatedAt)
                .last("LIMIT " + safeLimit));
        return convertToVO(notifications);
    }

    @Override
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
        return notificationMapper.updateById(notification) > 0;
    }

    @Override
    public boolean markAllAsRead(Long userId) {
        notificationMapper.update(null,
            new LambdaUpdateWrapper<Notification>()
                .eq(Notification::getUserId, userId)
                .eq(Notification::getIsRead, false)
                .set(Notification::getIsRead, true));
        return true;
    }

    @Override
    public boolean createNotification(NotificationDTO dto) {
        Notification n = new Notification();
        n.setUserId(dto.getUserId());
        n.setType(dto.getType());
        n.setTitle(dto.getTitle());
        n.setContent(dto.getContent());
        n.setRelatedId(dto.getRelatedId());
        n.setRelatedType(dto.getRelatedType());
        n.setIsRead(false);
        n.setCreatedAt(new Date());
        return notificationMapper.insert(n) > 0;
    }

    @Override
    public List<NotificationVO> getUnreadByUserId(Long userId) {
        List<Notification> notifications = notificationMapper.selectList(
            new LambdaQueryWrapper<Notification>()
                .eq(Notification::getUserId, userId)
                .eq(Notification::getIsRead, false)
                .orderByDesc(Notification::getCreatedAt)
                .last("LIMIT 200"));
        return convertToVO(notifications);
    }

    @Override
    public List<NotificationVO> getByUserId(Long userId, int page, int size) {
        int safeSize = Math.min(Math.max(size, 1), 200);
        int safePage = Math.max(page, 1);
        List<Notification> notifications = notificationMapper.selectList(
            new LambdaQueryWrapper<Notification>()
                .eq(Notification::getUserId, userId)
                .orderByDesc(Notification::getCreatedAt)
                .last("LIMIT " + safeSize + " OFFSET " + (safePage - 1) * safeSize));
        return convertToVO(notifications);
    }

    @Override
    public long countUnread(Long userId) {
        return notificationMapper.selectCount(
            new LambdaQueryWrapper<Notification>()
                .eq(Notification::getUserId, userId)
                .eq(Notification::getIsRead, false));
    }

    @Override
    public void create(Long userId, String type, String title, String content, Long relatedId) {
        Notification n = new Notification();
        n.setUserId(userId);
        n.setType(type);
        n.setTitle(title);
        n.setContent(content);
        n.setIsRead(false);
        n.setRelatedId(relatedId);
        n.setCreatedAt(new Date());
        notificationMapper.insert(n);
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
