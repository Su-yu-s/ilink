package cn.ilink.teamagent;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/** Strict HTTP request contracts for the team Agent workbench. */
public final class TeamAgentRequests {
    private TeamAgentRequests() {}

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record CreateConversation(String clientRequestId, String title) {}

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record CreateRun(Long conversationId, String clientRequestId,
                            RunMessage message, RunFocus focus) {}

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record RunMessage(String text, String mode) {}

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record RunFocus(List<Long> taskIds) {
        public RunFocus {
            taskIds = taskIds == null ? List.of() : List.copyOf(taskIds);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record CancelRun(String clientRequestId) {}

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record ConfirmActions(List<Long> selectedActionIds, Integer expectedGroupVersion,
                                 String clientRequestId, String existingLeaseToken) {}

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record DismissActions(Integer expectedGroupVersion, String clientRequestId) {}

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record UndoAction(Integer expectedActionVersion, String clientRequestId,
                             String existingLeaseToken) {}
}
