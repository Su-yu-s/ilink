package cn.ilink.service.ai;

import cn.ilink.entity.AiUsageLog;
import cn.ilink.mapper.AiUsageLogMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * AI 调用用量记录：只做审计留痕，不设上限、不做拦截。
 */
@Service
@RequiredArgsConstructor
public class AiUsageService {

    private final AiUsageLogMapper aiUsageLogMapper;

    public void record(Long userId, Long teamId, String action,
                       Integer promptTokens, Integer completionTokens, boolean success) {
        try {
            AiUsageLog entry = new AiUsageLog();
            entry.setUserId(userId);
            entry.setTeamId(teamId);
            entry.setAction(action);
            entry.setPromptTokens(promptTokens);
            entry.setCompletionTokens(completionTokens);
            entry.setSuccess(success);
            aiUsageLogMapper.insert(entry);
        } catch (Exception ignored) {
            // 用量记录失败不阻断主流程
        }
    }
}
