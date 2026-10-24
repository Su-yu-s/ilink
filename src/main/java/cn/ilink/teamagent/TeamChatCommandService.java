package cn.ilink.teamagent;

import cn.ilink.entity.ChatMessage;
import cn.ilink.entity.TeamDemand;
import cn.ilink.entity.User;
import cn.ilink.mapper.ChatMessageMapper;
import cn.ilink.mapper.TeamDemandMapper;
import cn.ilink.service.UserService;
import cn.ilink.util.DisplayNameResolver;
import cn.ilink.vo.ChatMessageVO;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Date;

/**
 * Shared group-chat write authority for human pages and confirmed Agent
 * actions. WebSocket delivery is deliberately deferred until commit.
 */
@Service
public class TeamChatCommandService {
    private final TeamAgentAuthorization authorization;
    private final TeamDemandMapper teamDemandMapper;
    private final ChatMessageMapper chatMessageMapper;
    private final UserService userService;
    private final SimpMessagingTemplate messagingTemplate;

    public TeamChatCommandService(TeamAgentAuthorization authorization,
                                  TeamDemandMapper teamDemandMapper,
                                  ChatMessageMapper chatMessageMapper,
                                  UserService userService,
                                  SimpMessagingTemplate messagingTemplate) {
        this.authorization = authorization;
        this.teamDemandMapper = teamDemandMapper;
        this.chatMessageMapper = chatMessageMapper;
        this.userService = userService;
        this.messagingTemplate = messagingTemplate;
    }

    @Transactional(rollbackFor = Exception.class)
    public ChatMessageVO sendConfirmed(Long teamId, Long actorUserId, String content) {
        TeamAgentScope scope = authorization.requireScope(teamId, actorUserId);
        TeamDemand team = teamDemandMapper.selectById(teamId);
        if (team == null || "DISSOLVED".equalsIgnoreCase(team.getStatus())) {
            throw new TeamAgentException(TeamAgentErrorCode.ACTION_PERMISSION_CHANGED,
                "团队不存在或不可访问");
        }
        if (!"TEAMING".equalsIgnoreCase(scope.teamStatus())) {
            throw new TeamAgentException(TeamAgentErrorCode.ACTION_PERMISSION_CHANGED,
                "当前团队状态不允许发送团队消息");
        }
        String safe = TeamAgentMarkdownSanitizer.sanitize(content, 6000);
        if (safe.isBlank()) {
            throw new TeamAgentException(TeamAgentErrorCode.ACTION_SELECTION_INVALID,
                "消息正文不能为空");
        }

        ChatMessage message = new ChatMessage();
        message.setTeamId(teamId);
        message.setSenderId(actorUserId);
        message.setContent(safe);
        message.setMessageType("TEXT");
        message.setCreatedAt(new Date());
        if (chatMessageMapper.insert(message) != 1) {
            throw new TeamAgentException(TeamAgentErrorCode.ACTION_TRANSACTION_FAILED,
                "团队消息保存失败");
        }

        User sender = userService.getById(actorUserId);
        ChatMessageVO vo = toView(message, sender);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    messagingTemplate.convertAndSend("/topic/team/" + teamId, vo);
                }
            });
        } else {
            messagingTemplate.convertAndSend("/topic/team/" + teamId, vo);
        }
        return vo;
    }

    private ChatMessageVO toView(ChatMessage message, User sender) {
        ChatMessageVO view = new ChatMessageVO();
        view.setId(message.getId());
        view.setTeamId(message.getTeamId());
        view.setSenderId(message.getSenderId());
        view.setContent(message.getContent());
        view.setMessageType(message.getMessageType());
        view.setCreatedAt(message.getCreatedAt());
        if (sender != null) {
            view.setSenderName(DisplayNameResolver.resolve(sender));
            view.setSenderAvatar(resolveAvatarUrl(sender.getAvatar()));
        }
        return view;
    }

    private String resolveAvatarUrl(String avatar) {
        if (avatar == null || avatar.isBlank()) return null;
        if (avatar.startsWith("http://") || avatar.startsWith("https://")
            || avatar.startsWith("//") || avatar.startsWith("/")) {
            return avatar;
        }
        return "/uploads/" + avatar;
    }
}
