package cn.ilink.service.impl;

import cn.ilink.entity.ChatMessage;
import cn.ilink.entity.User;
import cn.ilink.mapper.ChatMessageMapper;
import cn.ilink.service.ChatService;
import cn.ilink.service.UserService;
import cn.ilink.vo.ChatMessageVO;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class ChatServiceImpl extends ServiceImpl<ChatMessageMapper, ChatMessage> implements ChatService {

    @Autowired
    private UserService userService;

    @Autowired
    private SimpMessagingTemplate messagingTemplate;

    @Override
    public ChatMessageVO sendMessage(Long teamId, Long senderId, String content, String type) {
        ChatMessage message = new ChatMessage();
        message.setTeamId(teamId);
        message.setSenderId(senderId);
        message.setContent(content);
        message.setMessageType(type != null ? type : "TEXT");
        message.setCreatedAt(new Date());

        save(message);

        User sender = userService.getById(senderId);
        ChatMessageVO vo = toVO(message, sender);

        messagingTemplate.convertAndSend("/topic/team/" + teamId, vo);

        return vo;
    }

    @Override
    public List<ChatMessageVO> getHistory(Long teamId, int limit) {
        int safeLimit = Math.min(Math.max(limit, 1), 200);
        LambdaQueryWrapper<ChatMessage> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ChatMessage::getTeamId, teamId)
                .orderByDesc(ChatMessage::getCreatedAt)
                .last("LIMIT " + safeLimit);

        List<ChatMessage> messages = list(wrapper);
        if (messages.isEmpty()) {
            return Collections.emptyList();
        }
        Set<Long> senderIds = messages.stream()
                .map(ChatMessage::getSenderId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, User> senderMap = senderIds.isEmpty() ? Collections.emptyMap()
                : userService.listByIds(senderIds).stream()
                .collect(Collectors.toMap(User::getId, u -> u, (a, b) -> a));
        List<ChatMessageVO> vos = messages.stream()
                .map(m -> toVO(m, senderMap.get(m.getSenderId())))
                .collect(Collectors.toList());
        Collections.reverse(vos);
        return vos;
    }

    @Override
    public void markAsRead(Long teamId, Long userId) {
    }

    private ChatMessageVO toVO(ChatMessage message) {
        User sender = message.getSenderId() != null ? userService.getById(message.getSenderId()) : null;
        return toVO(message, sender);
    }

    private ChatMessageVO toVO(ChatMessage message, User sender) {
        ChatMessageVO vo = new ChatMessageVO();
        vo.setId(message.getId());
        vo.setTeamId(message.getTeamId());
        vo.setSenderId(message.getSenderId());
        vo.setContent(message.getContent());
        vo.setMessageType(message.getMessageType());
        vo.setCreatedAt(message.getCreatedAt());

        if (sender != null) {
            vo.setSenderName(sender.getRealName() != null ? sender.getRealName() : sender.getUsername());
        }

        return vo;
    }
}
