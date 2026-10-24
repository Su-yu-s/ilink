package cn.ilink.teamagent;

import org.springframework.http.HttpStatus;

/**
 * Stable API errors for the team Agent workbench. The front end must branch on
 * errorCode and never parse localized message text.
 */
public enum TeamAgentErrorCode {
    AUTH_REQUIRED(HttpStatus.UNAUTHORIZED, false),
    REQUEST_INVALID(HttpStatus.BAD_REQUEST, false),
    TEAM_ACCESS_DENIED(HttpStatus.FORBIDDEN, false),
    AGENT_ACTIONS_DISABLED(HttpStatus.FORBIDDEN, false),
    CONVERSATION_NOT_FOUND(HttpStatus.NOT_FOUND, false),
    CONVERSATION_BUSY(HttpStatus.CONFLICT, true),
    RUN_NOT_FOUND(HttpStatus.NOT_FOUND, false),
    RUN_ALREADY_ACTIVE(HttpStatus.CONFLICT, true),
    RUN_NOT_CANCELLABLE(HttpStatus.CONFLICT, false),
    IDEMPOTENCY_KEY_REUSED(HttpStatus.CONFLICT, false),
    SENSITIVE_INPUT_REJECTED(HttpStatus.BAD_REQUEST, false),
    ACTION_GROUP_NOT_FOUND(HttpStatus.NOT_FOUND, false),
    ACTION_NOT_FOUND(HttpStatus.NOT_FOUND, false),
    ACTION_SELECTION_INVALID(HttpStatus.BAD_REQUEST, false),
    ACTION_DUPLICATE(HttpStatus.CONFLICT, false),
    ACTION_SCHEMA_UNSUPPORTED(HttpStatus.CONFLICT, false),
    ACTION_GROUP_EXPIRED(HttpStatus.GONE, false),
    ACTION_ALREADY_FINALIZED(HttpStatus.CONFLICT, false),
    ACTION_VERSION_CONFLICT(HttpStatus.CONFLICT, true),
    ACTION_PERMISSION_CHANGED(HttpStatus.CONFLICT, true),
    UNDO_NOT_AVAILABLE(HttpStatus.CONFLICT, false),
    UNDO_EXPIRED(HttpStatus.GONE, false),
    UNDO_CONFLICT(HttpStatus.CONFLICT, false),
    UNDO_ALREADY_FINALIZED(HttpStatus.CONFLICT, false),
    AGENT_CAPACITY_EXCEEDED(HttpStatus.TOO_MANY_REQUESTS, true),
    AGENT_RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, true),
    AGENT_BUDGET_EXCEEDED(HttpStatus.TOO_MANY_REQUESTS, false),
    AI_TIMEOUT(HttpStatus.GATEWAY_TIMEOUT, true),
    AI_RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, true),
    AI_UPSTREAM_FAILED(HttpStatus.BAD_GATEWAY, true),
    AI_NOT_CONFIGURED(HttpStatus.SERVICE_UNAVAILABLE, false),
    MODEL_OUTPUT_INVALID(HttpStatus.BAD_GATEWAY, true),
    WORKER_LOST(HttpStatus.INTERNAL_SERVER_ERROR, true),
    ACTION_TRANSACTION_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, true);

    private final HttpStatus status;
    private final boolean retryable;

    TeamAgentErrorCode(HttpStatus status, boolean retryable) {
        this.status = status;
        this.retryable = retryable;
    }

    public HttpStatus status() {
        return status;
    }

    public boolean retryable() {
        return retryable;
    }
}
