package cn.ilink.service.ai;

import java.util.function.Consumer;

/** Usage observation is independent of algorithm limits and includes failed HTTP attempts. */
public record ChatUsage(Integer promptTokens, Integer completionTokens, boolean successful) {
    private static final ThreadLocal<Consumer<ChatUsage>> OBSERVER = new ThreadLocal<>();

    public boolean complete() { return promptTokens != null && completionTokens != null; }

    public static ChatRequestBudget.Scope observe(Consumer<ChatUsage> observer) {
        Consumer<ChatUsage> previous = OBSERVER.get();
        OBSERVER.set(previous == null ? observer : previous.andThen(observer));
        return () -> { if (previous == null) OBSERVER.remove(); else OBSERVER.set(previous); };
    }

    void publish() {
        Consumer<ChatUsage> observer = OBSERVER.get();
        if (observer != null) observer.accept(this);
    }
}
