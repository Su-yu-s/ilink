package cn.ilink.service;

import cn.ilink.entity.ChatMessage;
import cn.ilink.vo.ChatMessageVO;
import com.baomidou.mybatisplus.extension.service.IService;
import java.util.List;

public interface ChatService extends IService<ChatMessage> {
    ChatMessageVO sendMessage(Long teamId, Long senderId, String content, String type);
    List<ChatMessageVO> getHistory(Long teamId, int limit);
    void markAsRead(Long teamId, Long userId);
}
