package cn.ilink.teamagent;

public class TeamAgentException extends RuntimeException {
    private final TeamAgentErrorCode errorCode;
    private final String requestId;
    private final Integer retryAfterSeconds;

    public TeamAgentException(TeamAgentErrorCode errorCode, String message) {
        this(errorCode, message, null, null);
    }

    public TeamAgentException(TeamAgentErrorCode errorCode, String message, String requestId,
                              Integer retryAfterSeconds) {
        super(message);
        this.errorCode = errorCode;
        this.requestId = requestId;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public TeamAgentErrorCode getErrorCode() {
        return errorCode;
    }

    public String getRequestId() {
        return requestId;
    }

    public Integer getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
