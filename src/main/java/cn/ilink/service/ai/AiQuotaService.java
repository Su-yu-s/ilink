package cn.ilink.service.ai;

import cn.ilink.config.AiProperties;
import cn.ilink.entity.AiUsageLog;
import cn.ilink.mapper.AiUsageLogMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Calendar;
import java.util.Date;

/**
 * 每用户每日调用配额：超限时直接拒绝，不发起外部调用。
 */
@Service
@RequiredArgsConstructor
public class AiQuotaService {

    private final AiUsageLogMapper aiUsageLogMapper;
    private final AiProperties aiProperties;

    /** 今日已用次数 */
    public long usedToday(Long userId) {
        if (userId == null) {
            return Long.MAX_VALUE;
        }
        return aiUsageLogMapper.selectCount(new LambdaQueryWrapper<AiUsageLog>()
            .eq(AiUsageLog::getUserId, userId)
            .ge(AiUsageLog::getCreatedAt, startOfToday()));
    }

    public boolean isOverQuota(Long userId) {
        return usedToday(userId) >= aiProperties.getDailyQuota();
    }

    public int dailyQuota() {
        return aiProperties.getDailyQuota();
    }

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
            // 配额记录失败不阻断主流程
        }
    }

    private static Date startOfToday() {
        Calendar calendar = Calendar.getInstance();
        calendar.set(Calendar.HOUR_OF_DAY, 0);
        calendar.set(Calendar.MINUTE, 0);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        return calendar.getTime();
    }
}
