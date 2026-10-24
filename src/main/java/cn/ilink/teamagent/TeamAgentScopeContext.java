package cn.ilink.teamagent;

/**
 * Request/worker-local hard boundary for the existing GraphRAG tools.
 *
 * The model never supplies these values. A Team Agent run binds the scope
 * derived from the URL team, session user and durable membership before it
 * invokes Agentic GraphRAG, then always closes the scope in a finally block.
 */
public final class TeamAgentScopeContext {
    private static final ThreadLocal<TeamAgentScope> CURRENT = new ThreadLocal<>();

    private TeamAgentScopeContext() {}

    public static TeamAgentScope current() {
        return CURRENT.get();
    }

    public static TeamAgentScope require() {
        TeamAgentScope scope = CURRENT.get();
        if (scope == null) {
            throw new TeamAgentException(TeamAgentErrorCode.TEAM_ACCESS_DENIED,
                "团队 Agent 作用域未建立");
        }
        return scope;
    }

    public static BoundScope bind(TeamAgentScope scope) {
        if (scope == null) {
            throw new IllegalArgumentException("scope is required");
        }
        TeamAgentScope previous = CURRENT.get();
        CURRENT.set(scope);
        return new BoundScope(previous);
    }

    public static final class BoundScope implements AutoCloseable {
        private final TeamAgentScope previous;
        private boolean closed;

        private BoundScope(TeamAgentScope previous) {
            this.previous = previous;
        }

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }
}
