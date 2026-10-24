package cn.ilink.teamagent;

import java.util.List;
import java.util.Map;

public record TeamAgentActionDraft(
    String schemaVersion,
    String actionType,
    String targetType,
    Long targetId,
    Integer expectedTargetVersion,
    Map<String, Object> before,
    Map<String, Object> after,
    String reason,
    List<Map<String, Object>> evidence
) {
    public static TeamAgentActionDraft of(String schemaVersion, String actionType,
                                          String targetType, Long targetId,
                                          Integer expectedTargetVersion,
                                          Map<String, Object> before, Map<String, Object> after,
                                          String reason, List<Map<String, Object>> evidence) {
        return new TeamAgentActionDraft(schemaVersion, actionType, targetType, targetId,
            expectedTargetVersion, before, after, reason, evidence);
    }
}
